package com.motoonai.twa;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

final class PrayerWidgetStore {
    private static final String PREFS = "munibin_prayer_widget";
    private static final String DEVICE_ID = "device_id";
    private static final String SNAPSHOT = "snapshot";

    private PrayerWidgetStore() {}

    static String getDeviceId(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String current = preferences.getString(DEVICE_ID, null);
        if (current != null && current.length() >= 20) return current;
        String generated = UUID.randomUUID().toString().replace("-", "");
        preferences.edit().putString(DEVICE_ID, generated).apply();
        return generated;
    }

    static synchronized void saveSnapshot(Context context, String snapshot) throws JSONException {
        JSONObject parsed = new JSONObject(snapshot);
        if (parsed.optInt("version") < 1 || !parsed.has("prayerTimesEnabled")) {
            throw new JSONException("Unsupported prayer widget snapshot");
        }

        boolean configured = parsed.optBoolean("prayerTimesEnabled", false);
        if (configured && (parsed.optJSONArray("timings") == null
                || parsed.optJSONArray("timings").length() == 0)) {
            throw new JSONException("Prayer times are enabled but timings are missing");
        }

        preservePreviousLivePrayerDay(parsed, loadSnapshot(context));
        if (parsed.has("timeFormat")) PrayerTimeFormat.initializeFromSnapshot(context, parsed.optString("timeFormat", "24h"));
        PrayerWidgetTheme.initializeFromSnapshot(context, parsed);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(SNAPSHOT, parsed.toString()).apply();
        PrayerLiveNotificationController.sync(context);
        PrayerLocalNotificationScheduler.sync(context, parsed);
        PrayerNotchController.notifySnapshotChanged(context);
    }

    private static void preservePreviousLivePrayerDay(JSONObject incoming, JSONObject stored) throws JSONException {
        JSONObject next = incoming.optJSONObject("livePrayerSchedule");
        JSONObject previous = stored == null ? null : stored.optJSONObject("livePrayerSchedule");
        if (next == null || previous == null || next.optInt("version") != 1
                || previous.optInt("version") != 1) return;
        String key = next.optString("configurationKey", "");
        String zoneId = next.optString("timeZone", "");
        if (key.isEmpty() || zoneId.isEmpty() || !key.equals(previous.optString("configurationKey", ""))
                || !zoneId.equals(previous.optString("timeZone", ""))) return;
        JSONArray nextDays = next.optJSONArray("days");
        JSONArray previousDays = previous.optJSONArray("days");
        if (nextDays == null || previousDays == null || nextDays.length() >= 400) return;
        Calendar date = Calendar.getInstance(TimeZone.getTimeZone(zoneId), Locale.US);
        date.add(Calendar.DAY_OF_MONTH, -1);
        String yesterday = String.format(Locale.US, "%04d-%02d-%02d", date.get(Calendar.YEAR),
                date.get(Calendar.MONTH) + 1, date.get(Calendar.DAY_OF_MONTH));
        for (int i = 0; i < nextDays.length(); i++) {
            JSONObject day = nextDays.optJSONObject(i);
            if (day != null && yesterday.equals(day.optString("date", ""))) return;
        }
        JSONObject previousDay = null;
        for (int i = 0; i < previousDays.length(); i++) {
            JSONObject day = previousDays.optJSONObject(i);
            if (day != null && yesterday.equals(day.optString("date", ""))) {
                if (previousDay != null) return;
                previousDay = day;
            }
        }
        if (previousDay != null) nextDays.put(new JSONObject(previousDay.toString()));
    }

    static synchronized void saveRemoteSnapshotIfAbsent(Context context, String snapshot) throws JSONException {
        // A local publish may have arrived while the legacy request was in flight.
        if (loadSnapshot(context) == null) saveSnapshot(context, snapshot);
    }

    static JSONObject loadSnapshot(Context context) {
        String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(SNAPSHOT, null);
        if (raw == null) return null;
        try {
            return new JSONObject(raw);
        } catch (JSONException ignored) {
            return null;
        }
    }

    static boolean isPrayerTimesConfigured(JSONObject snapshot) {
        return snapshot != null
                && snapshot.optBoolean("prayerTimesEnabled", false)
                && snapshot.optJSONArray("timings") != null
                && snapshot.optJSONArray("timings").length() > 0;
    }

    static boolean hasPrayerNotificationsEnabled(Context context) {
        JSONObject snapshot = loadSnapshot(context);
        return isPrayerTimesConfigured(snapshot) && snapshot.optBoolean("notificationsEnabled", false);
    }
}
