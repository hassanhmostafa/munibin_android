package com.motoonai.twa;

import android.content.Context;

import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Display-only prayer clock preference. Persisted calendar clocks stay canonical HH:mm. */
public final class PrayerTimeFormat {
    private static final String PREFS = "munibin_prayer_display";
    private static final String MODE = "time_format";
    private static final Pattern CLOCK = Pattern.compile("^(\\d{1,2}):(\\d{2})(?:\\s*(AM|PM|ص|م))?$");

    private PrayerTimeFormat() {}

    public static String normalizeMode(String mode) {
        return "12h".equals(mode) ? "12h" : "24h";
    }

    public static String read(Context context) {
        return normalizeMode(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(MODE, "24h"));
    }

    public static synchronized void set(Context context, String mode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(MODE, normalizeMode(mode)).apply();
    }

    static synchronized void initializeFromSnapshot(Context context, String mode) {
        // Explicit bridge choices take precedence over an older publish already queued by the WebView.
        if (!context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(MODE)) set(context, mode);
    }

    public static String formatClock(Context context, String raw) {
        return formatClock(raw, read(context));
    }

    public static String formatClock(String raw, String mode) {
        if (raw == null) return "--:--";
        String value = latinDigits(raw).trim().toUpperCase(Locale.US);
        Matcher match = CLOCK.matcher(value);
        if (!match.matches()) return latinDigits(raw);
        int hour = Integer.parseInt(match.group(1));
        int minute = Integer.parseInt(match.group(2));
        String suffix = match.group(3);
        if (minute > 59) return latinDigits(raw);
        if (suffix != null) {
            if (hour < 1 || hour > 12) return latinDigits(raw);
            hour %= 12;
            if ("PM".equals(suffix) || "م".equals(suffix)) hour += 12;
        } else if (hour > 23) return latinDigits(raw);
        if ("24h".equals(normalizeMode(mode))) return String.format(Locale.US, "%02d:%02d", hour, minute);
        int displayHour = hour % 12;
        return String.format(Locale.US, "%d:%02d %s", displayHour == 0 ? 12 : displayHour, minute,
                hour < 12 ? "ص" : "م");
    }

    public static String formatMillis(Context context, long atMillis, TimeZone zone) {
        SimpleDateFormat formatter = new SimpleDateFormat("HH:mm", Locale.US);
        formatter.setTimeZone(zone == null ? TimeZone.getDefault() : zone);
        return formatClock(context, formatter.format(atMillis));
    }

    public static String latinDigits(String value) {
        if (value == null) return "";
        StringBuilder result = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char digit = value.charAt(index);
            if (digit >= '٠' && digit <= '٩') digit = (char) ('0' + digit - '٠');
            else if (digit >= '۰' && digit <= '۹') digit = (char) ('0' + digit - '۰');
            result.append(digit);
        }
        return result.toString();
    }
}
