package com.motoonai.twa;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.content.pm.PackageManager;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.Map;

/**
 * Receives Munibin support-chat FCM messages while Android delivers them to the app.
 * Background notification messages are normally rendered by FCM itself; foreground
 * messages arrive here and are rendered with the same title, route and unread count.
 */
public class MunibinFirebaseMessagingService extends FirebaseMessagingService {
    public static final String ACTION_FCM_TOKEN_REFRESH = "com.motoonai.twa.SUPPORT_FCM_TOKEN";
    public static final String EXTRA_FCM_TOKEN = "token";
    public static final String PREFS = "munibin_support_push";
    public static final String PREF_FCM_TOKEN = "fcm_token";
    public static final String CHANNEL_ID = "munibin_messages";
    public static final String TAG_PREFIX = "munibin-support-";

    @Override
    public void onNewToken(String token) {
        super.onNewToken(token);
        if (token == null || token.trim().isEmpty()) return;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_FCM_TOKEN, token).apply();
        Intent refresh = new Intent(ACTION_FCM_TOKEN_REFRESH)
                .setPackage(getPackageName())
                .putExtra(EXTRA_FCM_TOKEN, token);
        sendBroadcast(refresh);
    }

    @Override
    public void onMessageReceived(RemoteMessage remoteMessage) {
        super.onMessageReceived(remoteMessage);
        Map<String, String> data = remoteMessage.getData();
        if (!"support_chat".equals(data.get("type"))) return;

        String threadId = safe(data.get("threadId"));
        if (threadId.isEmpty()) return;
        String route = safe(data.get("route"));
        if (!isSafeRoute(route)) route = "/feedback?thread=" + Uri.encode(threadId);

        RemoteMessage.Notification remoteNotification = remoteMessage.getNotification();
        String title = remoteNotification != null ? safe(remoteNotification.getTitle()) : "";
        if (title.isEmpty()) title = "منيبين 💬";
        String body = remoteNotification != null ? safe(remoteNotification.getBody()) : "";
        if (body.isEmpty()) body = "رسالة جديدة";
        if (body.length() > 500) body = body.substring(0, 500);

        int unreadCount = 1;
        try { unreadCount = Math.max(1, Integer.parseInt(safe(data.get("unreadCount")))); }
        catch (Exception ignored) {}

        showSupportNotification(threadId, route, title, body, unreadCount);
    }

    private void showSupportNotification(String threadId, String route, String title, String body, int unreadCount) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        ensureChannel(manager);

        Uri publicUri = Uri.parse("https://munibin.com" + route);
        Intent openChat = new Intent(this, LauncherActivity.class)
                .setAction(Intent.ACTION_VIEW)
                .setData(publicUri)
                .putExtra("route", route)
                .putExtra("threadId", threadId)
                .putExtra("type", "support_chat")
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this,
                supportNotificationId(threadId),
                openChat,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_icon)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setNumber(unreadCount)
                .setGroup("munibin_support_messages")
                .setOnlyAlertOnce(false);

        manager.notify(TAG_PREFIX + threadId, 0, builder.build());
    }

    public static void ensureChannel(NotificationManager manager) {
        if (manager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = manager.getNotificationChannel(CHANNEL_ID);
            if (channel == null) {
                channel = new NotificationChannel(CHANNEL_ID, "رسائل منيبين", NotificationManager.IMPORTANCE_HIGH);
                channel.setDescription("تنبيهات الردود الجديدة في محادثة الدعم");
                channel.enableVibration(true);
                manager.createNotificationChannel(channel);
            }
        }
    }

    public static int supportNotificationId(String threadId) {
        return 0x4d420000 ^ (threadId == null ? 0 : threadId.hashCode());
    }

    private static boolean isSafeRoute(String route) {
        return route != null && route.startsWith("/") && !route.startsWith("//");
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
