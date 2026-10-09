package com.motoonai.twa;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.net.Uri;
import android.util.Log;
import android.webkit.MimeTypeMap;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpCookie;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;

/**
 * Loopback-only HTTP host for the packaged Munibin frontend.
 *
 * The browser-facing origin is local, so the APK owns its UI and Service Worker.
 * Only backend-dependent /api and /manus-storage requests are proxied to munibin.com.
 * A small persistent cookie jar keeps authenticated backend sessions independent of
 * WebView cookies, whose origin is intentionally 127.0.0.1.
 */
final class NativeAppServer {
    private static final String TAG = "MunibinNativeServer";
    private static final String BACKEND_ORIGIN = "https://munibin.com";
    private static final String ASSET_ROOT = "www";
    private static final String OFFLINE_ROOT = "www/native-offline";
    private static final String QURAN_RECITER_OFFLINE_PLAYBACK_PATH = "/__quran-reciter-offline-audio";
    // The reciter catalog is dynamic admin data. Never serve the bundled build-time
    // seed for this path, otherwise additions/edits/deletions can never reach users.
    private static final String QURAN_RECITER_CATALOG_PATH = "/api/quran/reciters";
    // This API-looking path is deliberately outside the old app-shell fetch handler.
    // Older Service Workers explicitly pass /api/* through, so a stale worker cannot
    // replace this bootstrap with its cached index.html.
    private static final String SHELL_RESET_PATH = "/api/__native-shell-reset-v196";
    private static final int MAX_REQUEST_BODY = 64 * 1024 * 1024;
    // Stable origin is required so Service Worker, Cache Storage, IndexedDB and localStorage
    // survive process restarts. Changing the port would create a new browser origin.
    private static final int LOCAL_PORT = 49321;

    private final Context context;
    private final AssetManager assets;
    private final ExecutorService acceptExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService connectionExecutor = Executors.newCachedThreadPool();
    private final BackendCookieJar cookieJar;
    // Cache only text decoded on demand, not the WOFF2 fonts or the entire
    // offline bundle. A strict LRU budget keeps repeat tafsir/page reads cheap
    // without retaining the 20 MB mutoon script or growing with all 604 pages.
    private static final int DECODED_ASSET_CACHE_BYTES = 16 * 1024 * 1024;
    private static final int DECODED_ASSET_CACHE_ENTRIES = 128;
    private static final int DECODED_ASSET_CACHE_FILE_BYTES = 8 * 1024 * 1024;
    private final Object decodedAssetCacheLock = new Object();
    private final LinkedHashMap<String, byte[]> decodedAssetCache = new LinkedHashMap<>(16, 0.75f, true);
    private int decodedAssetCacheBytes;
    private volatile boolean running;

    private volatile ServerSocket serverSocket;
    private volatile String baseUrl;

    NativeAppServer(Context context) {
        this.context = context.getApplicationContext();
        this.assets = this.context.getAssets();
        this.cookieJar = new BackendCookieJar(this.context);
    }

    synchronized String start() throws IOException {
        if (baseUrl != null) return baseUrl;
        ServerSocket socket = new ServerSocket(LOCAL_PORT, 32, InetAddress.getByName("127.0.0.1"));
        serverSocket = socket;
        running = true;
        baseUrl = "http://127.0.0.1:" + socket.getLocalPort();
        acceptExecutor.execute(() -> acceptLoop(socket));
        return baseUrl;
    }

    String getBaseUrl() {
        return baseUrl;
    }

    Uri localUriFor(Uri externalOrLocal) {
        if (baseUrl == null) return Uri.parse("about:blank");
        if (externalOrLocal == null) return Uri.parse(baseUrl + "/");
        String host = externalOrLocal.getHost();
        if ("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host)) return externalOrLocal;
        Uri.Builder builder = Uri.parse(baseUrl).buildUpon();
        builder.path(externalOrLocal.getPath() == null || externalOrLocal.getPath().isEmpty() ? "/" : externalOrLocal.getPath());
        builder.encodedQuery(externalOrLocal.getEncodedQuery());
        builder.encodedFragment(externalOrLocal.getEncodedFragment());
        return builder.build();
    }

    void stop() {
        running = false;
        synchronized (decodedAssetCacheLock) {
            decodedAssetCache.clear();
            decodedAssetCacheBytes = 0;
        }
        ServerSocket socket = serverSocket;
        serverSocket = null;
        baseUrl = null;
        if (socket != null) {
            try { socket.close(); } catch (IOException ignored) {}
        }
        acceptExecutor.shutdownNow();
        connectionExecutor.shutdownNow();
    }

    private void acceptLoop(ServerSocket socket) {
        while (!socket.isClosed()) {
            try {
                Socket client = socket.accept();
                client.setSoTimeout(120_000);
                connectionExecutor.execute(() -> handleSocket(client));
            } catch (IOException error) {
                if (!socket.isClosed()) Log.w(TAG, "Accept failed", error);
            }
        }
    }

    private void handleSocket(Socket socket) {
        try (Socket client = socket;
             BufferedInputStream input = new BufferedInputStream(client.getInputStream());
             BufferedOutputStream output = new BufferedOutputStream(client.getOutputStream())) {
            HttpRequest request = readRequest(input);
            if (request == null) return;
            handle(request, output);
            output.flush();
        } catch (Exception error) {
            Log.w(TAG, "Local request failed", error);
        }
    }

    private HttpRequest readRequest(BufferedInputStream input) throws IOException {
        String requestLine = readAsciiLine(input);
        if (requestLine == null || requestLine.trim().isEmpty()) return null;
        String[] requestParts = requestLine.split(" ", 3);
        if (requestParts.length < 2) throw new IOException("Malformed HTTP request line");

        Map<String, String> headers = new HashMap<>();
        while (true) {
            String line = readAsciiLine(input);
            if (line == null || line.isEmpty()) break;
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            headers.put(line.substring(0, colon).trim().toLowerCase(Locale.US), line.substring(colon + 1).trim());
        }

        byte[] body;
        String transferEncoding = headers.get("transfer-encoding");
        if (transferEncoding != null && transferEncoding.toLowerCase(Locale.US).contains("chunked")) {
            body = readChunkedBody(input);
        } else {
            int contentLength = parseInt(headers.get("content-length"), 0);
            if (contentLength < 0 || contentLength > MAX_REQUEST_BODY) throw new IOException("Request body too large");
            body = readExactly(input, contentLength);
        }
        return new HttpRequest(requestParts[0].toUpperCase(Locale.US), requestParts[1], headers, body);
    }

    private void handle(HttpRequest request, BufferedOutputStream output) throws IOException {
        Uri requestUri = Uri.parse(baseUrl + request.target);
        String path = requestUri.getPath() == null ? "/" : requestUri.getPath();

        // Break out of stale Service Worker app-shell caches after a native update.
        // This must run before generic /api proxy handling.
        if (("GET".equals(request.method) || "HEAD".equals(request.method)) && SHELL_RESET_PATH.equals(path)) {
            serveShellReset(request, output);
            return;
        }

        // The native app owns its Service Worker. Never ask the website for the worker.
        if (isServiceWorkerPath(path)) {
            serveAsset(request, output, ASSET_ROOT + "/sw.js", "application/javascript; charset=utf-8", false);
            return;
        }

        // The Service Worker normally owns this synthetic playback URL so downloaded
        // recitations come from Cache Storage. During the first native launch the worker
        // may not control the document yet, so provide an online native fallback instead
        // of accidentally serving index.html as audio.
        if (("GET".equals(request.method) || "HEAD".equals(request.method)) && QURAN_RECITER_OFFLINE_PLAYBACK_PATH.equals(path)) {
            proxyExternalAudio(request, requestUri, output);
            return;
        }

        // Build-time seed for resources that the web PWA intentionally made offline.
        // If a seed file is present it wins even without a network connection.
        if (("GET".equals(request.method) || "HEAD".equals(request.method)) && isBackendPath(path) && !QURAN_RECITER_CATALOG_PATH.equals(path)) {
            String seedAsset = offlineSeedAssetPath(path);
            if (assetExists(seedAsset)) {
                serveAsset(request, output, seedAsset, mimeType(path), true);
                return;
            }
        }

        if (isBackendPath(path)) {
            proxy(request, requestUri, output);
            return;
        }

        if (!"GET".equals(request.method) && !"HEAD".equals(request.method)) {
            sendBytes(output, 405, "text/plain; charset=utf-8", "Method Not Allowed".getBytes(StandardCharsets.UTF_8), "no-store", request.method);
            return;
        }

        serveFrontend(request, output, path);
    }

    private void serveShellReset(HttpRequest request, BufferedOutputStream output) throws IOException {
        String html = "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
                "<style>html,body{margin:0;width:100%;height:100%;background:#fdf8f0}</style></head><body><script>" +
                "(async()=>{const u=new URL(location.href);const next=u.searchParams.get('next')||'/';" +
                "try{if('serviceWorker'in navigator){const rs=await navigator.serviceWorker.getRegistrations();await Promise.all(rs.map(r=>r.unregister()));}}catch(e){}" +
                "try{if('caches'in window){const ks=await caches.keys();await Promise.all(ks.filter(k=>/^munibin-v/.test(k)).map(k=>caches.delete(k)));}}catch(e){}" +
                "location.replace(next);})();</script></body></html>";
        sendBytes(output, 200, "text/html; charset=utf-8", html.getBytes(StandardCharsets.UTF_8), "no-store, no-cache, must-revalidate", request.method);
    }

    private boolean isBackendPath(String path) {
        return path.equals("/api") || path.startsWith("/api/") || path.startsWith("/manus-storage/");
    }

    private boolean isServiceWorkerPath(String path) {
        return path.equals("/sw.js") || path.equals("/api/pwa-worker.js") || path.matches("/api/pwa-worker-v[^/]+\\.js");
    }

    private String offlineSeedAssetPath(String path) {
        String clean = sanitizePath(path);
        return OFFLINE_ROOT + (clean.isEmpty() ? "/index" : "/" + clean);
    }

    private void serveFrontend(HttpRequest request, BufferedOutputStream output, String path) throws IOException {
        String clean = sanitizePath(path);
        String candidate = ASSET_ROOT + "/" + (clean.isEmpty() ? "index.html" : clean);
        if (!assetExists(candidate)) {
            String extension = fileExtension(clean);
            boolean acceptsHtml = request.headers.getOrDefault("accept", "").contains("text/html");
            if (extension.isEmpty() || acceptsHtml) candidate = ASSET_ROOT + "/index.html";
        }
        if (!assetExists(candidate)) {
            sendBytes(output, 404, "text/plain; charset=utf-8", "Not Found".getBytes(StandardCharsets.UTF_8), "no-store", request.method);
            return;
        }
        boolean immutable = !candidate.endsWith("/index.html")
                && !candidate.endsWith("/sw.js")
                && !candidate.endsWith("/asset-manifest.json")
                && !candidate.endsWith(".js")
                && !candidate.endsWith(".css");
        serveAsset(request, output, candidate, mimeType(candidate), immutable);
    }

    private void serveAsset(HttpRequest request, BufferedOutputStream output, String assetPath, String contentType, boolean immutable) throws IOException {
        byte[] data;
        try {
            data = readBundledAsset(assetPath);
        } catch (FileNotFoundException error) {
            sendBytes(output, 404, "text/plain; charset=utf-8", "Not Found".getBytes(StandardCharsets.UTF_8), "no-store", request.method);
            return;
        }
        sendBytes(output, 200, contentType, data, immutable ? "public, max-age=31536000, immutable" : "no-cache", request.method);
    }

    private void proxy(HttpRequest request, Uri localUri, BufferedOutputStream client) throws IOException {
        proxy(request, localUri, client, 0);
    }

    private void proxy(HttpRequest request, Uri localUri, BufferedOutputStream client, int attempt) throws IOException {
        String path = localUri.getEncodedPath() == null ? "/" : localUri.getEncodedPath();
        String remote = BACKEND_ORIGIN + path + (localUri.getEncodedQuery() == null ? "" : "?" + localUri.getEncodedQuery());
        HttpURLConnection upstream = null;
        boolean responseStarted = false;
        try {
            upstream = (HttpURLConnection) new URL(remote).openConnection();
            upstream.setConnectTimeout(15_000);
            upstream.setReadTimeout(120_000);
            upstream.setInstanceFollowRedirects(false);
            upstream.setRequestMethod(request.method);
            upstream.setUseCaches(false);
            upstream.setRequestProperty("Accept-Encoding", "identity");
            upstream.setRequestProperty("X-Munibin-Native-App", "android");

            Set<String> excluded = new HashSet<>(Arrays.asList(
                    "host", "origin", "referer", "cookie", "connection", "content-length", "transfer-encoding", "accept-encoding"
            ));
            for (Map.Entry<String, String> header : request.headers.entrySet()) {
                if (!excluded.contains(header.getKey())) upstream.setRequestProperty(header.getKey(), header.getValue());
            }
            String cookies = cookieJar.cookieHeader();
            if (!cookies.isEmpty()) upstream.setRequestProperty("Cookie", cookies);

            if (request.body.length > 0) {
                upstream.setDoOutput(true);
                upstream.setFixedLengthStreamingMode(request.body.length);
                try (OutputStream body = upstream.getOutputStream()) { body.write(request.body); }
            }

            int status = upstream.getResponseCode();
            cookieJar.capture(upstream.getHeaderFields());

            Map<String, List<String>> responseHeaders = upstream.getHeaderFields();
            List<String> outgoing = new ArrayList<>();
            Set<String> stripped = new HashSet<>(Arrays.asList(
                    "content-length", "content-encoding", "connection", "transfer-encoding", "set-cookie", "set-cookie2"
            ));
            for (Map.Entry<String, List<String>> entry : responseHeaders.entrySet()) {
                String name = entry.getKey();
                if (name == null || stripped.contains(name.toLowerCase(Locale.US))) continue;
                for (String value : entry.getValue()) outgoing.add(name + ": " + value);
            }

            String location = upstream.getHeaderField("Location");
            if (location != null) {
                try {
                    URL resolved = new URL(new URL(BACKEND_ORIGIN), location);
                    if ("munibin.com".equalsIgnoreCase(resolved.getHost())) {
                        Uri remoteUri = Uri.parse(resolved.toString());
                        location = localUriFor(remoteUri).toString();
                    }
                    removeHeader(outgoing, "Location");
                    outgoing.add("Location: " + location);
                } catch (Exception ignored) {}
            }

            boolean hasBody = !"HEAD".equals(request.method) && status != 204 && status != 304;
            InputStream responseStream = null;
            if (hasBody) {
                try { responseStream = upstream.getInputStream(); }
                catch (IOException ignored) { responseStream = upstream.getErrorStream(); }
            }

            // Stream with chunked encoding so AI/SSE and large audio responses are not buffered in RAM.
            writeStatusLine(client, status);
            responseStarted = true;
            for (String header : outgoing) writeAscii(client, header + "\r\n");
            if (hasBody) writeAscii(client, "Transfer-Encoding: chunked\r\n");
            else writeAscii(client, "Content-Length: 0\r\n");
            writeAscii(client, "Connection: close\r\n\r\n");
            client.flush();

            if (hasBody) {
                if (responseStream != null) {
                    try (InputStream in = new BufferedInputStream(responseStream)) {
                        byte[] buffer = new byte[32 * 1024];
                        int count;
                        while ((count = in.read(buffer)) >= 0) {
                            if (count == 0) continue;
                            writeAscii(client, Integer.toHexString(count) + "\r\n");
                            client.write(buffer, 0, count);
                            writeAscii(client, "\r\n");
                            client.flush();
                        }
                    }
                }
                // A chunked response must always be terminated, including redirects/errors
                // whose upstream connection exposes no body stream.
                writeAscii(client, "0\r\n\r\n");
            }
        } catch (IOException error) {
            Log.i(TAG, "Backend request failed for " + remote + " (attempt " + (attempt + 1) + "): " + error.getMessage());
            // Android can report a transient DNS/network failure while the WebView is
            // already online (especially immediately after launch/resume). iOS waits
            // for connectivity through URLSession; mirror that resilience here before
            // surfacing an error to tRPC. Only retry before any HTTP bytes were sent.
            if (!responseStarted && attempt < 2) {
                try { Thread.sleep(attempt == 0 ? 450L : 900L); } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                proxy(request, localUri, client, attempt + 1);
                return;
            }
            if (!responseStarted) {
                byte[] body = "Backend unavailable while offline".getBytes(StandardCharsets.UTF_8);
                sendBytes(client, 503, "text/plain; charset=utf-8", body, "no-store", request.method);
            }
        } finally {
            if (upstream != null) upstream.disconnect();
        }
    }

    /** Online fallback before the Service Worker controls the current page. */
    private void proxyExternalAudio(HttpRequest request, Uri localUri, BufferedOutputStream client) throws IOException {
        String source = localUri.getQueryParameter("source");
        URL remote;
        try {
            remote = source == null ? null : new URL(source);
            if (remote == null || !"https".equalsIgnoreCase(remote.getProtocol())) {
                throw new IllegalArgumentException("Only HTTPS audio is allowed");
            }
        } catch (Exception error) {
            sendBytes(client, 400, "text/plain; charset=utf-8", "Invalid audio source".getBytes(StandardCharsets.UTF_8), "no-store", request.method);
            return;
        }

        HttpURLConnection upstream = null;
        try {
            upstream = (HttpURLConnection) remote.openConnection();
            upstream.setConnectTimeout(30_000);
            // Device sleep can suspend WebView consumption long enough to trip a
            // short read timeout. Keep the native audio stream alive and let the
            // web retry layer resume cleanly after wake if the network still drops.
            upstream.setReadTimeout(600_000);
            upstream.setInstanceFollowRedirects(true);
            upstream.setRequestMethod(request.method);
            upstream.setUseCaches(false);
            upstream.setRequestProperty("Accept-Encoding", "identity");

            for (String name : Arrays.asList("range", "if-range", "if-none-match", "if-modified-since", "accept", "user-agent")) {
                String value = request.headers.get(name);
                if (value != null && !value.isEmpty()) upstream.setRequestProperty(name, value);
            }

            int status = upstream.getResponseCode();
            List<String> outgoing = new ArrayList<>();
            Set<String> stripped = new HashSet<>(Arrays.asList(
                    "content-length", "content-encoding", "connection", "transfer-encoding", "set-cookie", "set-cookie2"
            ));
            for (Map.Entry<String, List<String>> entry : upstream.getHeaderFields().entrySet()) {
                String name = entry.getKey();
                if (name == null || stripped.contains(name.toLowerCase(Locale.US))) continue;
                for (String value : entry.getValue()) outgoing.add(name + ": " + value);
            }

            boolean hasBody = !"HEAD".equals(request.method) && status != 204 && status != 304;
            InputStream responseStream = null;
            if (hasBody) {
                try { responseStream = upstream.getInputStream(); }
                catch (IOException ignored) { responseStream = upstream.getErrorStream(); }
            }
            long responseLength = -1L;
            String responseLengthHeader = upstream.getHeaderField("Content-Length");
            if (responseLengthHeader != null) {
                try { responseLength = Long.parseLong(responseLengthHeader.trim()); }
                catch (NumberFormatException ignored) { responseLength = -1L; }
            }
            boolean fixedLengthBody = hasBody && responseStream != null && responseLength >= 0L;

            writeStatusLine(client, status);
            for (String header : outgoing) writeAscii(client, header + "\r\n");
            if (fixedLengthBody) writeAscii(client, "Content-Length: " + responseLength + "\r\n");
            else if (hasBody) writeAscii(client, "Transfer-Encoding: chunked\r\n");
            else writeAscii(client, "Content-Length: 0\r\n");
            writeAscii(client, "Connection: close\r\n\r\n");
            client.flush();

            if (hasBody) {
                if (responseStream != null) {
                    try (InputStream in = new BufferedInputStream(responseStream)) {
                        byte[] buffer = new byte[32 * 1024];
                        int count;
                        while ((count = in.read(buffer)) >= 0) {
                            if (count == 0) continue;
                            if (fixedLengthBody) {
                                client.write(buffer, 0, count);
                            } else {
                                writeAscii(client, Integer.toHexString(count) + "\r\n");
                                client.write(buffer, 0, count);
                                writeAscii(client, "\r\n");
                            }
                            client.flush();
                        }
                    }
                }
                if (!fixedLengthBody) writeAscii(client, "0\r\n\r\n");
            }
        } catch (IOException error) {
            Log.i(TAG, "Reciter audio unavailable for " + remote + ": " + error.getMessage());
            sendBytes(client, 502, "text/plain; charset=utf-8", "Audio unavailable".getBytes(StandardCharsets.UTF_8), "no-store", request.method);
        } finally {
            if (upstream != null) upstream.disconnect();
        }
    }

    private void sendBytes(BufferedOutputStream output, int status, String contentType, byte[] data, String cacheControl, String method) throws IOException {
        writeStatusLine(output, status);
        writeAscii(output, "Content-Type: " + contentType + "\r\n");
        writeAscii(output, "Content-Length: " + data.length + "\r\n");
        writeAscii(output, "Cache-Control: " + cacheControl + "\r\n");
        writeAscii(output, "Connection: close\r\n\r\n");
        if (!"HEAD".equals(method)) output.write(data);
    }

    private void writeStatusLine(OutputStream output, int status) throws IOException {
        writeAscii(output, "HTTP/1.1 " + status + " " + reason(status) + "\r\n");
    }

    private static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 201: return "Created";
            case 202: return "Accepted";
            case 204: return "No Content";
            case 206: return "Partial Content";
            case 301: return "Moved Permanently";
            case 302: return "Found";
            case 303: return "See Other";
            case 304: return "Not Modified";
            case 307: return "Temporary Redirect";
            case 308: return "Permanent Redirect";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 413: return "Payload Too Large";
            case 429: return "Too Many Requests";
            case 500: return "Internal Server Error";
            case 502: return "Bad Gateway";
            case 503: return "Service Unavailable";
            default: return "HTTP";
        }
    }

    private static String readAsciiLine(InputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int previous = -1;
        while (true) {
            int current = input.read();
            if (current < 0) {
                if (line.size() == 0) return null;
                break;
            }
            if (previous == '\r' && current == '\n') {
                byte[] raw = line.toByteArray();
                int length = Math.max(0, raw.length - 1);
                return new String(raw, 0, length, StandardCharsets.ISO_8859_1);
            }
            line.write(current);
            previous = current;
            if (line.size() > 64 * 1024) throw new IOException("Header line too large");
        }
        return line.toString(StandardCharsets.ISO_8859_1.name());
    }

    private static byte[] readChunkedBody(BufferedInputStream input) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        while (true) {
            String sizeLine = readAsciiLine(input);
            if (sizeLine == null) throw new EOFException("Unexpected end of chunked request");
            String hex = sizeLine.split(";", 2)[0].trim();
            int size = Integer.parseInt(hex, 16);
            if (size == 0) {
                while (true) {
                    String trailer = readAsciiLine(input);
                    if (trailer == null || trailer.isEmpty()) break;
                }
                break;
            }
            if (body.size() + size > MAX_REQUEST_BODY) throw new IOException("Request body too large");
            body.write(readExactly(input, size));
            String terminator = readAsciiLine(input);
            if (terminator == null) throw new EOFException("Missing chunk terminator");
        }
        return body.toByteArray();
    }

    private static byte[] readExactly(InputStream input, int length) throws IOException {
        byte[] data = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = input.read(data, offset, length - offset);
            if (count < 0) throw new EOFException("Unexpected end of request");
            offset += count;
        }
        return data;
    }

    private static byte[] readAll(InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[32 * 1024];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            if (count == 0) continue;
            if (output.size() + count > maxBytes) throw new IOException("Asset too large");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private boolean assetExists(String path) {
        synchronized (decodedAssetCacheLock) {
            if (decodedAssetCache.containsKey(path)) return true;
        }
        try (InputStream ignored = assets.open(path, AssetManager.ACCESS_STREAMING)) { return true; }
        catch (IOException missing) {
            try (InputStream ignored = assets.open(path + ".gz", AssetManager.ACCESS_STREAMING)) { return true; }
            catch (IOException ignored) { return false; }
        }
    }

    // v360: store large offline text compressed, but keep the original URLs,
    // decoded bytes, MIME types, lengths and cache behavior. Native streaming
    // inflation needs no newer WebView APIs and never writes an expanded copy.
    private InputStream openBundledAsset(String path) throws IOException {
        try {
            return assets.open(path, AssetManager.ACCESS_STREAMING);
        } catch (IOException missing) {
            InputStream compressed = assets.open(path + ".gz", AssetManager.ACCESS_STREAMING);
            try {
                return new GZIPInputStream(compressed, 32 * 1024);
            } catch (IOException invalidGzip) {
                compressed.close();
                throw invalidGzip;
            }
        }
    }

    private byte[] readBundledAsset(String path) throws IOException {
        synchronized (decodedAssetCacheLock) {
            byte[] cached = decodedAssetCache.get(path);
            if (cached != null) return cached;
        }
        final byte[] data;
        final boolean compressed;
        // Never hold the cache lock during IO/inflate: another connection can
        // still obtain its tiny hot Quran page while a large tafsir is decoded.
        try (InputStream input = openBundledAsset(path)) {
            compressed = input instanceof GZIPInputStream;
            data = readAll(input, 128 * 1024 * 1024);
        }
        if (compressed && data.length <= DECODED_ASSET_CACHE_FILE_BYTES) {
            synchronized (decodedAssetCacheLock) {
                // An in-flight read must not repopulate the cache after stop().
                if (!running) return data;
                byte[] previous = decodedAssetCache.remove(path);
                if (previous != null) decodedAssetCacheBytes -= previous.length;
                while (decodedAssetCacheBytes + data.length > DECODED_ASSET_CACHE_BYTES
                        || decodedAssetCache.size() >= DECODED_ASSET_CACHE_ENTRIES) {
                    String oldest = decodedAssetCache.keySet().iterator().next();
                    decodedAssetCacheBytes -= decodedAssetCache.remove(oldest).length;
                }
                decodedAssetCache.put(path, data);
                decodedAssetCacheBytes += data.length;
            }
        }
        return data;
    }

    private static String sanitizePath(String path) {
        try { path = URLDecoder.decode(path, "UTF-8"); } catch (Exception ignored) {}
        StringBuilder clean = new StringBuilder();
        for (String part : path.split("/")) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) continue;
            if (clean.length() > 0) clean.append('/');
            clean.append(part);
        }
        return clean.toString();
    }

    private static String fileExtension(String path) {
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        return dot > slash ? path.substring(dot + 1) : "";
    }

    private static String mimeType(String path) {
        String ext = fileExtension(path).toLowerCase(Locale.US);
        if ("js".equals(ext) || "mjs".equals(ext)) return "text/javascript; charset=utf-8";
        if ("json".equals(ext) || "map".equals(ext)) return "application/json; charset=utf-8";
        if ("css".equals(ext)) return "text/css; charset=utf-8";
        if ("woff2".equals(ext)) return "font/woff2";
        if ("woff".equals(ext)) return "font/woff";
        if ("ttf".equals(ext)) return "font/ttf";
        if ("svg".equals(ext)) return "image/svg+xml";
        if ("png".equals(ext)) return "image/png";
        if ("jpg".equals(ext) || "jpeg".equals(ext)) return "image/jpeg";
        if ("webp".equals(ext)) return "image/webp";
        if ("ico".equals(ext)) return "image/x-icon";
        if ("mp3".equals(ext)) return "audio/mpeg";
        if ("m4a".equals(ext)) return "audio/mp4";
        if ("txt".equals(ext)) return "text/plain; charset=utf-8";
        if ("html".equals(ext) || ext.isEmpty()) return "text/html; charset=utf-8";
        String guessed = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return guessed == null ? "application/octet-stream" : guessed;
    }

    private static int parseInt(String value, int fallback) {
        try { return value == null ? fallback : Integer.parseInt(value); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private static void writeAscii(OutputStream output, String value) throws IOException {
        output.write(value.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static void removeHeader(List<String> headers, String wanted) {
        String prefix = wanted.toLowerCase(Locale.US) + ":";
        for (int index = headers.size() - 1; index >= 0; index--) {
            if (headers.get(index).toLowerCase(Locale.US).startsWith(prefix)) headers.remove(index);
        }
    }

    private static final class HttpRequest {
        final String method;
        final String target;
        final Map<String, String> headers;
        final byte[] body;

        HttpRequest(String method, String target, Map<String, String> headers, byte[] body) {
            this.method = method;
            this.target = target;
            this.headers = headers;
            this.body = body;
        }
    }

    /** Minimal persistent cookie jar for the single trusted backend origin. */
    private static final class BackendCookieJar {
        private static final String PREFS = "munibin_backend_session";
        private static final String KEY = "cookies";
        private final SharedPreferences preferences;
        private final Map<String, String> cookies = new HashMap<>();

        BackendCookieJar(Context context) {
            preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            try {
                JSONObject stored = new JSONObject(preferences.getString(KEY, "{}"));

                java.util.Iterator<String> keys = stored.keys();
                while (keys.hasNext()) {
                    String name = keys.next();
                    cookies.put(name, stored.optString(name, ""));
                }
            } catch (Exception ignored) {}
        }

        synchronized String cookieHeader() {
            List<String> values = new ArrayList<>();
            for (Map.Entry<String, String> cookie : cookies.entrySet()) {
                if (!cookie.getValue().isEmpty()) values.add(cookie.getKey() + "=" + cookie.getValue());
            }
            StringBuilder joined = new StringBuilder();
            for (String value : values) {
                if (joined.length() > 0) joined.append("; ");
                joined.append(value);
            }
            return joined.toString();
        }

        synchronized void capture(Map<String, List<String>> headers) {
            boolean changed = false;
            for (Map.Entry<String, List<String>> header : headers.entrySet()) {
                if (header.getKey() == null || !"set-cookie".equalsIgnoreCase(header.getKey())) continue;
                for (String raw : header.getValue()) {
                    try {
                        for (HttpCookie cookie : HttpCookie.parse(raw)) {
                            if (cookie.getMaxAge() == 0 || cookie.getValue() == null || cookie.getValue().isEmpty()) cookies.remove(cookie.getName());
                            else cookies.put(cookie.getName(), cookie.getValue());
                            changed = true;
                        }
                    } catch (IllegalArgumentException ignored) {}
                }
            }
            if (changed) persist();
        }

        private void persist() {
            JSONObject value = new JSONObject(cookies);
            preferences.edit().putString(KEY, value.toString()).apply();
        }
    }
}
