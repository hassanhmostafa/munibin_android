package com.motoonai.twa;

import android.content.Context;
import android.graphics.Color;

import org.json.JSONObject;

/** Validated opaque colors resolved from the app's exact selected background palette. */
public final class PrayerWidgetTheme {
    private static final String PREFS = "munibin_prayer_display";
    private static final String THEME = "widget_theme";
    private static String cachedStoredTheme;
    private static Palette cachedStoredPalette;

    public static final class Palette {
        public final String theme, backgroundPalette;
        public final boolean light;
        public final int backgroundStart, backgroundEnd, accent, primary, secondary, border, highlight, iqama, iconBackground;
        private final String signature;

        private Palette(JSONObject value) {
            theme = value != null && "light".equals(value.optString("theme", "dark")) ? "light" : "dark";
            light = "light".equals(theme);
            String id = value == null ? "" : value.optString("backgroundPalette", "");
            backgroundPalette = knownPalette(id) ? id : light ? "cream" : "olive";
            JSONObject colors = value == null ? null : value.optJSONObject("themeColors");
            String[] fallback = fallbackColors(backgroundPalette, light);
            backgroundStart = color(colors, "backgroundStart", fallback[0]);
            backgroundEnd = color(colors, "backgroundEnd", fallback[1]);
            accent = color(colors, "accent", fallback[2]);
            primary = color(colors, "primary", fallback[3]);
            secondary = color(colors, "secondary", fallback[4]);
            border = color(colors, "border", fallback[5]);
            highlight = Color.parseColor(light ? "#12642F" : "#85F0AB");
            iqama = Color.parseColor(light ? "#A32335" : "#FFB4AE");
            iconBackground = color(colors, "iconBackground", fallback[6]);
            signature = json().toString();
        }

        public String signature() { return signature; }

        private JSONObject json() {
            JSONObject result = new JSONObject();
            JSONObject colors = new JSONObject();
            try {
                result.put("theme", theme);
                result.put("backgroundPalette", backgroundPalette);
                colors.put("backgroundStart", hex(backgroundStart));
                colors.put("backgroundEnd", hex(backgroundEnd));
                colors.put("accent", hex(accent));
                colors.put("primary", hex(primary));
                colors.put("secondary", hex(secondary));
                colors.put("border", hex(border));
                colors.put("highlight", hex(highlight));
                colors.put("iqama", hex(iqama));
                colors.put("iconBackground", hex(iconBackground));
                result.put("themeColors", colors);
            } catch (Exception ignored) { }
            return result;
        }
    }

    private PrayerWidgetTheme() {}

    public static synchronized Palette read(Context context, JSONObject snapshot) {
        String stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(THEME, null);
        if (stored != null) {
            // Display ticks re-read the preference so theme changes take effect, but an
            // unchanged immutable palette needs no JSON parsing/color formatting.
            if (stored.equals(cachedStoredTheme) && cachedStoredPalette != null) return cachedStoredPalette;
            try {
                Palette palette = new Palette(new JSONObject(stored));
                cachedStoredTheme = stored;
                cachedStoredPalette = palette;
                return palette;
            } catch (Exception ignored) { }
        }
        return new Palette(snapshot);
    }

    public static synchronized void set(Context context, String serialized) throws org.json.JSONException {
        Palette palette = new Palette(new JSONObject(serialized));
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(THEME, palette.signature()).apply();
    }

    static synchronized void initializeFromSnapshot(Context context, JSONObject snapshot) {
        if (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(THEME)) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(THEME, new Palette(snapshot).signature()).apply();
    }

    private static int color(JSONObject colors, String key, String fallback) {
        String raw = colors == null ? "" : colors.optString(key, "");
        return Color.parseColor(raw.matches("#[0-9a-fA-F]{6}") ? raw : fallback);
    }

    private static String hex(int color) {
        return String.format(java.util.Locale.US, "#%06X", color & 0x00ffffff);
    }

    private static boolean knownPalette(String value) {
        return "cream".equals(value) || "gold".equals(value) || "olive".equals(value)
                || "wood".equals(value) || "blue".equals(value) || "monochrome".equals(value);
    }

    private static String[] fallbackColors(String palette, boolean light) {
        if ("gold".equals(palette)) return light
                ? new String[]{"#FFF5CF", "#FFEDB6", "#B45309", "#3D1506", "#816043", "#E7CA85", "#F6DAA1"}
                : new String[]{"#3A2905", "#543B08", "#F59E0B", "#FFFFFF", "#C3BBA9", "#88681D", "#6E4F0B"};
        if ("olive".equals(palette)) return light
                ? new String[]{"#F1F3DF", "#E6EBCC", "#B45309", "#3D1506", "#78604B", "#C0CBA5", "#E0D9B4"}
                : new String[]{"#263020", "#3A482D", "#F59E0B", "#FFFFFF", "#BABFB6", "#5E6F44", "#595A29"};
        if ("wood".equals(palette)) return light
                ? new String[]{"#F5E9DC", "#EFDAC6", "#B45309", "#3D1506", "#7B5A49", "#D4B79F", "#E8CAAF"}
                : new String[]{"#352016", "#4B2D1C", "#F59E0B", "#FFFFFF", "#C0B6B0", "#785133", "#66441B"};
        if ("blue".equals(palette)) return light
                ? new String[]{"#E9F0F2", "#DCE7EA", "#B45309", "#3D1506", "#745E56", "#B7C8CD", "#D7D5CF"}
                : new String[]{"#1F2B30", "#2C3C43", "#F59E0B", "#FFFFFF", "#B5BBBD", "#475B63", "#4E503A"};
        if ("monochrome".equals(palette)) return light
                ? new String[]{"#FFFFFF", "#FAFAFA", "#000000", "#000000", "#575757", "#CDCDCD", "#EAEAEA"}
                : new String[]{"#000000", "#0F0F0F", "#FFFFFF", "#FFFFFF", "#ABABAB", "#3F3F3F", "#272727"};
        return light
                ? new String[]{"#FDF6E3", "#F9EDCE", "#B45309", "#3D1506", "#7F604C", "#E8C69D", "#F1DAB6"}
                : new String[]{"#3D1506", "#4D1D09", "#F59E0B", "#FFFFFF", "#C1B0A9", "#6F3E0D", "#68370C"};
    }
}
