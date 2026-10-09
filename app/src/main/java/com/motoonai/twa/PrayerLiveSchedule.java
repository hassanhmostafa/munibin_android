package com.motoonai.twa;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Dated prayer selection shared by the native notification and floating bar. */
final class PrayerLiveSchedule {
    static final String[] KEYS = {"fajr", "sunrise", "dhuhr", "asr", "maghrib", "isha"};

    static final class Event {
        final String key, label, time;
        final long atMillis, iqamaAtMillis;

        Event(String key, String label, String time, long atMillis, long iqamaAtMillis) {
            this.key = key;
            this.label = label;
            this.time = time;
            this.atMillis = atMillis;
            this.iqamaAtMillis = iqamaAtMillis;
        }
    }

    static final class State {
        final String targetKey, label, dayDate, hijriDate;
        final boolean isIqama, available;
        final long targetAtMillis, nextRefreshAtMillis;
        final TimeZone timeZone;
        final List<Event> dayEvents;
        final JSONObject displayTheme;

        State(Event target, boolean iqama, long refreshAt, String dayDate,
                TimeZone timeZone, List<Event> dayEvents, JSONObject displayTheme, String hijriDate) {
            this.available = target != null;
            this.targetKey = target == null ? "" : target.key;
            this.label = target == null ? "" : target.label;
            this.isIqama = target != null && iqama;
            this.targetAtMillis = target == null ? 0L : iqama ? target.iqamaAtMillis : target.atMillis;
            this.nextRefreshAtMillis = refreshAt;
            this.dayDate = dayDate;
            this.hijriDate = hijriDate;
            this.timeZone = timeZone;
            this.displayTheme = displayTheme;
            this.dayEvents = Collections.unmodifiableList(new ArrayList<>(dayEvents));
        }
    }

    private PrayerLiveSchedule() {}

    static State select(JSONObject snapshot, long now) {
        TimeZone zone = TimeZone.getDefault();
        JSONObject displayTheme = displayTheme(snapshot);
        JSONObject schedule = snapshot == null ? null : snapshot.optJSONObject("livePrayerSchedule");
        if (schedule != null) {
            String id = schedule.optString("timeZone", "");
            if (isValidTimeZone(id)) zone = TimeZone.getTimeZone(id);
            else return unavailable(now, zone, Collections.<Event>emptyList(), displayTheme);
        }
        if (snapshot == null || !snapshot.optBoolean("prayerTimesEnabled", false)
                || schedule == null || schedule.optInt("version", 0) != 1) {
            return unavailable(now, zone, Collections.<Event>emptyList(), displayTheme);
        }

        Calendar today = Calendar.getInstance(zone, Locale.US);
        today.setTimeInMillis(now);
        String todayDate = date(today.getTimeInMillis(), zone);
        Calendar adjacent = (Calendar) today.clone();
        adjacent.add(Calendar.DATE, -1);
        String yesterdayDate = date(adjacent.getTimeInMillis(), zone);
        adjacent.add(Calendar.DATE, 2);
        String tomorrowDate = date(adjacent.getTimeInMillis(), zone);
        JSONArray days = schedule.optJSONArray("days");
        List<Event> all = new ArrayList<>();
        List<Event> current = new ArrayList<>();
        boolean foundToday = false;
        if (days != null) {
            // The annual offline cache is scanned once at a boundary. Only three dates are parsed.
            for (int index = 0; index < Math.min(400, days.length()); index++) {
                JSONObject day = days.optJSONObject(index);
                if (day == null) continue;
                String dayDate = day.optString("date", "");
                if (!dayDate.equals(todayDate) && !dayDate.equals(yesterdayDate)
                        && !dayDate.equals(tomorrowDate)) continue;
                List<Event> events = parseEvents(day.optJSONArray("events"), dayDate, zone);
                if (dayDate.equals(todayDate) && !foundToday) {
                    current.addAll(events);
                    foundToday = true;
                }
                all.addAll(events);
            }
        }
        if (!foundToday || current.isEmpty()) return unavailable(now, zone, current, displayTheme);
        Comparator<Event> chronological = new Comparator<Event>() {
            @Override public int compare(Event first, Event second) {
                return Long.compare(first.atMillis, second.atMillis);
            }
        };
        Collections.sort(all, chronological);
        Collections.sort(current, chronological);
        long refresh = nextMidnight(now, zone);
        Event activeIqama = null;
        Event nextPrayer = null;
        for (Event event : all) {
            if (event.atMillis > now) {
                refresh = Math.min(refresh, event.atMillis);
                if (nextPrayer == null) nextPrayer = event;
            }
            if (event.iqamaAtMillis > now) refresh = Math.min(refresh, event.iqamaAtMillis);
            // Keep the earlier prayer highlighted until its iqama has finished, even if
            // a later prayer time has already begun (for example Asr crossing Maghrib).
            if (activeIqama == null && event.atMillis <= now && event.iqamaAtMillis > now) {
                activeIqama = event;
            }
        }
        Event target = activeIqama != null ? activeIqama : nextPrayer;
        String displayDate = target == null ? todayDate : date(target.atMillis, zone);
        List<Event> displayEvents = new ArrayList<>();
        // The timetable follows the prayer being counted down to. Keep yesterday's
        // Isha day while its iqama spans midnight; roll to tomorrow at its finish.
        for (Event event : all) {
            if (displayDate.equals(date(event.atMillis, zone))) displayEvents.add(event);
        }
        String hijriDate = "";
        if (days != null) for (int index = 0; index < Math.min(400, days.length()); index++) {
            JSONObject day = days.optJSONObject(index);
            if (day != null && displayDate.equals(day.optString("date", ""))) {
                hijriDate = day.optString("hijriDate", "");
                break;
            }
        }
        return new State(target, activeIqama != null,
                refresh, displayDate, zone, displayEvents, displayTheme, hijriDate);
    }

    private static List<Event> parseEvents(JSONArray values, String expectedDate, TimeZone zone) {
        List<Event> result = new ArrayList<>();
        if (values == null) return result;
        for (int index = 0; index < Math.min(values.length(), 12); index++) {
            JSONObject value = values.optJSONObject(index);
            if (value == null) continue;
            String key = value.optString("key", "").toLowerCase(Locale.US);
            if (!isKnownKey(key)) continue;
            long at = value.optLong("at", 0L);
            String time = value.optString("time", "");
            if (at <= 0L || !date(at, zone).equals(expectedDate) || !time.matches("[0-2][0-9]:[0-5][0-9]")
                    || !time.equals(time(at, zone))) continue;
            boolean duplicate = false;
            for (Event previous : result) if (previous.key.equals(key)) duplicate = true;
            if (duplicate) continue;
            long iqama = value.optLong("iqamaAt", 0L);
            if ("sunrise".equals(key) || iqama <= at) iqama = 0L;
            String label = value.optString("label", "").trim();
            result.add(new Event(key, label.isEmpty() ? defaultLabel(key) : label, time, at, iqama));
        }
        return result;
    }

    private static State unavailable(long now, TimeZone zone, List<Event> current, JSONObject displayTheme) {
        return new State(null, false, nextMidnight(now, zone), date(now, zone), zone, current, displayTheme, "");
    }

    private static JSONObject displayTheme(JSONObject snapshot) {
        // The ticker retains only the display fields, never the full annual JSON.
        JSONObject result = new JSONObject();
        try {
            result.put("theme", snapshot == null ? "dark" : snapshot.optString("theme", "dark"));
            String palette = snapshot == null ? "" : snapshot.optString("backgroundPalette", "");
            if (!palette.isEmpty()) result.put("backgroundPalette", palette);
            if (snapshot != null && snapshot.optJSONObject("themeColors") != null) {
                result.put("themeColors", snapshot.optJSONObject("themeColors"));
            }
        } catch (JSONException ignored) { }
        return result;
    }

    /** Display the configured minutes after adhan, never a second wall clock. */
    static String iqamaOffset(Event event) {
        if (event == null || "sunrise".equals(event.key) || event.atMillis <= 0L
                || event.iqamaAtMillis <= event.atMillis) return "";
        long delay = event.iqamaAtMillis - event.atMillis;
        if (delay % 60_000L != 0L) return "";
        long minutes = delay / 60_000L;
        return minutes > 0L ? "+" + minutes : "";
    }

    /** Offset annotations inherit their prayer clock's hue at a quieter opacity. */
    static int mutedClockColor(int color) {
        return (color & 0x00FFFFFF) | (Math.round(((color >>> 24) & 0xFF) * 0.72f) << 24);
    }

    static String time(long at, TimeZone zone) {
        SimpleDateFormat formatter = new SimpleDateFormat("HH:mm", Locale.US);
        formatter.setTimeZone(zone);
        return formatter.format(at);
    }

    private static String date(long at, TimeZone zone) {
        SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        formatter.setTimeZone(zone);
        return formatter.format(at);
    }

    private static long nextMidnight(long now, TimeZone zone) {
        Calendar calendar = Calendar.getInstance(zone, Locale.US);
        calendar.setTimeInMillis(now);
        calendar.add(Calendar.DATE, 1);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    private static boolean isValidTimeZone(String id) {
        if ("UTC".equals(id) || "GMT".equals(id)) return true;
        if (id.isEmpty()) return false;
        for (String available : TimeZone.getAvailableIDs()) if (id.equals(available)) return true;
        return false;
    }

    private static boolean isKnownKey(String key) {
        for (String known : KEYS) if (known.equals(key)) return true;
        return false;
    }

    static String defaultLabel(String key) {
        if ("fajr".equals(key)) return "الفجر";
        if ("sunrise".equals(key)) return "الشروق";
        if ("dhuhr".equals(key)) return "الظهر";
        if ("asr".equals(key)) return "العصر";
        if ("maghrib".equals(key)) return "المغرب";
        if ("isha".equals(key)) return "العشاء";
        return "الصلاة";
    }
}
