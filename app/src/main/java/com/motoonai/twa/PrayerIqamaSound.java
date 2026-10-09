package com.motoonai.twa;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;

import org.json.JSONObject;

/** User-selected device notification tone; no adhan playback or file storage permission. */
final class PrayerIqamaSound {
    private static final String PREFS = "munibin_iqama_sound";
    private static final String URI = "uri";
    private static final String REVISION = "channel_revision";
    private static final String CHANNEL = "munibin_prayer_iqama_";

    private PrayerIqamaSound() {}

    static Uri selectedUri(Context context) {
        String value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(URI, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION).toString());
        return value == null || value.isEmpty() ? null : Uri.parse(value);
    }

    static synchronized void select(Context context, Uri selected) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String next = selected == null ? "" : selected.toString();
        String current = preferences.getString(URI, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION).toString());
        Uri effective = effectiveUri(context);
        if (!next.equals(current) || !next.equals(effective == null ? "" : effective.toString())) {
            preferences.edit().putString(URI, next)
                    .putLong(REVISION, preferences.getLong(REVISION, 0L) + 1L).apply();
        }
        createChannel(context);
    }

    static String channelId(Context context) {
        return CHANNEL + context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(REVISION, 0L);
    }

    private static Uri effectiveUri(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel channel = manager == null ? null : manager.getNotificationChannel(channelId(context));
            if (channel != null) return channel.getSound();
        }
        return selectedUri(context);
    }

    static void createChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(channelId(context),
                context.getString(R.string.prayer_iqama_channel_name), NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription(context.getString(R.string.prayer_iqama_channel_description));
        channel.enableVibration(true);
        channel.setSound(selectedUri(context), new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION).build());
        // Each explicit sound change gets a new channel because Android keeps an existing channel's sound.
        manager.createNotificationChannel(channel);
    }

    static JSONObject status(Context context) {
        JSONObject result = new JSONObject();
        Uri uri = effectiveUri(context);
        String name = context.getString(uri == null ? R.string.prayer_iqama_sound_silent
                : R.string.prayer_iqama_sound_default);
        try {
            if (uri != null && !RingtoneManager.isDefault(uri)) {
                Ringtone ringtone = RingtoneManager.getRingtone(context, uri);
                if (ringtone != null) name = ringtone.getTitle(context);
            }
        } catch (Exception ignored) { }
        try {
            result.put("name", name);
            result.put("uri", uri == null ? "" : uri.toString());
            result.put("selectionSupported", true);
        } catch (Exception ignored) { }
        return result;
    }
}
