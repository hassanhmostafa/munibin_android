package com.motoonai.twa;

import android.Manifest;
import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;

import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;

final class PrayerLocalNotificationScheduler {
    static final String ACTION_PRAYER_ALARM = "com.motoonai.twa.action.PRAYER_ALARM";
    static final String EXTRA_LABEL = "label";
    static final String EXTRA_KEY = "key";
    static final String EXTRA_MODE = "alertMode";
    static final String EXTRA_KIND = "kind";
    static final String EXTRA_MINUTES = "minutesBefore";
    static final String EXTRA_PRAYER_AT = "prayerAt";
    static final String EXTRA_EVENT_AT = "eventAt";
    static final String EXTRA_TIME_ZONE = "timeZone";
    private static final String DEFAULT_CHANNEL_ID = "munibin_prayer_times";
    private static final String ADHAN_CHANNEL_ID = "munibin_prayer_adhan";
    private static final String REMINDER_CHANNEL_ID = "munibin_prayer_reminders";
    private static final String MODE_AUDIO = "audio";
    private static final int REQUEST_CODE_OFFSET = 7400;
    private static String lastConfigKey = "";
    private static final java.util.List<Long> scheduledTimes = new java.util.ArrayList<>();

    private PrayerLocalNotificationScheduler() {}

    static synchronized void sync(Context context, JSONObject snapshot) {
        String configKey = schedulingConfigKey(snapshot.optString("notificationConfigKey", ""));
        long now = System.currentTimeMillis();
        // A clock-only snapshot must not cancel an alarm while Android is delivering it.
        // Actual preference/location edits have a different key and cancel immediately.
        if (snapshot.optBoolean("prayerTimesEnabled", false) && snapshot.optBoolean("notificationsEnabled", false)
                && !configKey.isEmpty() && configKey.equals(lastConfigKey)) {
            for (long scheduled : scheduledTimes) {
                if (Math.abs(scheduled - now) <= 90_000L) return;
            }
        }
        cancelAll(context);
        scheduledTimes.clear();
        lastConfigKey = "";
        if (!snapshot.optBoolean("prayerTimesEnabled", false)
                || !snapshot.optBoolean("notificationsEnabled", false)
                || !hasNotificationPermission(context)) return;
        JSONArray prayers = snapshot.optJSONArray("upcomingPrayers");
        if (prayers == null) return;
        for (int index = 0; index < prayers.length() && index < 60; index++) {
            JSONObject prayer = prayers.optJSONObject(index);
            if (prayer == null) continue;
            long atMillis = parseTime(prayer.optString("at", ""));
            if (atMillis <= System.currentTimeMillis()) continue;
            boolean reminder = "pre-prayer".equals(prayer.optString("kind", "prayer"));
            boolean iqama = "iqama".equals(prayer.optString("kind", prayer.optString("type", "prayer")));
            String mode = prayer.optString("alertMode", "notification");
            if ("off".equals(mode)) continue;
            int minutes = prayer.optInt("minutesBefore", 0);
            if (reminder && (minutes < 1 || minutes > 120)) continue;
            String key = prayer.optString("key", "");
            long prayerAt = parseTime(prayer.optString("prayerAt", prayer.optString("at", "")));
            // Sunrise is not a prayer; a zero offset must not produce a second alert at adhan.
            if (iqama && (!isIqamaPrayer(key) || prayerAt <= 0L || atMillis <= prayerAt)) continue;
            schedule(context, index, atMillis, prayer.optString("label", "الصلاة"), prayer.optString("key", ""),
                    !reminder && !iqama && MODE_AUDIO.equals(mode) ? MODE_AUDIO : "notification",
                    reminder ? "pre-prayer" : iqama ? "iqama" : "prayer", minutes, prayerAt,
                    prayer.optString("timeZone", snapshotTimeZone(snapshot)));
            scheduledTimes.add(atMillis);
        }
        lastConfigKey = configKey;
    }

    static void rescheduleStored(Context context) {
        JSONObject snapshot = PrayerWidgetStore.loadSnapshot(context);
        if (snapshot != null) sync(context, snapshot);
    }

    static void createChannels(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel defaultChannel = new NotificationChannel(DEFAULT_CHANNEL_ID, "مواقيت الصلاة", NotificationManager.IMPORTANCE_HIGH);
        defaultChannel.setDescription("تنبيهات محلية دقيقة عند دخول وقت الصلاة");
        defaultChannel.enableVibration(true);
        manager.createNotificationChannel(defaultChannel);

        // Reminders always use the device's notification sound, never the adhan channel.
        NotificationChannel reminderChannel = new NotificationChannel(REMINDER_CHANNEL_ID, "تذكير قبل الصلاة", NotificationManager.IMPORTANCE_HIGH);
        reminderChannel.setDescription("تنبيه الجهاز قبل الصلاة بالمدة التي تختارها");
        reminderChannel.enableVibration(true);
        reminderChannel.setSound(android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION),
                new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build());
        manager.createNotificationChannel(reminderChannel);

        // Android لا يحدّث صوت قناة موجودة؛ لهذا تستعمل قناة مستقلة ثابتة للأذان.
        NotificationChannel adhanChannel = new NotificationChannel(ADHAN_CHANNEL_ID, "مواقيت الصلاة — أذان", NotificationManager.IMPORTANCE_HIGH);
        adhanChannel.setDescription("تنبيهات محلية قصيرة بصوت الأذان عند دخول وقت الصلاة");
        adhanChannel.enableVibration(true);
        Uri adhanSound = Uri.parse("android.resource://" + context.getPackageName() + "/raw/munibin_adhan_short");
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        adhanChannel.setSound(adhanSound, attributes);
        manager.createNotificationChannel(adhanChannel);
        PrayerIqamaSound.createChannel(context);
    }

    static String channelId(String mode) { return MODE_AUDIO.equals(mode) ? ADHAN_CHANNEL_ID : DEFAULT_CHANNEL_ID; }
    static String reminderChannelId() { return REMINDER_CHANNEL_ID; }

    private static void schedule(Context context, int index, long atMillis, String label, String key, String mode,
                                 String kind, int minutes, long prayerAt, String timeZone) {
        Intent intent = new Intent(context, PrayerNotificationReceiver.class)
                .setAction(ACTION_PRAYER_ALARM)
                .putExtra(EXTRA_LABEL, label)
                .putExtra(EXTRA_KEY, key)
                .putExtra(EXTRA_MODE, "prayer".equals(kind) ? mode : "notification")
                .putExtra(EXTRA_KIND, kind)
                .putExtra(EXTRA_MINUTES, minutes)
                .putExtra(EXTRA_PRAYER_AT, prayerAt)
                .putExtra(EXTRA_EVENT_AT, atMillis)
                .putExtra(EXTRA_TIME_ZONE, timeZone);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(context, REQUEST_CODE_OFFSET + index, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarms.canScheduleExactAlarms()) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pendingIntent);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pendingIntent);
        } else {
            alarms.setExact(AlarmManager.RTC_WAKEUP, atMillis, pendingIntent);
        }
    }

    private static void cancelAll(Context context) {
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        for (int index = 0; index < 60; index++) {
            Intent intent = new Intent(context, PrayerNotificationReceiver.class).setAction(ACTION_PRAYER_ALARM);
            PendingIntent pendingIntent = PendingIntent.getBroadcast(context, REQUEST_CODE_OFFSET + index, intent, PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
            if (pendingIntent != null) alarms.cancel(pendingIntent);
        }
    }

    private static boolean hasNotificationPermission(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private static String snapshotTimeZone(JSONObject snapshot) {
        JSONObject live = snapshot.optJSONObject("livePrayerSchedule");
        String id = live == null ? "" : live.optString("timeZone", "");
        if (!id.isEmpty()) return id;
        try {
            id = new JSONObject(snapshot.optString("notificationConfigKey", "{}")).optString("timeZone", "");
        } catch (Exception ignored) { }
        return id.isEmpty() ? TimeZone.getDefault().getID() : id;
    }

    private static String schedulingConfigKey(String raw) {
        if (raw.isEmpty()) return raw;
        try {
            JSONObject configuration = new JSONObject(raw);
            for (String displayOnly : new String[]{"timeFormat", "theme", "backgroundPalette", "themeColors"}) {
                configuration.remove(displayOnly);
            }
            return configuration.toString();
        } catch (Exception ignored) {
            return raw;
        }
    }

    private static boolean isIqamaPrayer(String key) {
        String normalized = key == null ? "" : key.toLowerCase(Locale.US);
        return "fajr".equals(normalized) || "dhuhr".equals(normalized) || "asr".equals(normalized)
                || "maghrib".equals(normalized) || "isha".equals(normalized);
    }

    static long parseTime(String raw) {
        if (raw == null || raw.trim().isEmpty()) return 0L;
        String value = raw.trim();

        try {
            if (value.endsWith("Z")) {
                String pattern = value.contains(".")
                        ? "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
                        : "yyyy-MM-dd'T'HH:mm:ss'Z'";
                SimpleDateFormat formatter = new SimpleDateFormat(pattern, Locale.US);
                formatter.setTimeZone(TimeZone.getTimeZone("UTC"));
                return formatter.parse(value).getTime();
            }

            // API 23's SimpleDateFormat does not support the ISO-8601 X pattern.
            // Convert offsets such as +03:00 to +0300 and parse them with Z instead.
            if (value.matches(".*[+-]\\d{2}:\\d{2}$")) {
                int length = value.length();
                value = value.substring(0, length - 3) + value.substring(length - 2);
            }

            String pattern = value.contains(".")
                    ? "yyyy-MM-dd'T'HH:mm:ss.SSSZ"
                    : "yyyy-MM-dd'T'HH:mm:ssZ";
            SimpleDateFormat formatter = new SimpleDateFormat(pattern, Locale.US);
            return formatter.parse(value).getTime();
        } catch (Exception ignored) {
            return 0L;
        }
    }

}
