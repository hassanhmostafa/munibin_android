package com.motoonai.twa;

import android.app.PendingIntent;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

public class PrayerNotificationReceiver extends BroadcastReceiver {
    private static final String ALERT_METADATA = "munibinPrayerAlert";

    @Override
    public void onReceive(Context context, Intent intent) {
        post(context, intent, false, System.currentTimeMillis());
    }

    private static void post(Context context, Intent intent, boolean update, long postedAt) {
        PrayerLocalNotificationScheduler.createChannels(context);

        String label = intent.getStringExtra(PrayerLocalNotificationScheduler.EXTRA_LABEL);
        String key = intent.getStringExtra(PrayerLocalNotificationScheduler.EXTRA_KEY);
        String mode = intent.getStringExtra(PrayerLocalNotificationScheduler.EXTRA_MODE);
        boolean reminder = "pre-prayer".equals(intent.getStringExtra(PrayerLocalNotificationScheduler.EXTRA_KIND));
        boolean iqama = "iqama".equals(intent.getStringExtra(PrayerLocalNotificationScheduler.EXTRA_KIND));
        int minutes = intent.getIntExtra(PrayerLocalNotificationScheduler.EXTRA_MINUTES, 10);

        String displayLabel = (label == null || label.trim().isEmpty()) ? "الصلاة" : label.trim();
        String zoneId = intent.getStringExtra(PrayerLocalNotificationScheduler.EXTRA_TIME_ZONE);
        java.util.TimeZone zone = zoneId == null || zoneId.isEmpty()
                ? java.util.TimeZone.getDefault() : java.util.TimeZone.getTimeZone(zoneId);
        long displayAt = intent.getLongExtra(iqama ? PrayerLocalNotificationScheduler.EXTRA_EVENT_AT
                : PrayerLocalNotificationScheduler.EXTRA_PRAYER_AT, System.currentTimeMillis());
        String prayerTime = PrayerTimeFormat.formatMillis(context, displayAt, zone);

        Intent open = new Intent(context, LauncherActivity.class)
                .setData(android.net.Uri.parse("https://munibin.com/prayer-times"));

        PendingIntent openIntent = PendingIntent.getActivity(
                context,
                8800,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder notification = new NotificationCompat.Builder(
                context,
                iqama ? PrayerIqamaSound.channelId(context) : reminder ? PrayerLocalNotificationScheduler.reminderChannelId()
                        : PrayerLocalNotificationScheduler.channelId(mode)
        )
                .setSmallIcon(R.drawable.ic_notification_icon)
                .setContentTitle((iqama ? "إقامة " : reminder ? "تذكير بصلاة " : "") + displayLabel + " " + prayerTime)
                .setContentText(iqama ? "حان وقت إقامة صلاة " + displayLabel
                        : reminder ? "تبقى " + minutes + " دقيقة على صلاة " + displayLabel : "حان وقت صلاة " + displayLabel)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setWhen(postedAt)
                .setOnlyAlertOnce(update)
                .setContentIntent(openIntent);

        android.os.Bundle metadata = intent.getExtras() == null ? new android.os.Bundle() : new android.os.Bundle(intent.getExtras());
        metadata.putBoolean(ALERT_METADATA, true);
        notification.addExtras(metadata);

        if (iqama) {
            org.json.JSONObject snapshot = PrayerWidgetStore.loadSnapshot(context);
            notification.setColor(PrayerWidgetTheme.read(context, snapshot).iqama);
        }

        if (reminder && android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) {
            notification.setDefaults(android.app.Notification.DEFAULT_ALL);
        } else if (iqama && android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) {
            notification.setSound(PrayerIqamaSound.selectedUri(context))
                    .setDefaults(android.app.Notification.DEFAULT_VIBRATE | android.app.Notification.DEFAULT_LIGHTS);
        }

        NotificationManagerCompat.from(context).notify(
                notificationId(key, iqama ? "iqama" : reminder ? "reminder" : "prayer"),
                notification.build()
        );
    }

    static void refreshDisplayedNotifications(Context context) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        try {
            for (android.service.notification.StatusBarNotification active : manager.getActiveNotifications()) {
                android.os.Bundle metadata = active.getNotification().extras;
                if (metadata != null && metadata.getBoolean(ALERT_METADATA, false)) {
                    post(context, storedAlertIntent(context, metadata),
                            true, active.getNotification().when);
                } else if (isLegacyPrayerAlert(active.getId())) {
                    // Older alerts lack an event instant; remove their stale formatted clock instead of guessing it.
                    manager.cancel(active.getId());
                }
            }
        } catch (SecurityException ignored) { }
    }

    private static int notificationId(String key, String kind) {
        return 9000 + (((key == null ? "prayer" : key) + ":" + kind).hashCode() & 0x0fffffff);
    }

    private static Intent storedAlertIntent(Context context, android.os.Bundle metadata) {
        Intent intent = new Intent(context, PrayerNotificationReceiver.class);
        for (String field : new String[]{PrayerLocalNotificationScheduler.EXTRA_LABEL, PrayerLocalNotificationScheduler.EXTRA_KEY,
                PrayerLocalNotificationScheduler.EXTRA_MODE, PrayerLocalNotificationScheduler.EXTRA_KIND,
                PrayerLocalNotificationScheduler.EXTRA_TIME_ZONE}) {
            intent.putExtra(field, metadata.getString(field));
        }
        intent.putExtra(PrayerLocalNotificationScheduler.EXTRA_MINUTES, metadata.getInt(PrayerLocalNotificationScheduler.EXTRA_MINUTES, 0));
        intent.putExtra(PrayerLocalNotificationScheduler.EXTRA_PRAYER_AT, metadata.getLong(PrayerLocalNotificationScheduler.EXTRA_PRAYER_AT, 0L));
        intent.putExtra(PrayerLocalNotificationScheduler.EXTRA_EVENT_AT, metadata.getLong(PrayerLocalNotificationScheduler.EXTRA_EVENT_AT, 0L));
        return intent;
    }

    private static boolean isLegacyPrayerAlert(int id) {
        for (String key : new String[]{"fajr", "sunrise", "dhuhr", "asr", "maghrib", "isha", "prayer"}) {
            for (String spelling : new String[]{key, key.substring(0, 1).toUpperCase(java.util.Locale.US) + key.substring(1)}) {
                if (id == notificationId(spelling, "prayer") || id == notificationId(spelling, "reminder")) return true;
            }
        }
        return false;
    }
}
