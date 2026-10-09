package com.motoonai.twa;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.RemoteViews;
import android.util.TypedValue;

import androidx.core.content.res.ResourcesCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PrayerWidgetProvider extends AppWidgetProvider {
    public static final String ACTION_REFRESH = "com.motoonai.twa.action.REFRESH_PRAYER_WIDGET";

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final int BOUNDARY_REFRESH_REQUEST_CODE = 7398;
    private static final Pattern TIME_PATTERN = Pattern.compile("(\\d{1,2}):(\\d{2})");

    // Visual order is left -> right. Fajr therefore remains on the far right.
    private static final String[] PRAYER_KEYS = {
            "isha", "maghrib", "asr", "dhuhr", "fajr"
    };
    private static final String[] PRAYER_DEFAULT_LABELS = {
            "العشاء", "المغرب", "العصر", "الظهر", "الفجر"
    };
    private static final int[] PRAYER_LABEL_IDS = {
            R.id.prayer_widget_isha_label,
            R.id.prayer_widget_maghrib_label,
            R.id.prayer_widget_asr_label,
            R.id.prayer_widget_dhuhr_label,
            R.id.prayer_widget_fajr_label
    };
    private static final int[] PRAYER_TIME_IDS = {
            R.id.prayer_widget_isha_time,
            R.id.prayer_widget_maghrib_time,
            R.id.prayer_widget_asr_time,
            R.id.prayer_widget_dhuhr_time,
            R.id.prayer_widget_fajr_time
    };
    private static final int[] PRAYER_COLUMN_IDS = {
            R.id.prayer_widget_isha_column,
            R.id.prayer_widget_maghrib_column,
            R.id.prayer_widget_asr_column,
            R.id.prayer_widget_dhuhr_column,
            R.id.prayer_widget_fajr_column
    };
    private static final int[] PRAYER_UNDERLINE_IDS = {
            R.id.prayer_widget_isha_underline,
            R.id.prayer_widget_maghrib_underline,
            R.id.prayer_widget_asr_underline,
            R.id.prayer_widget_dhuhr_underline,
            R.id.prayer_widget_fajr_underline
    };
    private static final int[] PRAYER_OFFSET_IDS = {
            R.id.prayer_widget_isha_offset,
            R.id.prayer_widget_maghrib_offset,
            R.id.prayer_widget_asr_offset,
            R.id.prayer_widget_dhuhr_offset,
            R.id.prayer_widget_fajr_offset
    };
    private static final String[] LOCK_PRAYER_KEYS = {"fajr", "sunrise", "dhuhr", "asr", "maghrib", "isha"};
    private static final int[] LOCK_LABEL_IDS = {
            R.id.prayer_lock_fajr_label, R.id.prayer_lock_sunrise_label, R.id.prayer_lock_dhuhr_label,
            R.id.prayer_lock_asr_label, R.id.prayer_lock_maghrib_label, R.id.prayer_lock_isha_label
    };
    private static final int[] LOCK_TIME_IDS = {
            R.id.prayer_lock_fajr_time, R.id.prayer_lock_sunrise_time, R.id.prayer_lock_dhuhr_time,
            R.id.prayer_lock_asr_time, R.id.prayer_lock_maghrib_time, R.id.prayer_lock_isha_time
    };
    private static final int[] LOCK_COUNTDOWN_IDS = {
            R.id.prayer_lock_fajr_countdown, R.id.prayer_lock_sunrise_countdown, R.id.prayer_lock_dhuhr_countdown,
            R.id.prayer_lock_asr_countdown, R.id.prayer_lock_maghrib_countdown, R.id.prayer_lock_isha_countdown
    };
    private static final int[] LOCK_IQAMA_IDS = {
            R.id.prayer_lock_fajr_iqama, R.id.prayer_lock_sunrise_iqama, R.id.prayer_lock_dhuhr_iqama,
            R.id.prayer_lock_asr_iqama, R.id.prayer_lock_maghrib_iqama, R.id.prayer_lock_isha_iqama
    };
    private static final int[] LOCK_ICON_IDS = {
            R.id.prayer_lock_fajr_icon, R.id.prayer_lock_sunrise_icon, R.id.prayer_lock_dhuhr_icon,
            R.id.prayer_lock_asr_icon, R.id.prayer_lock_maghrib_icon, R.id.prayer_lock_isha_icon
    };

    private static final class CountdownTarget {
        final String key;
        final String label;
        final String time;
        final long atMillis;
        final boolean isIqama;

        CountdownTarget(String key, String label, String time, long atMillis, boolean isIqama) {
            this.key = key;
            this.label = label;
            this.time = time;
            this.atMillis = atMillis;
            this.isIqama = isIqama;
        }
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        for (int widgetId : appWidgetIds) {
            render(context, manager, widgetId);
        }
        refreshInBackground(context, this.goAsync());
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int appWidgetId, Bundle options) {
        super.onAppWidgetOptionsChanged(context, manager, appWidgetId, options);
        render(context, manager, appWidgetId);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (ACTION_REFRESH.equals(intent.getAction())) {
            renderStoredSnapshot(context);
            refreshInBackground(context, this.goAsync());
        }
    }

    static void renderStoredSnapshot(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);

        ComponentName regular = new ComponentName(context, PrayerWidgetProvider.class);
        for (int widgetId : manager.getAppWidgetIds(regular)) {
            render(context, manager, widgetId);
        }

        ComponentName compact = new ComponentName(context, PrayerLockWidgetProvider.class);
        for (int widgetId : manager.getAppWidgetIds(compact)) {
            renderCompact(context, manager, widgetId);
        }
    }

    static void refreshInBackground(Context context, PendingResult pendingResult) {
        Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            try {
                // The native app is the source of truth for whether prayer times are
                // configured. Never revive an old server/device-id snapshot when the
                // user has not enabled prayer times in the app.
                renderStoredSnapshot(appContext);
            } finally {
                pendingResult.finish();
            }
        });
    }

    static void render(Context context, AppWidgetManager manager, int widgetId) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.prayer_widget);
        JSONObject snapshot = PrayerWidgetStore.loadSnapshot(context);
        boolean configured = PrayerWidgetStore.isPrayerTimesConfigured(snapshot);
        long now = System.currentTimeMillis();
        PrayerLiveSchedule.State displayState = datedState(snapshot, configured, now);
        JSONArray timings = displayTimings(snapshot, displayState);
        bindClockSpacing(context, manager, views, widgetId, timings);

        PrayerWidgetTheme.Palette palette = PrayerWidgetTheme.read(context, snapshot);
        int primary = palette.primary;
        int secondary = palette.secondary;
        int accent = palette.accent;
        int highlight = palette.highlight;
        int divider = palette.border;
        views.setImageViewBitmap(R.id.prayer_widget_theme_background,
                PrayerWidgetArtwork.background(context, manager, widgetId, palette, false, configured));
        views.setImageViewBitmap(R.id.prayer_widget_sunrise_shell,
                PrayerWidgetArtwork.iconShell(context, palette.iconBackground, false));
        views.setImageViewBitmap(R.id.prayer_widget_empty_refresh_shell,
                PrayerWidgetArtwork.iconShell(context, palette.iconBackground, false));
        views.setTextColor(R.id.prayer_widget_empty_refresh, accent);
        views.setInt(R.id.prayer_widget_divider, "setBackgroundColor", divider);

        views.setTextColor(R.id.prayer_widget_brand, accent);
        views.setTextColor(R.id.prayer_widget_hijri, secondary);
        views.setTextColor(R.id.prayer_widget_next_label, primary);
        views.setTextColor(R.id.prayer_widget_countdown, highlight);
        views.setTextColor(R.id.prayer_widget_empty_note, secondary);

        views.setTextViewText(
                R.id.prayer_widget_hijri,
                !configured
                        ? "مواقيت منيبين"
                        : displayDate(snapshot, displayState, "مواقيت منيبين")
        );

        CountdownTarget target = configured ? displayTarget(snapshot, displayState, now) : null;
        int countdownColor = target != null && target.isIqama ? palette.iqama : palette.highlight;
        views.setTextColor(R.id.prayer_widget_countdown, countdownColor);
        String targetLabel = target == null || target.label.isEmpty()
                ? "الصلاة القادمة"
                : target.label;
        views.setTextViewText(R.id.prayer_widget_next_label,
                target != null && target.isIqama ? "إقامة " + targetLabel + " بعد" : targetLabel + " بعد");

        long countdownMillis = target == null
                ? 0L
                : Math.max(0L, target.atMillis - now);

        if (countdownMillis > 0) {
            views.setViewVisibility(R.id.prayer_widget_countdown_row, View.VISIBLE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                views.setChronometer(
                        R.id.prayer_widget_countdown,
                        SystemClock.elapsedRealtime() + countdownMillis,
                        "%s",
                        true
                );
                views.setChronometerCountDown(R.id.prayer_widget_countdown, true);
            } else {
                views.setTextViewText(R.id.prayer_widget_countdown, latinCountdown(countdownMillis));
            }
        } else {
            views.setViewVisibility(R.id.prayer_widget_countdown_row, View.GONE);
        }

        if (!configured) {
            views.setViewVisibility(R.id.prayer_widget_next_label, View.GONE);
            views.setViewVisibility(R.id.prayer_widget_divider, View.GONE);
            views.setViewVisibility(R.id.prayer_widget_timings_row, View.GONE);
            views.setViewVisibility(R.id.prayer_widget_sunrise_strip, View.GONE);
            views.setViewVisibility(R.id.prayer_widget_empty_note, View.VISIBLE);
            views.setViewVisibility(R.id.prayer_widget_empty_refresh, View.VISIBLE);
            views.setViewVisibility(R.id.prayer_widget_empty_refresh_container, View.VISIBLE);
        } else {
            views.setViewVisibility(R.id.prayer_widget_next_label, View.VISIBLE);
            views.setViewVisibility(R.id.prayer_widget_divider, View.VISIBLE);
            views.setViewVisibility(R.id.prayer_widget_timings_row, View.VISIBLE);
            views.setViewVisibility(R.id.prayer_widget_sunrise_strip, View.VISIBLE);
            views.setViewVisibility(R.id.prayer_widget_empty_note, View.GONE);
            views.setViewVisibility(R.id.prayer_widget_empty_refresh, View.GONE);
            views.setViewVisibility(R.id.prayer_widget_empty_refresh_container, View.GONE);
            bindTimings(
                    context,
                    views,
                    snapshot,
                    timings,
                    displayState,
                    target,
                    primary,
                    highlight
            );
        }

        // Refresh at each prayer/iqama boundary so the active row stays on the
        // prayer through iqama, then moves to the next scheduled prayer.
        scheduleBoundaryRefresh(context, target == null ? 0L : target.atMillis);

        Intent openPrayer = new Intent(context, LauncherActivity.class)
                .setData(Uri.parse("https://munibin.com/prayer-times"));
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                context,
                widgetId,
                openPrayer,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        views.setOnClickPendingIntent(R.id.prayer_widget_root, openPendingIntent);

        views.setOnClickPendingIntent(R.id.prayer_widget_empty_refresh, openPendingIntent);
        views.setOnClickPendingIntent(R.id.prayer_widget_empty_note, openPendingIntent);

        manager.updateAppWidget(widgetId, views);
    }

    private static void bindClockSpacing(Context context, AppWidgetManager manager, RemoteViews views,
                                         int widgetId, JSONArray timings) {
        // Five columns have equal width in both formats. Never retain the former
        // six-column padding when a launcher reapplies a new snapshot in place.
        float density = context.getResources().getDisplayMetrics().density;
        int horizontal = Math.round(8f * density);
        views.setViewPadding(R.id.prayer_widget_content, horizontal,
                Math.round(7f * density), horizontal, Math.round(5f * density));
        for (int id : PRAYER_COLUMN_IDS) views.setViewPadding(id, 0, 0, 0, 0);

        Bundle options = manager.getAppWidgetOptions(widgetId);
        int widthDp = options == null ? 300 : options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 300);
        if (widthDp <= 0) widthDp = 300;
        // Read the actual old/resized host width as well. Updating the provider's
        // minimum size does not force launchers to enlarge already placed widgets.
        float contentWidth = Math.max(1f, widthDp - 16f);
        float columnWidth = contentWidth / 5f;
        Typeface medium = Typeface.create("sans-serif-medium", Typeface.BOLD);
        Typeface digits = Typeface.DEFAULT;
        try {
            Typeface bundled = ResourcesCompat.getFont(context, R.font.widget_latin_digits);
            if (bundled != null) digits = bundled;
        } catch (Exception ignored) { }
        fitWidgetText(context, views, R.id.prayer_widget_countdown, "23:59:59", 29f,
                contentWidth * 3f / 5f, digits);
        fitWidgetText(context, views, R.id.prayer_widget_next_label, "", 15f, 0f, medium);
        fitWidgetText(context, views, R.id.prayer_widget_brand, "", 14f, 0f, medium);
        fitWidgetText(context, views, R.id.prayer_widget_hijri, "", 10f, 0f, medium);
        fitWidgetText(context, views, R.id.prayer_widget_sunrise_label, "", 12f, 0f, medium);
        fitWidgetText(context, views, R.id.prayer_widget_sunrise_time, "", 13f, 0f, medium);
        for (int slot = 0; slot < PRAYER_KEYS.length; slot++) {
            JSONObject item = findPrayer(timings, PRAYER_KEYS[slot]);
            String time = item == null ? "--:--" : widgetClock(context, item.optString("time", "--:--"));
            String label = item == null ? PRAYER_DEFAULT_LABELS[slot]
                    : item.optString("label", PRAYER_DEFAULT_LABELS[slot]);
            fitWidgetText(context, views, PRAYER_LABEL_IDS[slot], label, 12f, columnWidth, medium);
            fitWidgetText(context, views, PRAYER_TIME_IDS[slot], time, 15f, columnWidth, medium);
            fitWidgetText(context, views, PRAYER_OFFSET_IDS[slot], "", 11f, 0f, digits);
        }
    }

    private static void fitWidgetText(Context context, RemoteViews views, int id, String text,
                                       float sizeSp, float widthDp, Typeface typeface) {
        android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        // A launcher card has finite space; permit a modest accessibility increase
        // without letting a large system font scale clip its timers or bottom row.
        float sizePx = sizeSp * Math.min(metrics.scaledDensity, metrics.density * 1.1f);
        if (widthDp > 0f && !text.isEmpty()) {
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setTypeface(typeface);
            paint.setTextSize(sizePx);
            float measured = paint.measureText(text);
            float available = Math.max(1f, widthDp * metrics.density - 2f * metrics.density);
            if (measured > available) sizePx *= available / measured;
        }
        views.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_PX, sizePx);
    }

    static void renderCompact(Context context, AppWidgetManager manager, int widgetId) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.prayer_lock_widget);
        JSONObject snapshot = PrayerWidgetStore.loadSnapshot(context);
        boolean configured = PrayerWidgetStore.isPrayerTimesConfigured(snapshot);
        long now = System.currentTimeMillis();
        PrayerLiveSchedule.State displayState = datedState(snapshot, configured, now);

        PrayerWidgetTheme.Palette palette = PrayerWidgetTheme.read(context, snapshot);
        int primary = palette.primary;
        int secondary = palette.secondary;
        int highlight = palette.highlight;
        views.setImageViewBitmap(R.id.prayer_lock_widget_theme_background,
                PrayerWidgetArtwork.background(context, manager, widgetId, palette, true, configured));
        views.setTextColor(R.id.prayer_lock_widget_name, primary);
        views.setTextColor(R.id.prayer_lock_widget_date, secondary);
        views.setTextColor(R.id.prayer_lock_widget_empty_note, secondary);
        views.setTextViewText(R.id.prayer_lock_widget_name, "منيبين");
        views.setTextViewText(R.id.prayer_lock_widget_date, configured
                ? displayDate(snapshot, displayState, "مواقيت الصلاة") : "مواقيت الصلاة");

        CountdownTarget target = configured ? displayTarget(snapshot, displayState, now) : null;
        int countdownColor = target != null && target.isIqama ? palette.iqama : palette.highlight;
        views.setViewVisibility(R.id.prayer_lock_widget_list, configured ? View.VISIBLE : View.GONE);
        views.setViewVisibility(R.id.prayer_lock_widget_empty_note, configured ? View.GONE : View.VISIBLE);
        JSONArray timings = configured ? displayTimings(snapshot, displayState) : null;
        long countdownMillis = target == null ? 0L : Math.max(0L, target.atMillis - now);
        for (int slot = 0; slot < LOCK_PRAYER_KEYS.length; slot++) {
            String key = LOCK_PRAYER_KEYS[slot];
            JSONObject item = findPrayer(timings, key);
            boolean active = target != null && key.equals(target.key);
            int color = active ? highlight : primary;
            views.setTextViewText(LOCK_LABEL_IDS[slot], item == null ? defaultPrayerLabel(key)
                    : item.optString("label", defaultPrayerLabel(key)));
            views.setTextViewText(LOCK_TIME_IDS[slot], item == null ? "--:--"
                    : widgetClock(context, item.optString("time", "--:--")));
            views.setTextColor(LOCK_LABEL_IDS[slot], color);
            views.setTextColor(LOCK_TIME_IDS[slot], color);
            views.setTextColor(LOCK_COUNTDOWN_IDS[slot], countdownColor);
            // Only the countdown changes to red during iqama. Prayer clocks,
            // names and this phase label retain their ordinary widget colors.
            views.setTextColor(LOCK_IQAMA_IDS[slot], primary);
            views.setInt(LOCK_ICON_IDS[slot], "setColorFilter", color);
            views.setViewVisibility(LOCK_IQAMA_IDS[slot], active && target.isIqama ? View.VISIBLE : View.GONE);
            // Stop the old row's timer too; an iqama transition keeps the same
            // prayer row, while the following prayer gets a fresh countdown.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                views.setChronometer(LOCK_COUNTDOWN_IDS[slot],
                        SystemClock.elapsedRealtime() + countdownMillis, "%s", active && countdownMillis > 0L);
                views.setChronometerCountDown(LOCK_COUNTDOWN_IDS[slot], true);
            } else if (active) {
                views.setTextViewText(LOCK_COUNTDOWN_IDS[slot], latinCountdown(countdownMillis));
            }
            views.setViewVisibility(LOCK_COUNTDOWN_IDS[slot], active ? View.VISIBLE : View.GONE);
        }

        // Use the exact same boundary alarm logic as the full Android prayer widget.
        // The alarm broadcasts to PrayerWidgetProvider, whose refresh path redraws
        // both full and compact widgets from the same stored snapshot.
        scheduleBoundaryRefresh(context, target == null ? 0L : target.atMillis);

        Intent openPrayer = new Intent(context, LauncherActivity.class)
                .setData(Uri.parse("https://munibin.com/prayer-times"));
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                context,
                widgetId + 100000,
                openPrayer,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        views.setOnClickPendingIntent(R.id.prayer_lock_widget_root, openPendingIntent);

        manager.updateAppWidget(widgetId, views);
    }

    private static PrayerLiveSchedule.State datedState(JSONObject snapshot, boolean configured, long now) {
        return configured && snapshot.optJSONObject("livePrayerSchedule") != null
                ? PrayerLiveSchedule.select(snapshot, now) : null;
    }

    private static CountdownTarget displayTarget(JSONObject snapshot, PrayerLiveSchedule.State state, long now) {
        if (state == null) return countdownTarget(snapshot, now);
        return state.available ? new CountdownTarget(state.targetKey, state.label,
                PrayerLiveSchedule.time(state.targetAtMillis, state.timeZone), state.targetAtMillis, state.isIqama) : null;
    }

    private static JSONArray displayTimings(JSONObject snapshot, PrayerLiveSchedule.State state) {
        if (state == null) return snapshot == null ? null : snapshot.optJSONArray("timings");
        JSONArray result = new JSONArray();
        for (PrayerLiveSchedule.Event event : state.dayEvents) {
            try {
                result.put(new JSONObject().put("key", event.key).put("label", event.label).put("time", event.time));
            } catch (org.json.JSONException ignored) { }
        }
        return result;
    }

    private static String displayDate(JSONObject snapshot, PrayerLiveSchedule.State state, String fallback) {
        // Per-day metadata is optional for older cached snapshots. Prefer a truthful
        // dated fallback over displaying the Hijri date from a different timetable.
        return toLatinDigits(state == null ? snapshot.optString("hijriDate", fallback)
                : state.hijriDate.isEmpty() ? state.dayDate : state.hijriDate);
    }

    private static CountdownTarget countdownTarget(JSONObject snapshot) {
        return countdownTarget(snapshot, System.currentTimeMillis());
    }

    // Keeping the prayer identity during iqama makes both widget layouts share
    // one active row until the iqama boundary, rather than clearing its accent.
    private static CountdownTarget countdownTarget(JSONObject snapshot, long now) {
        JSONArray timings = snapshot.optJSONArray("timings");
        if (timings == null || timings.length() == 0) return null;
        TimeZone zone = snapshotTimeZone(snapshot);
        JSONObject iqamaOffsets = snapshot.optJSONObject("iqamaOffsets");
        CountdownTarget best = null;
        CountdownTarget iqama = null;
        long earliestIqamaPrayer = Long.MAX_VALUE;
        for (int index = 0; index < timings.length(); index++) {
            JSONObject item = timings.optJSONObject(index);
            if (item == null) continue;
            String label = item.optString("label", "");
            String key = prayerKey(item.optString("key", ""), label);
            if (!isKnownPrayer(key)) continue;
            String displayLabel = label.isEmpty() ? defaultPrayerLabel(key) : label;
            String time = item.optString("time", "");
            int minutes = iqamaOffsets == null || "sunrise".equals(key) ? 0 : Math.max(0,
                    iqamaOffsets.optInt(key, iqamaOffsets.optInt(key.substring(0, 1).toUpperCase(Locale.US) + key.substring(1), 0)));
            // Include yesterday so an Isha iqama after midnight stays on Isha.
            // Calendar day shifts also preserve local clock times across DST.
            for (int day = -1; day <= 1; day++) {
                long prayerAt = prayerTimeMillis(item, now, day, zone);
                if (prayerAt <= 0L) continue;
                long iqamaAt = prayerAt + minutes * 60_000L;
                // Finish an earlier prayer's iqama before highlighting a later prayer.
                if (minutes > 0 && now >= prayerAt && now < iqamaAt && prayerAt < earliestIqamaPrayer) {
                    earliestIqamaPrayer = prayerAt;
                    iqama = new CountdownTarget(key, displayLabel, time, iqamaAt, true);
                }
                if (prayerAt > now && (best == null || prayerAt < best.atMillis)) {
                    best = new CountdownTarget(key, displayLabel, time, prayerAt, false);
                }
            }
        }
        return iqama != null ? iqama : best;
    }

    private static boolean isKnownPrayer(String key) {
        return "fajr".equals(key) || "sunrise".equals(key) || "dhuhr".equals(key)
                || "asr".equals(key) || "maghrib".equals(key) || "isha".equals(key);
    }

    private static TimeZone snapshotTimeZone(JSONObject snapshot) {
        try {
            JSONObject config = new JSONObject(snapshot.optString("notificationConfigKey", "{}"));
            String id = config.optString("timeZone", "");
            if (!id.isEmpty()) {
                TimeZone zone = TimeZone.getTimeZone(id);
                if (!"GMT".equals(zone.getID()) || "GMT".equals(id) || "UTC".equals(id)) return zone;
            }
        } catch (Exception ignored) { }
        return TimeZone.getDefault();
    }

    private static long prayerTimeMillis(JSONObject timing, long now, int dayOffset, TimeZone zone) {
        long published = PrayerLocalNotificationScheduler.parseTime(timing.optString("at", ""));
        if (published > 0L) {
            Calendar day = Calendar.getInstance(zone);
            day.setTimeInMillis(now);
            day.add(Calendar.DAY_OF_YEAR, dayOffset);
            Calendar event = Calendar.getInstance(zone);
            event.setTimeInMillis(published);
            if (day.get(Calendar.YEAR) == event.get(Calendar.YEAR)
                    && day.get(Calendar.DAY_OF_YEAR) == event.get(Calendar.DAY_OF_YEAR)) {
                return published;
            }
        }
        return clockTimeMillis(timing.optString("time", ""), now, dayOffset, zone);
    }


    private static int prayerIconResource(String key) {
        if ("fajr".equals(key) || "sunrise".equals(key)) return R.drawable.ic_prayer_sunrise;
        if ("dhuhr".equals(key)) return R.drawable.ic_prayer_sun_max;
        if ("asr".equals(key)) return R.drawable.ic_prayer_sun_haze;
        if ("maghrib".equals(key)) return R.drawable.ic_prayer_sunset;
        if ("isha".equals(key)) return R.drawable.ic_prayer_moon_stars;
        return R.drawable.ic_prayer_clock;
    }

    private static String defaultPrayerLabel(String key) {
        if ("fajr".equals(key)) return "الفجر";
        if ("sunrise".equals(key)) return "الشروق";
        if ("dhuhr".equals(key)) return "الظهر";
        if ("asr".equals(key)) return "العصر";
        if ("maghrib".equals(key)) return "المغرب";
        if ("isha".equals(key)) return "العشاء";
        return "الصلاة القادمة";
    }

    private static void bindTimings(
            Context context,
            RemoteViews views,
            JSONObject snapshot,
            JSONArray timings,
            PrayerLiveSchedule.State displayState,
            CountdownTarget target,
            int primary,
            int highlight
    ) {
        JSONObject iqamaOffsets = snapshot.optJSONObject("iqamaOffsets");
        String targetKey = target == null ? "" : target.key;

        JSONObject sunrise = findPrayer(timings, "sunrise");
        int sunriseColor = "sunrise".equals(targetKey) ? highlight : primary;
        views.setTextViewText(R.id.prayer_widget_sunrise_label, sunrise == null ? "الشروق"
                : sunrise.optString("label", "الشروق"));
        views.setTextViewText(R.id.prayer_widget_sunrise_time, sunrise == null ? "--:--"
                : widgetClock(context, sunrise.optString("time", "--:--")));
        views.setTextColor(R.id.prayer_widget_sunrise_label, sunriseColor);
        views.setTextColor(R.id.prayer_widget_sunrise_time, sunriseColor);
        views.setInt(R.id.prayer_widget_sunrise_icon, "setColorFilter", sunriseColor);

        for (int slot = 0; slot < PRAYER_KEYS.length; slot++) {
            JSONObject item = findPrayer(timings, PRAYER_KEYS[slot]);

            String label = item == null
                    ? PRAYER_DEFAULT_LABELS[slot]
                    : item.optString("label", PRAYER_DEFAULT_LABELS[slot]);
            String time = item == null ? "--:--" : widgetClock(context, item.optString("time", "--:--"));
            String itemKey = item == null
                    ? PRAYER_KEYS[slot]
                    : prayerKey(item.optString("key", ""), label);

            int textColor = !targetKey.isEmpty() && targetKey.equals(itemKey)
                    ? highlight
                    : primary;

            views.setTextViewText(PRAYER_LABEL_IDS[slot], label);
            views.setTextViewText(PRAYER_TIME_IDS[slot], time);
            views.setTextColor(PRAYER_LABEL_IDS[slot], textColor);
            views.setTextColor(PRAYER_TIME_IDS[slot], textColor);
            // Offsets are configured minutes after adhan, never another clock.
            // Reserve the row even when absent so all five prayer clocks align.
            String offset = item == null ? "" : displayState == null
                    ? iqamaOffsetText(iqamaOffsets, PRAYER_KEYS[slot])
                    : PrayerLiveSchedule.iqamaOffset(findDayEvent(displayState, PRAYER_KEYS[slot]));
            views.setTextViewText(PRAYER_OFFSET_IDS[slot], offset);
            views.setTextColor(PRAYER_OFFSET_IDS[slot], PrayerLiveSchedule.mutedClockColor(textColor));
            views.setViewVisibility(PRAYER_OFFSET_IDS[slot], offset.isEmpty() ? View.INVISIBLE : View.VISIBLE);
            views.setInt(PRAYER_UNDERLINE_IDS[slot], "setBackgroundColor", highlight);
            views.setViewVisibility(PRAYER_UNDERLINE_IDS[slot],
                    !targetKey.isEmpty() && targetKey.equals(itemKey) ? View.VISIBLE : View.INVISIBLE);
        }
    }

    private static PrayerLiveSchedule.Event findDayEvent(PrayerLiveSchedule.State state, String key) {
        for (PrayerLiveSchedule.Event event : state.dayEvents) if (key.equals(event.key)) return event;
        return null;
    }

    private static String iqamaOffsetText(JSONObject offsets, String key) {
        if (offsets == null) return "";
        int minutes = Math.max(0, offsets.optInt(key,
                offsets.optInt(key.substring(0, 1).toUpperCase(Locale.US) + key.substring(1), 0)));
        return minutes > 0 ? "+" + minutes : "";
    }

    private static String widgetClock(Context context, String raw) {
        // Keep the Arabic AM/PM suffix after the digits in RTL reading order.
        // This is display-only; snapshots and boundary calculations stay HH:mm.
        return "\u2067" + PrayerTimeFormat.formatClock(context, raw) + "\u2069";
    }

    private static JSONObject findPrayer(JSONArray timings, String wantedKey) {
        if (timings == null) return null;

        for (int index = 0; index < timings.length(); index++) {
            JSONObject item = timings.optJSONObject(index);
            if (item == null) continue;
            String key = prayerKey(item.optString("key", ""), item.optString("label", ""));
            if (wantedKey.equals(key)) return item;
        }
        return null;
    }

    private static String prayerKey(String rawKey, String label) {
        String key = rawKey == null ? "" : rawKey
                                           .trim()
                                           .toLowerCase(Locale.US)
                                           .replace("_", "")
                                           .replace("-", "");

        if ("zuhr".equals(key) || "zuhur".equals(key)) return "dhuhr";
        if ("sunrise".equals(key)
                || "shuruq".equals(key)
                || "shurooq".equals(key)
                || "shorouq".equals(key)
                || "shorooq".equals(key)) {
            return "sunrise";
        }
        if ("fajr".equals(key)
                || "dhuhr".equals(key)
                || "asr".equals(key)
                || "maghrib".equals(key)
                || "isha".equals(key)
                || "ishaa".equals(key)) {
            return "ishaa".equals(key) ? "isha" : key;
        }

        String value = label == null ? "" : label;
        if (value.contains("الفجر")) return "fajr";
        if (value.contains("الشروق") || value.contains("شروق")) return "sunrise";
        if (value.contains("الظهر")) return "dhuhr";
        if (value.contains("العصر")) return "asr";
        if (value.contains("المغرب")) return "maghrib";
        if (value.contains("العشاء")) return "isha";

        return key;
    }

    private static long clockTimeMillis(String rawTime, long now, int dayOffset, TimeZone zone) {
        if (rawTime == null || rawTime.trim().isEmpty()) return 0L;

        String value = toLatinDigits(rawTime).trim().toUpperCase(Locale.US);
        Matcher matcher = TIME_PATTERN.matcher(value);
        if (!matcher.find()) return 0L;

        int hour;
        int minute;
        try {
            hour = Integer.parseInt(matcher.group(1));
            minute = Integer.parseInt(matcher.group(2));
        } catch (Exception ignored) {
            return 0L;
        }

        boolean hasAm = value.contains("AM") || value.endsWith("ص");
        boolean hasPm = value.contains("PM") || value.endsWith("م");
        if (hasAm || hasPm) {
            if (hour < 1 || hour > 12) return 0L;
            if (hasAm && hour == 12) hour = 0;
            if (hasPm && hour != 12) hour += 12;
        }
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) return 0L;

        Calendar calendar = Calendar.getInstance(zone);
        calendar.setTimeInMillis(now);
        calendar.add(Calendar.DAY_OF_YEAR, dayOffset);
        calendar.set(Calendar.HOUR_OF_DAY, hour);
        calendar.set(Calendar.MINUTE, minute);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);

        return calendar.getTimeInMillis();
    }

    private static String latinCountdown(long millis) {
        long seconds = millis <= 0L ? 0L : 1L + ((millis - 1L) / 1_000L);
        return String.format(Locale.US, "%d:%02d:%02d", seconds / 3_600L, (seconds / 60L) % 60L, seconds % 60L);
    }

    private static String toLatinDigits(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= '٠' && c <= '٩') {
                result.append((char) ('0' + (c - '٠')));
            } else if (c >= '۰' && c <= '۹') {
                result.append((char) ('0' + (c - '۰')));
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }

    private static void scheduleBoundaryRefresh(Context context, long atMillis) {
        Intent intent = new Intent(context, PrayerWidgetProvider.class)
                .setAction(ACTION_REFRESH);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context,
                BOUNDARY_REFRESH_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms == null) return;
        alarms.cancel(pendingIntent);

        long triggerAt = atMillis;
        if (atMillis <= 0L || triggerAt <= System.currentTimeMillis()) return;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarms.canScheduleExactAlarms()) {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent);
            } else {
                alarms.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent);
            }
        } catch (SecurityException ignored) {
            // Exact-alarm permission can be revoked between checking and setting.
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent);
        }
    }
}
