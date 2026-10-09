package com.motoonai.twa;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.DownloadManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.media.MediaMetadata;
import android.media.RingtoneManager;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.DownloadListener;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.firebase.messaging.FirebaseMessaging;

import org.json.JSONObject;

import java.io.IOException;

/**
 * Native host for the packaged Munibin frontend.
 *
 * The previous project used Chrome/TWA and therefore depended on munibin.com to
 * render the application. This Activity owns the WebView and loads a loopback
 * origin served from APK assets; only backend-only requests leave the device.
 */
public class LauncherActivity extends Activity {
    private static final int PRAYER_NOTIFICATION_PERMISSION_REQUEST = 702;
    private static final int LOCATION_PERMISSION_REQUEST = 703;
    private static final int FILE_CHOOSER_REQUEST = 704;
    private static final int CHAT_NOTIFICATION_PERMISSION_REQUEST = 705;
    private static final int LIVE_PRAYER_NOTIFICATION_PERMISSION_REQUEST = 706;
    private static final int PRAYER_NOTCH_NOTIFICATION_PERMISSION_REQUEST = 707;
    private static final int IQAMA_SOUND_REQUEST = 708;
    private static final String CHAT_NOTIFICATION_CHANNEL_ID = "munibin_messages";
    private static final String CHAT_NOTIFICATION_GROUP = "munibin_support_messages";
    private static final long LAUNCH_MIN_VISIBLE_MS = 1_050L;
    private static final int LAUNCH_CREAM = Color.rgb(253, 248, 240);
    private static final int MUNIBIN_BROWN = Color.rgb(146, 64, 14);
    private static final int MUNIBIN_GOLD = Color.rgb(217, 119, 6);
    // Exact opaque brown used by the drawing inside munibin_launch_mark.png: #9E4005.
    private static final int LAUNCH_MARK_BROWN = Color.rgb(158, 64, 5);
    private static final String NATIVE_SHELL_RESET_VERSION = "v423-prayer-format-iqama-1";
    private static final String NATIVE_SHELL_RESET_PREFS = "munibin_native_shell";
    private static final String FRONTEND_HTTP_CACHE_RESET_VERSION = "v423-prayer-format-iqama-1";
    private static final String FRONTEND_HTTP_CACHE_RESET_PREFS = "munibin_frontend_http_cache";

    private WebView webView;
    private FrameLayout rootContainer;
    private NativeAppServer nativeServer;
    private ValueCallback<Uri[]> fileChooserCallback;
    private GeolocationPermissions.Callback pendingGeoCallback;
    private String pendingGeoOrigin;
    private boolean notificationPermissionRequested;
    private boolean prayerReminderPermissionPending;
    private boolean prayerNotchPermissionPending;
    private boolean activityResumed;
    private String lastLivePrayerStatus = "";
    private BroadcastReceiver prayerNotchStatusReceiver;
    private boolean exactAlarmSettingsRequested;
    private MediaSession mediaSession;
    private NativeQuranInteractionBridge nativeQuranBridge;
    private PhoneTabletOrientationController orientationController;
    private boolean buttonNavigationMode;
    private boolean navigationModeResolved;
    private boolean showingLaunchChrome = true;
    private Uri pendingSocialAuthCallback;

    // Native launch animation. It lives above the WebView so startup is smooth
    // even when the packaged frontend is still being prepared offline.
    private FrameLayout launchOverlay;
    private View launchGlow;
    private ImageView launchLogo;
    private TextView launchTitle;
    private View launchAccent;
    private long launchShownAtMs;
    private boolean launchDismissRequested;
    private BroadcastReceiver supportFcmTokenReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        orientationController = new PhoneTabletOrientationController(this);
        chatNotificationManager();
        // Let Android itself reserve the system-bar area on 3-button / 2-button
        // navigation. In gesture mode we stay edge-to-edge. This avoids reserving
        // the navigation-bar height twice (native padding + web footer safe area),
        // which previously left a large empty strip below the Munibin footer.
        int systemNavigationMode = readSystemNavigationMode();
        if (systemNavigationMode >= 0) {
            buttonNavigationMode = systemNavigationMode != 2; // 2 = gestural
            navigationModeResolved = true;
        } else {
            // Unknown OEM mode: begin edge-to-edge and resolve from the first insets.
            buttonNavigationMode = false;
            navigationModeResolved = false;
        }
        WindowCompat.setDecorFitsSystemWindows(getWindow(), buttonNavigationMode);
        mediaSession = new MediaSession(this, "MunibinQuran");
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setActive(false);
        // Match the first frame of the launch animation. The regular brown system
        // bars are restored as the animation reveals the application.
        getWindow().setStatusBarColor(LAUNCH_CREAM);
        getWindow().setNavigationBarColor(LAUNCH_CREAM);
        int launchSystemUi = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            launchSystemUi |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        getWindow().getDecorView().setSystemUiVisibility(launchSystemUi);
        keepSystemNavigationVisible();

        FrameLayout container = new FrameLayout(this);
        rootContainer = container;
        // On button navigation, decorFitsSystemWindows=true already makes the app's
        // content area end immediately above Android's navigation bar. Do NOT add the
        // navigation-bar inset again as root padding. That was the source of the large
        // blank strip and the resulting Quran/footer shrinkage.
        //
        // On gesture navigation, decorFitsSystemWindows=false keeps the app edge-to-edge;
        // only the top status/cutout inset (and the IME while visible) is applied here.
        container.setBackgroundColor(LAUNCH_CREAM);
        setContentView(container, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ViewCompat.setOnApplyWindowInsetsListener(container, (view, insets) -> {
            // Use the stable (ignoring-visibility) top inset. Some Android 15/16 OEMs can
            // transiently report a zero visible status-bar inset during launch/system-bar
            // animation even though a tall camera cutout is still physically unsafe.
            Insets topSafe = insets.getInsetsIgnoringVisibility(
                    WindowInsetsCompat.Type.statusBars() | WindowInsetsCompat.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());

            // Fallback for OEMs that do not expose config_navBarInteractionMode. Resolve
            // once only; after switching decorFitsSystemWindows(true), fitted insets may be
            // consumed by the framework and must not be reinterpreted as gesture mode.
            if (!navigationModeResolved) {
                Insets nav = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars());
                Insets tappable = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.tappableElement());
                buttonNavigationMode = nav.bottom > 0 && tappable.bottom > 0;
                navigationModeResolved = true;
                WindowCompat.setDecorFitsSystemWindows(getWindow(), buttonNavigationMode);
                applyNavigationBarAppearance();
                ViewCompat.requestApplyInsets(view);
            }

            final int keyboardBottom = insets.isVisible(WindowInsetsCompat.Type.ime()) ? ime.bottom : 0;
            final boolean buttons = buttonNavigationMode;
            final int safeLeft = topSafe.left;
            final int safeTop = topSafe.top;
            final int safeRight = topSafe.right;

            // Do not blindly add status-bar padding on every phone. With
            // decorFitsSystemWindows=true Android may already have physically moved this
            // content view below the status bar. Instead, after layout, compare the view's
            // actual screen Y with the device's real status/cutout safe top and add only the
            // missing difference. Correct phones therefore remain pixel-for-pixel unchanged,
            // while OEMs that leave content beside/under a tall camera cutout get exactly the
            // additional top space they need. Bottom handling stays untouched.
            view.post(() -> {
                int[] location = new int[2];
                view.getLocationOnScreen(location);
                int missingTop = Math.max(0, safeTop - Math.max(0, location[1]));
                int leftPadding = buttons ? 0 : safeLeft;
                int rightPadding = buttons ? 0 : safeRight;
                int bottomPadding = buttons ? 0 : keyboardBottom;
                if (view.getPaddingLeft() != leftPadding
                        || view.getPaddingTop() != missingTop
                        || view.getPaddingRight() != rightPadding
                        || view.getPaddingBottom() != bottomPadding) {
                    view.setPadding(leftPadding, missingTop, rightPadding, bottomPadding);
                }
            });

            keepSystemNavigationVisible();
            return insets;
        });
        ViewCompat.requestApplyInsets(container);

        nativeServer = new NativeAppServer(getApplicationContext());
        try {
            nativeServer.start();
        } catch (IOException error) {
            Toast.makeText(this, "تعذر تشغيل واجهة منيبين المحلية", Toast.LENGTH_LONG).show();
            return;
        }

        webView = new WebView(this);
        webView.setBackgroundColor(LAUNCH_CREAM);
        container.addView(webView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        configureWebView(webView);
        // This build changes already-installed JavaScript bundles. Older native
        // releases served JS/CSS as one-year immutable resources, so WebView can
        // otherwise reuse the previous bytes even though the APK contains a newer
        // bundle. Clear only the HTTP resource cache once; localStorage, IndexedDB,
        // cookies and downloaded Quran/Mutoon audio are not removed.
        android.content.SharedPreferences frontendCachePrefs = getSharedPreferences(FRONTEND_HTTP_CACHE_RESET_PREFS, MODE_PRIVATE);
        if (!FRONTEND_HTTP_CACHE_RESET_VERSION.equals(frontendCachePrefs.getString("version", ""))) {
            webView.clearCache(true);
            frontendCachePrefs.edit().putString("version", FRONTEND_HTTP_CACHE_RESET_VERSION).apply();
        }
        showLaunchAnimation(container);
        registerSupportFcmTokenReceiver();
        registerPrayerNotchStatusReceiver();
        loadIntent(getIntent());

        // Preserve the existing widget/notification refresh behavior.
        webView.postDelayed(this::refreshNativePrayerState, 2_500L);
        webView.postDelayed(this::refreshNativePrayerState, 8_000L);
        webView.postDelayed(this::refreshNativePrayerState, 15_000L);
    }

    /**
     * Android framework resource used by AOSP and the major OEMs:
     * 0 = 3-button, 1 = 2-button, 2 = gestural. Returns -1 when an OEM does not expose it.
     * This is preferable to inferring the mode after the framework has already consumed
     * fitted system-bar insets.
     */
    private int readSystemNavigationMode() {
        int resourceId = getResources().getIdentifier(
                "config_navBarInteractionMode", "integer", "android");
        if (resourceId == 0) return -1;
        try {
            return getResources().getInteger(resourceId);
        } catch (Exception ignored) {
            return -1;
        }
    }

    /**
     * Android users can choose gesture navigation or button navigation on the same device.
     * Never put the app into immersive navigation mode: on button navigation that would
     * force the user to swipe before Back/Home/Recents become available. Showing the
     * navigation bars is also safe in gesture mode; Android only exposes the gesture area.
     */
    private void keepSystemNavigationVisible() {
        View decor = getWindow().getDecorView();
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getWindow(), decor);
        if (controller != null) {
            // Explicitly leave immersive/transient-bar behavior off and request the
            // navigation controls to be shown. On gesture navigation this only keeps
            // the gesture navigation area available; it does not create a 3-button bar.
            controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_DEFAULT);
            controller.show(WindowInsetsCompat.Type.navigationBars());
        }
    }

    /** Keep launch chrome cream, use a solid app-colored bar for button navigation, and
     * a transparent edge-to-edge gesture area for gesture navigation. */
    private void applyNavigationBarAppearance() {
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(
                getWindow(), getWindow().getDecorView());

        if (showingLaunchChrome) {
            getWindow().setNavigationBarColor(LAUNCH_CREAM);
            if (controller != null) controller.setAppearanceLightNavigationBars(true);
        } else if (buttonNavigationMode) {
            getWindow().setNavigationBarColor(MUNIBIN_BROWN);
            if (controller != null) controller.setAppearanceLightNavigationBars(false);
        } else {
            getWindow().setNavigationBarColor(Color.TRANSPARENT);
            if (controller != null) controller.setAppearanceLightNavigationBars(false);
        }

        keepSystemNavigationVisible();
    }

    private void configureWebView(WebView view) {
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setUserAgentString(settings.getUserAgentString() + " MunibinNative/Android");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) settings.setSafeBrowsingEnabled(true);

        nativeQuranBridge = new NativeQuranInteractionBridge(this, view);
        view.addJavascriptInterface(new AndroidBridge(), "MunibinAndroid");
        view.setWebViewClient(new MunibinWebViewClient());
        view.setWebChromeClient(new MunibinChromeClient());
        view.setDownloadListener(createDownloadListener());
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable roundedDrawable(int color, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    /**
     * Warm, restrained launch motion: the mark grows out of the system splash,
     * the Arabic wordmark settles in, then the whole layer dissolves into the app.
     * No network or WebView content is required for this animation.
     */
    private void showLaunchAnimation(FrameLayout container) {
        launchShownAtMs = SystemClock.uptimeMillis();
        launchDismissRequested = false;

        launchOverlay = new FrameLayout(this);
        launchOverlay.setClickable(true);
        launchOverlay.setBackgroundColor(LAUNCH_CREAM);
        container.addView(launchOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        launchGlow = new View(this);
        GradientDrawable glowBackground = new GradientDrawable();
        glowBackground.setShape(GradientDrawable.OVAL);
        glowBackground.setColor(Color.argb(22, 217, 119, 6));
        launchGlow.setBackground(glowBackground);
        FrameLayout.LayoutParams glowParams = new FrameLayout.LayoutParams(dp(210), dp(210), Gravity.CENTER);
        glowParams.topMargin = -dp(24);
        launchOverlay.addView(launchGlow, glowParams);

        launchLogo = new ImageView(this);
        launchLogo.setImageResource(R.drawable.munibin_launch_mark);
        launchLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        FrameLayout.LayoutParams logoParams = new FrameLayout.LayoutParams(dp(118), dp(130), Gravity.CENTER);
        logoParams.topMargin = -dp(50);
        launchOverlay.addView(launchLogo, logoParams);

        launchTitle = new TextView(this);
        launchTitle.setText("منيبين");
        launchTitle.setTextColor(MUNIBIN_BROWN);
        launchTitle.setTextSize(28);
        launchTitle.setGravity(Gravity.CENTER);
        launchTitle.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        launchTitle.setIncludeFontPadding(false);
        FrameLayout.LayoutParams titleParams = new FrameLayout.LayoutParams(dp(180), dp(44), Gravity.CENTER);
        titleParams.topMargin = dp(12);
        launchOverlay.addView(launchTitle, titleParams);

        launchAccent = new View(this);
        launchAccent.setBackground(roundedDrawable(LAUNCH_MARK_BROWN, 2));
        FrameLayout.LayoutParams accentParams = new FrameLayout.LayoutParams(dp(38), dp(3), Gravity.CENTER);
        accentParams.topMargin = dp(50);
        launchOverlay.addView(launchAccent, accentParams);

        // The system splash already shows the mark. Begin from a slightly smaller
        // matching pose so there is no abrupt second splash when our Activity appears.
        launchLogo.setScaleX(0.80f);
        launchLogo.setScaleY(0.80f);
        launchGlow.setScaleX(0.62f);
        launchGlow.setScaleY(0.62f);
        launchGlow.setAlpha(0f);
        launchTitle.setAlpha(0f);
        launchTitle.setTranslationY(dp(8));
        launchAccent.setScaleX(0f);
        launchAccent.setAlpha(0f);

        launchOverlay.post(() -> {
            if (launchOverlay == null) return;
            launchGlow.animate()
                    .alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(620L)
                    .setInterpolator(new DecelerateInterpolator(1.8f))
                    .start();
            launchLogo.animate()
                    .scaleX(1f).scaleY(1f)
                    .setStartDelay(45L)
                    .setDuration(650L)
                    .setInterpolator(new OvershootInterpolator(0.85f))
                    .start();
            launchTitle.animate()
                    .alpha(1f).translationY(0f)
                    .setStartDelay(285L)
                    .setDuration(330L)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
            launchAccent.animate()
                    .alpha(1f).scaleX(1f)
                    .setStartDelay(440L)
                    .setDuration(300L)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        });
    }

    private void dismissLaunchAnimationWhenReady() {
        if (launchOverlay == null || launchDismissRequested) return;
        launchDismissRequested = true;
        long elapsed = SystemClock.uptimeMillis() - launchShownAtMs;
        long delay = Math.max(0L, LAUNCH_MIN_VISIBLE_MS - elapsed);
        launchOverlay.postDelayed(this::finishLaunchAnimation, delay);
    }

    private void finishLaunchAnimation() {
        if (launchOverlay == null) return;

        // Restore the normal app chrome before the cream layer disappears.
        getWindow().setStatusBarColor(MUNIBIN_BROWN);
        showingLaunchChrome = false;
        // Brown app chrome uses light status-bar icons. Navigation appearance is chosen
        // dynamically: solid for button navigation, transparent for gesture navigation.
        getWindow().getDecorView().setSystemUiVisibility(0);
        applyNavigationBarAppearance();

        if (launchTitle != null) {
            launchTitle.animate().alpha(0f).translationY(-dp(6)).setDuration(180L).start();
        }
        if (launchAccent != null) {
            launchAccent.animate().alpha(0f).scaleX(0.55f).setDuration(160L).start();
        }
        if (launchLogo != null) {
            launchLogo.animate().alpha(0f).scaleX(1.075f).scaleY(1.075f)
                    .setDuration(260L).setInterpolator(new DecelerateInterpolator()).start();
        }
        if (launchGlow != null) {
            launchGlow.animate().alpha(0f).scaleX(1.18f).scaleY(1.18f)
                    .setDuration(290L).setInterpolator(new DecelerateInterpolator()).start();
        }

        launchOverlay.animate()
                .alpha(0f)
                .setStartDelay(70L)
                .setDuration(280L)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> {
                    if (launchOverlay != null) {
                        ViewGroup parent = (ViewGroup) launchOverlay.getParent();
                        if (parent != null) parent.removeView(launchOverlay);
                    }
                    launchOverlay = null;
                    launchGlow = null;
                    launchLogo = null;
                    launchTitle = null;
                    launchAccent = null;
                })
                .start();
    }

    private Uri incomingUriFromIntent(Intent intent) {
        if (intent == null) return null;
        Uri direct = intent.getData();
        if (direct != null) return direct;
        String route = intent.getStringExtra("route");
        if (route != null && route.startsWith("/") && !route.startsWith("//")) {
            return Uri.parse("https://munibin.com" + route);
        }
        String threadId = intent.getStringExtra("threadId");
        String type = intent.getStringExtra("type");
        if ("support_chat".equals(type) && threadId != null && !threadId.isEmpty()) {
            return Uri.parse("https://munibin.com/feedback?thread=" + Uri.encode(threadId));
        }
        return null;
    }

    private void loadIntent(Intent intent) {
        Uri incoming = incomingUriFromIntent(intent);
        if (incoming != null && "munibin".equalsIgnoreCase(incoming.getScheme()) && "auth".equalsIgnoreCase(incoming.getHost())) {
            pendingSocialAuthCallback = incoming;
            Uri localRoot = nativeServer.localUriFor(Uri.parse("https://munibin.com/"));
            if (webView != null && webView.getUrl() != null) {
                dispatchSocialAuthCallback(incoming);
                pendingSocialAuthCallback = null;
                return;
            }
            incoming = Uri.parse("https://munibin.com/");
        }
        Uri target = incoming == null ? Uri.parse("https://munibin.com/") : incoming;
        Uri local = nativeServer.localUriFor(target);

        // v145-v147 could remain visually frozen because an older Service Worker served
        // its cached index.html before the newly bundled index ever got a chance to run.
        // Load a same-origin /api bootstrap exactly once for this native release. Old
        // workers pass /api through, so the native server can unregister them without
        // deleting IndexedDB/localStorage/downloaded Quran audio.
        android.content.SharedPreferences prefs = getSharedPreferences(NATIVE_SHELL_RESET_PREFS, MODE_PRIVATE);
        String resetDone = prefs.getString("version", "");
        if (!NATIVE_SHELL_RESET_VERSION.equals(resetDone)) {
            prefs.edit().putString("version", NATIVE_SHELL_RESET_VERSION).apply();
            Uri reset = Uri.parse(nativeServer.getBaseUrl()).buildUpon()
                    .appendPath("api")
                    .appendPath("__native-shell-reset-v196")
                    .appendQueryParameter("next", local.toString())
                    .build();
            webView.loadUrl(reset.toString());
            return;
        }
        webView.loadUrl(local.toString());
    }

    private boolean navigateWarmAppLinkWithoutReload(Uri incoming) {
        if (webView == null || webView.getUrl() == null || incoming == null) return false;
        String scheme = incoming.getScheme() == null ? "" : incoming.getScheme().toLowerCase();
        String host = incoming.getHost() == null ? "" : incoming.getHost().toLowerCase();
        if (!("http".equals(scheme) || "https".equals(scheme))) return false;
        if (!("munibin.com".equals(host) || host.endsWith(".munibin.com"))) return false;

        String route = incoming.getEncodedPath();
        if (route == null || route.isEmpty()) route = "/";
        if (incoming.getEncodedQuery() != null && !incoming.getEncodedQuery().isEmpty()) {
            route += "?" + incoming.getEncodedQuery();
        }
        if (incoming.getEncodedFragment() != null && !incoming.getEncodedFragment().isEmpty()) {
            route += "#" + incoming.getEncodedFragment();
        }
        final String routeLiteral = JSONObject.quote(route);
        final String js = "(function(){try{var p=" + routeLiteral + ";var c=location.pathname+location.search+location.hash;if(c!==p){history.pushState({},'',p);window.dispatchEvent(new PopStateEvent('popstate',{state:history.state}));}return true;}catch(e){return false;}})();";
        webView.evaluateJavascript(js, null);
        return true;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        Uri incoming = incomingUriFromIntent(intent);
        if (incoming != null && "munibin".equalsIgnoreCase(incoming.getScheme()) && "auth".equalsIgnoreCase(incoming.getHost())) {
            pendingSocialAuthCallback = incoming;
            if (webView != null && webView.getUrl() != null) {
                dispatchSocialAuthCallback(incoming);
                pendingSocialAuthCallback = null;
            }
            return;
        }
        // A widget/universal-link activation while the app is already alive must not
        // reload the packaged WebView: reloads destroy the HTMLAudioElement and stop
        // Quran/Mutoon playback. Route the existing SPA in place instead.
        if (navigateWarmAppLinkWithoutReload(incoming)) return;
        if (webView != null) loadIntent(intent);
    }

    private void dispatchSocialAuthCallback(Uri uri) {
        if (webView == null || uri == null) return;
        try {
            JSONObject detail = new JSONObject();
            String code = uri.getQueryParameter("code");
            String error = uri.getQueryParameter("error");
            if (code != null && !code.isEmpty()) detail.put("code", code);
            if (error != null && !error.isEmpty()) detail.put("error", error);
            String js = "window.dispatchEvent(new CustomEvent('munibin-native-social-auth',{detail:" + detail.toString() + "}));";
            runOnUiThread(() -> webView.evaluateJavascript(js, null));
        } catch (Exception ignored) {}
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (orientationController != null) orientationController.onResume();
        activityResumed = true;
        keepSystemNavigationVisible();
        if (rootContainer != null) ViewCompat.requestApplyInsets(rootContainer);
        PrayerLocalNotificationScheduler.rescheduleStored(getApplicationContext());
        PrayerLiveNotificationController.reconcile(getApplicationContext());
        PrayerNotchController.startFromUser(getApplicationContext());
        restorePrayerWidgetSnapshotFromWebStorage();
        emitLivePrayerNotificationStatus();
        emitIqamaNotificationSoundStatus();
        if (webView != null) {
            webView.evaluateJavascript(
                    "try{window.dispatchEvent(new Event('munibin-native-resume'));}catch(e){}",
                    null
            );
        }
    }

    @Override
    protected void onPause() {
        activityResumed = false;
        if (orientationController != null) orientationController.onPause();
        super.onPause();
    }

    @Override
    public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (orientationController != null) orientationController.onConfigurationChanged();
    }

    @Override
    protected void onStop() {
        // Recitation is an explicit foreground action. Never leave the microphone
        // or a pending recognizer restart running after the app is backgrounded.
        if (nativeQuranBridge != null) nativeQuranBridge.stopSpeech();
        super.onStop();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            // Some OEMs re-apply transient system-bar state as a window regains focus.
            // Reassert the user's navigation mode without changing gesture devices.
            keepSystemNavigationVisible();
            if (rootContainer != null) ViewCompat.requestApplyInsets(rootContainer);
            emitLivePrayerNotificationStatus();
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (orientationController != null) {
            orientationController.dispose();
            orientationController = null;
        }
        if (prayerNotchStatusReceiver != null) {
            try { unregisterReceiver(prayerNotchStatusReceiver); } catch (Exception ignored) {}
            prayerNotchStatusReceiver = null;
        }
        if (supportFcmTokenReceiver != null) {
            try { unregisterReceiver(supportFcmTokenReceiver); } catch (Exception ignored) {}
            supportFcmTokenReceiver = null;
        }
        if (nativeQuranBridge != null) { nativeQuranBridge.destroy(); nativeQuranBridge = null; }
        if (webView != null) {
            webView.removeJavascriptInterface("MunibinAndroid");
            webView.destroy();
        }
        if (nativeServer != null) nativeServer.stop();
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
        rootContainer = null;
        super.onDestroy();
    }

    private void refreshNativePrayerState() {
        Intent refreshIntent = new Intent(getApplicationContext(), PrayerWidgetProvider.class)
                .setAction(PrayerWidgetProvider.ACTION_REFRESH);
        sendBroadcast(refreshIntent);
        PrayerLiveNotificationController.sync(getApplicationContext());
        requestPrayerNotificationAccessIfNeeded();
    }

    private void restorePrayerWidgetSnapshotFromWebStorage() {
        if (webView == null) return;
        String currentUrl = webView.getUrl();
        if (currentUrl == null || !"127.0.0.1".equals(Uri.parse(currentUrl).getHost())) return;
        webView.evaluateJavascript(
                "(function(){try{var raw=localStorage.getItem('munibin-prayer-widget-snapshot-v1');"
                        + "if(raw&&window.MunibinAndroid){window.MunibinAndroid.publishPrayerWidgetSnapshot(raw);}}catch(e){}})();",
                null);
    }

    private void requestPrayerNotificationAccessIfNeeded() {
        if (!PrayerWidgetStore.hasPrayerNotificationsEnabled(getApplicationContext())) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            if (!notificationPermissionRequested) {
                notificationPermissionRequested = true;
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, PRAYER_NOTIFICATION_PERMISSION_REQUEST);
            }
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AlarmManager alarms = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (alarms != null && !alarms.canScheduleExactAlarms() && !exactAlarmSettingsRequested) {
                exactAlarmSettingsRequested = true;
                try {
                    startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + getPackageName())));
                } catch (Exception ignored) {}
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PRAYER_NOTCH_NOTIFICATION_PERMISSION_REQUEST) {
            boolean explicitEnable = prayerNotchPermissionPending;
            prayerNotchPermissionPending = false;
            PrayerLiveNotificationController.reconcile(getApplicationContext());
            continuePrayerNotchEnable(explicitEnable && grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED);
            return;
        }
        if (requestCode == LIVE_PRAYER_NOTIFICATION_PERMISSION_REQUEST) {
            PrayerLiveNotificationController.reconcile(getApplicationContext());
            continuePrayerNotchEnable(false);
            emitLivePrayerNotificationStatus();
            return;
        }
        if (requestCode == PRAYER_NOTIFICATION_PERMISSION_REQUEST) {
            if (prayerReminderPermissionPending) {
                prayerReminderPermissionPending = false;
                emitPrayerReminderPermission(grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED);
            }
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                PrayerLocalNotificationScheduler.rescheduleStored(getApplicationContext());
                PrayerLiveNotificationController.reconcile(getApplicationContext());
                continuePrayerNotchEnable(false);
                requestPrayerNotificationAccessIfNeeded();
            }
            return;
        }
        if (requestCode == CHAT_NOTIFICATION_PERMISSION_REQUEST) {
            PrayerLiveNotificationController.reconcile(getApplicationContext());
            continuePrayerNotchEnable(false);
            return;
        }
        if (requestCode == NativeQuranInteractionBridge.MICROPHONE_PERMISSION_REQUEST) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (nativeQuranBridge != null) nativeQuranBridge.onMicrophonePermissionResult(granted);
            return;
        }
        if (requestCode == LOCATION_PERMISSION_REQUEST && pendingGeoCallback != null) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            pendingGeoCallback.invoke(pendingGeoOrigin, granted, false);
            pendingGeoCallback = null;
            pendingGeoOrigin = null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == IQAMA_SOUND_REQUEST) {
            if (resultCode == RESULT_OK && data != null) {
                Uri selected = data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
                if (selected != null && (data.getFlags() & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0
                        && (data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
                    try {
                        getContentResolver().takePersistableUriPermission(selected,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (SecurityException ignored) { }
                }
                PrayerIqamaSound.select(getApplicationContext(), selected);
            }
            emitIqamaNotificationSoundStatus();
            return;
        }
        if (requestCode != FILE_CHOOSER_REQUEST || fileChooserCallback == null) return;
        Uri[] results = null;
        if (resultCode == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                int count = data.getClipData().getItemCount();
                results = new Uri[count];
                for (int i = 0; i < count; i++) results[i] = data.getClipData().getItemAt(i).getUri();
            } else if (data.getData() != null) {
                results = new Uri[]{data.getData()};
            }
        }
        fileChooserCallback.onReceiveValue(results);
        fileChooserCallback = null;
    }

    private void requestChatNotificationAccessIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, CHAT_NOTIFICATION_PERMISSION_REQUEST);
        }
    }

    private NotificationManager chatNotificationManager() {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        MunibinFirebaseMessagingService.ensureChannel(manager);
        return manager;
    }

    private int chatNotificationId(String threadId) {
        return MunibinFirebaseMessagingService.supportNotificationId(threadId);
    }

    private void registerSupportFcmTokenReceiver() {
        if (supportFcmTokenReceiver != null) return;
        supportFcmTokenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null) return;
                String token = intent.getStringExtra(MunibinFirebaseMessagingService.EXTRA_FCM_TOKEN);
                dispatchSupportFcmTokenToWeb(token);
            }
        };
        IntentFilter filter = new IntentFilter(MunibinFirebaseMessagingService.ACTION_FCM_TOKEN_REFRESH);
        ContextCompat.registerReceiver(this, supportFcmTokenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    private void dispatchSupportFcmTokenToWeb(String token) {
        if (token == null || token.trim().isEmpty() || webView == null) return;
        try {
            JSONObject detail = new JSONObject();
            detail.put("token", token.trim());
            String js = "window.dispatchEvent(new CustomEvent('munibin-native-fcm-token',{detail:" + detail.toString() + "}));";
            runOnUiThread(() -> webView.evaluateJavascript(js, null));
        } catch (Exception ignored) {}
    }

    private void requestSupportFcmToken() {
        FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                String token = task.getResult();
                if (token != null && !token.trim().isEmpty()) {
                    getSharedPreferences(MunibinFirebaseMessagingService.PREFS, MODE_PRIVATE)
                            .edit().putString(MunibinFirebaseMessagingService.PREF_FCM_TOKEN, token).apply();
                    dispatchSupportFcmTokenToWeb(token);
                    return;
                }
            }
            String cached = getSharedPreferences(MunibinFirebaseMessagingService.PREFS, MODE_PRIVATE)
                    .getString(MunibinFirebaseMessagingService.PREF_FCM_TOKEN, "");
            if (cached != null && !cached.isEmpty()) dispatchSupportFcmTokenToWeb(cached);
        });
    }

    private void handleChatNotificationPayload(String serialized) {
        try {
            JSONObject payload = new JSONObject(serialized == null ? "{}" : serialized);
            String action = payload.optString("action", "");
            if ("request-permission".equals(action)) {
                requestChatNotificationAccessIfNeeded();
                return;
            }
            if ("set-badge".equals(action)) {
                // Android launchers derive app badges from active notifications. Each chat
                // notification carries the current unread count via setNumber().
                return;
            }
            String threadId = payload.optString("threadId", "");
            NotificationManager manager = chatNotificationManager();
            if (manager == null) return;
            if ("clear-thread".equals(action)) {
                if (!threadId.isEmpty()) {
                    manager.cancel(chatNotificationId(threadId));
                    manager.cancel(MunibinFirebaseMessagingService.TAG_PREFIX + threadId, 0);
                }
                return;
            }
            if (!"notify".equals(action) || threadId.isEmpty()) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestChatNotificationAccessIfNeeded();
                return;
            }

            String message = payload.optString("message", "رسالة جديدة").trim();
            if (message.isEmpty()) message = "رسالة جديدة";
            if (message.length() > 500) message = message.substring(0, 500);
            int unreadCount = Math.max(1, payload.optInt("count", 1));
            String route = payload.optString("route", "/feedback?thread=" + Uri.encode(threadId));
            if (!route.startsWith("/") || route.startsWith("//")) route = "/feedback?thread=" + Uri.encode(threadId);

            Uri publicUri = Uri.parse("https://munibin.com" + route);
            Intent openChat = new Intent(this, LauncherActivity.class)
                    .setAction(Intent.ACTION_VIEW)
                    .setData(publicUri)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent contentIntent = PendingIntent.getActivity(
                    this,
                    chatNotificationId(threadId),
                    openChat,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );

            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHAT_NOTIFICATION_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification_icon)
                    .setContentTitle("منيبين 💬")
                    .setContentText(message)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                    .setContentIntent(contentIntent)
                    .setAutoCancel(true)
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setNumber(unreadCount)
                    .setGroup(CHAT_NOTIFICATION_GROUP)
                    .setOnlyAlertOnce(false);
            manager.notify(chatNotificationId(threadId), builder.build());
        } catch (Exception error) {
            android.util.Log.w("MunibinChat", "Invalid chat notification payload", error);
        }
    }

    private void emitPrayerReminderPermission(boolean granted) {
        if (webView == null) return;
        webView.evaluateJavascript("window.dispatchEvent(new CustomEvent('munibin-prayer-permission-result',{detail:{granted:"
                + granted + "}}));", null);
    }

    private void emitIqamaNotificationSoundStatus() {
        if (webView == null) return;
        webView.evaluateJavascript("window.dispatchEvent(new CustomEvent('munibin-iqama-sound-status',{detail:"
                + PrayerIqamaSound.status(getApplicationContext()).toString() + "}));", null);
    }

    private void emitLivePrayerNotificationStatus() {
        if (webView == null) return;
        String status = PrayerLiveNotificationController.getStatus(getApplicationContext()).toString();
        if (status.equals(lastLivePrayerStatus)) return;
        lastLivePrayerStatus = status;
        webView.evaluateJavascript("window.dispatchEvent(new CustomEvent('munibin-live-prayer-status',{detail:"
                + status + "}));", null);
    }

    private void registerPrayerNotchStatusReceiver() {
        if (prayerNotchStatusReceiver != null) return;
        prayerNotchStatusReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (activityResumed && intent != null
                        && intent.getBooleanExtra(PrayerNotchController.EXTRA_RETRY_EXPLICIT_START, false)) {
                    PrayerNotchController.startFromUser(getApplicationContext());
                }
                emitLivePrayerNotificationStatus();
            }
        };
        ContextCompat.registerReceiver(this, prayerNotchStatusReceiver,
                new IntentFilter(PrayerNotchController.ACTION_STATUS), ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    private void continuePrayerNotchEnable(boolean openPermissionSettings) {
        if (!PrayerLiveNotificationController.isEnabled(getApplicationContext())) {
            PrayerNotchController.stop(getApplicationContext());
            emitLivePrayerNotificationStatus();
            return;
        }
        if (PrayerNotchController.shouldRun(getApplicationContext())) {
            PrayerNotchController.startFromUser(getApplicationContext());
        }
        if (PrayerNotchController.isEnabled(getApplicationContext()) && openPermissionSettings
                && !PrayerNotchController.hasOverlayPermission(getApplicationContext())
                && androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled()
                && (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED)) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (RuntimeException unavailable) {
                Toast.makeText(this, "اسمح لمنيبين بالظهور فوق التطبيقات الأخرى من إعدادات Android.",
                        Toast.LENGTH_LONG).show();
            }
        }
        emitLivePrayerNotificationStatus();
    }

    private final class AndroidBridge {
        @JavascriptInterface
        public void setPrayerTheme(String serialized) {
            runOnUiThread(() -> {
                try {
                    PrayerWidgetTheme.set(getApplicationContext(), serialized);
                    PrayerNotificationReceiver.refreshDisplayedNotifications(getApplicationContext());
                    PrayerWidgetProvider.renderStoredSnapshot(getApplicationContext());
                    PrayerLiveNotificationController.reconcile(getApplicationContext());
                    PrayerNotchController.notifySnapshotChanged(getApplicationContext());
                } catch (org.json.JSONException ignored) { }
            });
        }

        @JavascriptInterface
        public String getPrayerTimeFormat() {
            return PrayerTimeFormat.read(getApplicationContext());
        }

        @JavascriptInterface
        public void setPrayerTimeFormat(String format) {
            runOnUiThread(() -> {
                PrayerTimeFormat.set(getApplicationContext(), format);
                PrayerNotificationReceiver.refreshDisplayedNotifications(getApplicationContext());
                PrayerWidgetProvider.renderStoredSnapshot(getApplicationContext());
                PrayerLiveNotificationController.reconcile(getApplicationContext());
                PrayerNotchController.notifySnapshotChanged(getApplicationContext());
            });
        }

        @JavascriptInterface
        public String getIqamaNotificationSoundStatus() {
            return PrayerIqamaSound.status(getApplicationContext()).toString();
        }

        @JavascriptInterface
        public void selectIqamaNotificationSound() {
            runOnUiThread(() -> {
                Intent picker = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, getString(R.string.prayer_iqama_sound_picker_title))
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, PrayerIqamaSound.selectedUri(getApplicationContext()));
                try {
                    startActivityForResult(picker, IQAMA_SOUND_REQUEST);
                } catch (RuntimeException unavailable) {
                    PrayerIqamaSound.createChannel(getApplicationContext());
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        try {
                            startActivity(new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())
                                    .putExtra(Settings.EXTRA_CHANNEL_ID, PrayerIqamaSound.channelId(getApplicationContext())));
                        } catch (RuntimeException ignored) {
                            Toast.makeText(LauncherActivity.this, R.string.prayer_iqama_sound_picker_unavailable, Toast.LENGTH_LONG).show();
                        }
                    } else {
                        Toast.makeText(LauncherActivity.this, R.string.prayer_iqama_sound_picker_unavailable, Toast.LENGTH_LONG).show();
                    }
                    emitIqamaNotificationSoundStatus();
                }
            });
        }

        @JavascriptInterface
        public String getLivePrayerNotificationStatus() {
            return PrayerLiveNotificationController.getStatus(getApplicationContext()).toString();
        }

        @JavascriptInterface
        public void requestLivePrayerNotificationStatus() {
            runOnUiThread(() -> {
                lastLivePrayerStatus = "";
                emitLivePrayerNotificationStatus();
            });
        }

        @JavascriptInterface
        public void setLivePrayerNotificationEnabled(boolean enabled) {
            runOnUiThread(() -> {
                PrayerLiveNotificationController.setEnabled(getApplicationContext(), enabled);
                if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                        && ContextCompat.checkSelfPermission(LauncherActivity.this,
                        Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    notificationPermissionRequested = true;
                    ActivityCompat.requestPermissions(LauncherActivity.this,
                            new String[]{Manifest.permission.POST_NOTIFICATIONS},
                            LIVE_PRAYER_NOTIFICATION_PERMISSION_REQUEST);
                } else {
                    continuePrayerNotchEnable(false);
                }
                emitLivePrayerNotificationStatus();
            });
        }

        @JavascriptInterface
        public void requestLivePrayerNotificationSettings(String kind) {
            runOnUiThread(() -> {
                try {
                    if ("notifications".equals(kind)) {
                        Intent settings = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                                ? new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())
                                : new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:" + getPackageName()));
                        startActivity(settings);
                    } else if ("alarms".equals(kind) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                Uri.parse("package:" + getPackageName())));
                    }
                } catch (RuntimeException unavailable) {
                    Toast.makeText(LauncherActivity.this, "افتح إعدادات منيبين للسماح بالإشعارات والمنبّهات الدقيقة.",
                            Toast.LENGTH_LONG).show();
                }
            });
        }

        @JavascriptInterface
        public void setPrayerNotchEnabled(boolean enabled) {
            runOnUiThread(() -> {
                if (enabled && !PrayerLiveNotificationController.isEnabled(getApplicationContext())) {
                    PrayerLiveNotificationController.setEnabled(getApplicationContext(), true);
                }
                PrayerNotchController.setEnabled(getApplicationContext(), enabled);
                if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                        && ContextCompat.checkSelfPermission(LauncherActivity.this,
                        Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    prayerNotchPermissionPending = true;
                    notificationPermissionRequested = true;
                    ActivityCompat.requestPermissions(LauncherActivity.this,
                            new String[]{Manifest.permission.POST_NOTIFICATIONS},
                            PRAYER_NOTCH_NOTIFICATION_PERMISSION_REQUEST);
                    emitLivePrayerNotificationStatus();
                } else {
                    continuePrayerNotchEnable(enabled);
                }
            });
        }

        @JavascriptInterface
        public void requestPrayerNotchPermission() {
            runOnUiThread(() -> continuePrayerNotchEnable(true));
        }
        @JavascriptInterface
        public void requestPrayerReminderPermission() {
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                        && ContextCompat.checkSelfPermission(LauncherActivity.this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    prayerReminderPermissionPending = true;
                    notificationPermissionRequested = true;
                    ActivityCompat.requestPermissions(LauncherActivity.this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, PRAYER_NOTIFICATION_PERMISSION_REQUEST);
                } else {
                    emitPrayerReminderPermission(androidx.core.app.NotificationManagerCompat.from(LauncherActivity.this).areNotificationsEnabled());
                }
            });
        }

        @JavascriptInterface
        public void handleChatNotification(String serialized) {
            runOnUiThread(() -> handleChatNotificationPayload(serialized));
        }

        @JavascriptInterface
        public void requestSupportPushToken() {
            runOnUiThread(() -> requestSupportFcmToken());
        }

        @JavascriptInterface
        public void startSocialAuth(String provider) {
            if (!"google".equals(provider) && !"apple".equals(provider)) return;
            runOnUiThread(() -> {
                try {
                    Uri authUri = Uri.parse("https://munibin.com/api/auth/" + provider + "?native=1");
                    Intent browser = new Intent(Intent.ACTION_VIEW, authUri);
                    browser.addCategory(Intent.CATEGORY_BROWSABLE);
                    startActivity(browser);
                } catch (Exception error) {
                    try {
                        JSONObject detail = new JSONObject();
                        detail.put("error", "تعذر فتح تسجيل الدخول.");
                        String js = "window.dispatchEvent(new CustomEvent('munibin-native-social-auth',{detail:" + detail.toString() + "}));";
                        webView.evaluateJavascript(js, null);
                    } catch (Exception ignored) {}
                }
            });
        }

        @JavascriptInterface
        public void publishPrayerWidgetSnapshot(String serialized) {
            runOnUiThread(() -> {
                try {
                    JSONObject snapshot = new JSONObject(serialized);
                    PrayerWidgetStore.saveSnapshot(getApplicationContext(), snapshot.toString());
                    PrayerWidgetProvider.renderStoredSnapshot(getApplicationContext());
                    if (activityResumed && !PrayerNotchService.isRunning()) {
                        PrayerNotchController.startFromUser(getApplicationContext());
                    }
                    emitLivePrayerNotificationStatus();
                    if (snapshot.optBoolean("notificationsEnabled", false)) requestPrayerNotificationAccessIfNeeded();
                } catch (Exception error) {
                    android.util.Log.w("MunibinBridge", "Invalid prayer snapshot", error);
                }
            });
        }

        @JavascriptInterface
        public void startSpeechRecognition(String requestId) {
            if (nativeQuranBridge != null) nativeQuranBridge.startSpeech(requestId);
        }

        @JavascriptInterface
        public void stopSpeechRecognition() {
            if (nativeQuranBridge != null) nativeQuranBridge.stopSpeech();
        }

        @JavascriptInterface
        public void startQuranAudioRecognition(String requestId) {
            if (nativeQuranBridge != null) nativeQuranBridge.startQuranAudio(requestId);
        }

        @JavascriptInterface
        public void stopQuranAudioRecognition() {
            if (nativeQuranBridge != null) nativeQuranBridge.stopQuranAudio();
        }

        @JavascriptInterface
        public void startCompass() {
            if (nativeQuranBridge != null) nativeQuranBridge.startCompass();
        }

        @JavascriptInterface
        public void stopCompass() {
            if (nativeQuranBridge != null) nativeQuranBridge.stopCompass();
        }

        @JavascriptInterface
        public void savePersistentState(String serialized) {
            if (serialized == null || serialized.length() > 2_000_000) return;
            getSharedPreferences("munibin_persistent_state", MODE_PRIVATE).edit().putString("web_v1", serialized).commit();
        }

        @JavascriptInterface
        public String getPersistentState() {
            return getSharedPreferences("munibin_persistent_state", MODE_PRIVATE).getString("web_v1", "");
        }

        @JavascriptInterface
        public void publishNowPlaying(String serialized) {
            runOnUiThread(() -> {
                if (mediaSession == null) return;
                try {
                    JSONObject payload = new JSONObject(serialized);
                    String title = payload.optString("title", "القرآن الكريم");
                    String artist = payload.optString("artist", "القارئ");
                    String album = payload.optString("album", "القرآن الكريم");
                    String state = payload.optString("state", "stopped");
                    MediaMetadata metadata = new MediaMetadata.Builder()
                            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                            .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                            .putString(MediaMetadata.METADATA_KEY_ALBUM, album)
                            .build();
                    mediaSession.setMetadata(metadata);
                    int playbackState = "playing".equals(state)
                            ? PlaybackState.STATE_PLAYING
                            : ("paused".equals(state) ? PlaybackState.STATE_PAUSED : PlaybackState.STATE_STOPPED);
                    mediaSession.setPlaybackState(new PlaybackState.Builder()
                            .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE)
                            .setState(playbackState, PlaybackState.PLAYBACK_POSITION_UNKNOWN, playbackState == PlaybackState.STATE_PLAYING ? 1f : 0f)
                            .build());
                    mediaSession.setActive(playbackState != PlaybackState.STATE_STOPPED);
                } catch (Exception error) {
                    android.util.Log.w("MunibinBridge", "Invalid now-playing payload", error);
                }
            });
        }
    }

    private final class MunibinWebViewClient extends WebViewClient {
        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            if (pendingSocialAuthCallback != null) {
                Uri callback = pendingSocialAuthCallback;
                pendingSocialAuthCallback = null;
                dispatchSocialAuthCallback(callback);
            }
            restorePrayerWidgetSnapshotFromWebStorage();
            view.evaluateJavascript(
                    "(function(){if(window.__munibinHideSetupSpeedInstalled)return;window.__munibinHideSetupSpeedInstalled=true;" +
                            "function hide(){document.querySelectorAll('.qcf1441-reciter-settings--speed').forEach(function(el){el.style.display='none';el.setAttribute('aria-hidden','true');});}" +
                            "hide();new MutationObserver(hide).observe(document.documentElement,{childList:true,subtree:true});})();",
                    null
            );
            dismissLaunchAnimationWhenReady();
        }

        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            if (nativeQuranBridge != null) nativeQuranBridge.stopSpeech();
            // addJavascriptInterface exists before document scripts, but these stable markers
            // let the shared React app classify the native shell without UA heuristics.
            view.evaluateJavascript("window.__munibinNativeApp=true;window.__munibinNativePlatform='android';", null);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            String host = uri.getHost();
            if ("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host)) return false;
            if ("munibin.com".equalsIgnoreCase(host) || (host != null && host.toLowerCase().endsWith(".munibin.com"))) {
                view.loadUrl(nativeServer.localUriFor(uri).toString());
                return true;
            }
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            if ("http".equals(scheme) || "https".equals(scheme) || "mailto".equals(scheme) || "tel".equals(scheme)) {
                try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch (Exception ignored) {}
                return true;
            }
            return true;
        }
    }

    private final class MunibinChromeClient extends WebChromeClient {
        @Override
        public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
            if (ContextCompat.checkSelfPermission(LauncherActivity.this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                callback.invoke(origin, true, false);
                return;
            }
            pendingGeoCallback = callback;
            pendingGeoOrigin = origin;
            ActivityCompat.requestPermissions(LauncherActivity.this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, LOCATION_PERMISSION_REQUEST);
        }

        @Override
        public void onPermissionRequest(PermissionRequest request) {
            runOnUiThread(() -> request.grant(request.getResources()));
        }

        @Override
        public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
            if (fileChooserCallback != null) fileChooserCallback.onReceiveValue(null);
            fileChooserCallback = filePathCallback;
            Intent chooser;
            try { chooser = fileChooserParams.createIntent(); }
            catch (Exception error) {
                filePathCallback.onReceiveValue(null);
                fileChooserCallback = null;
                return false;
            }
            chooser.addCategory(Intent.CATEGORY_OPENABLE);
            chooser.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, fileChooserParams.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
            try { startActivityForResult(chooser, FILE_CHOOSER_REQUEST); }
            catch (Exception error) {
                filePathCallback.onReceiveValue(null);
                fileChooserCallback = null;
                return false;
            }
            return true;
        }
    }

    private DownloadListener createDownloadListener() {
        return (url, userAgent, contentDisposition, mimetype, contentLength) -> {
            try {
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                request.setMimeType(mimetype);
                request.addRequestHeader("User-Agent", userAgent);
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, android.webkit.URLUtil.guessFileName(url, contentDisposition, mimetype));
                DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                if (manager != null) manager.enqueue(request);
            } catch (Exception error) {
                Toast.makeText(this, "تعذر تنزيل الملف", Toast.LENGTH_SHORT).show();
            }
        };
    }
}
