package com.motoonai.twa;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.View;
import android.widget.RemoteViews;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

/** A silent live display; its foreground owner supplies locale-independent countdown text. */
final class PrayerLiveNotificationController {
    static final int NOTIFICATION_ID = 7810;
    static final String ACTION_REFRESH = "com.motoonai.twa.action.PRAYER_LIVE_REFRESH";
    static final String ACTION_DISMISS = "com.motoonai.twa.action.PRAYER_LIVE_DISMISS";
    static final String EXTRA_GENERATION = "generation";
    static final String EXTRA_PACKAGE_UPDATED_AT = "package_updated_at";
    private static final String CHANNEL_ID = "munibin_prayer_live_v1";
    private static final String PREFS = "munibin_prayer_live_v1";
    private static final String ENABLED = "enabled";
    private static final String GENERATION = "generation";
    private static final int ALARM_REQUEST = 7809;
    private static final int OPEN_REQUEST = 7811;
    private static final int CLOSE_REQUEST = 7812;
    private static String lastRenderKey = "";
    private static String cachedTemplateKey = "";
    private static PrayerLiveSchedule.State cachedTemplateState;
    private static Notification cachedTemplate;
    private static long lastAlarmAt;
    private static long lastClockOffset;
    private static boolean lastExactAllowed;
    private static final int[] LABEL_IDS = {R.id.prayer_live_fajr_label, R.id.prayer_live_sunrise_label,
            R.id.prayer_live_dhuhr_label, R.id.prayer_live_asr_label, R.id.prayer_live_maghrib_label,
            R.id.prayer_live_isha_label};
    private static final int[] TIME_IDS = {R.id.prayer_live_fajr_time, R.id.prayer_live_sunrise_time,
            R.id.prayer_live_dhuhr_time, R.id.prayer_live_asr_time, R.id.prayer_live_maghrib_time,
            R.id.prayer_live_isha_time};
    private static final int[] IQAMA_IDS = {R.id.prayer_live_fajr_iqama, R.id.prayer_live_sunrise_iqama,
            R.id.prayer_live_dhuhr_iqama, R.id.prayer_live_asr_iqama, R.id.prayer_live_maghrib_iqama,
            R.id.prayer_live_isha_iqama};
    private static final int[] COUNTDOWN_IDS = {R.id.prayer_live_fajr_countdown, R.id.prayer_live_sunrise_countdown,
            R.id.prayer_live_dhuhr_countdown, R.id.prayer_live_asr_countdown, R.id.prayer_live_maghrib_countdown,
            R.id.prayer_live_isha_countdown};
    private static final int[] PHASE_IDS = {R.id.prayer_live_fajr_phase, R.id.prayer_live_sunrise_phase,
            R.id.prayer_live_dhuhr_phase, R.id.prayer_live_asr_phase, R.id.prayer_live_maghrib_phase,
            R.id.prayer_live_isha_phase};

    private PrayerLiveNotificationController() {}

    static boolean isEnabled(Context context) {
        return preferences(context).getBoolean(ENABLED, false);
    }

    static synchronized void setEnabled(Context context, boolean enabled) {
        SharedPreferences preferences = preferences(context);
        if (preferences.getBoolean(ENABLED, false) != enabled
                || preferences.getString(GENERATION, "").isEmpty()) {
            // A delayed delete callback belonging to an earlier enabled session cannot
            // disable a notification the user has subsequently enabled again.
            preferences.edit().putBoolean(ENABLED, enabled)
                    .putString(GENERATION, UUID.randomUUID().toString()).commit();
        }
        if (!enabled) PrayerNotchController.setEnabled(context, false);
        reconcile(context);
    }

    static synchronized void dismiss(Context context, String generation) {
        if (!isEnabled(context) || generation == null
                || !generation.equals(preferences(context).getString(GENERATION, ""))) return;
        setEnabled(context, false);
    }

    static long installedUpdateTime(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).lastUpdateTime;
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
            return 0L;
        }
    }

    static synchronized void reconcile(Context context) {
        cancelBoundary(context);
        lastRenderKey = "";
        clearTemplate();
        lastAlarmAt = 0L;
        lastClockOffset = 0L;
        sync(context);
    }

    static synchronized void sync(Context context) {
        ensureChannel(context);
        JSONObject snapshot = PrayerWidgetStore.loadSnapshot(context);
        if (!isEnabled(context) || !PrayerWidgetStore.isPrayerTimesConfigured(snapshot)
                || !isNotificationDisplayAllowed(context)) {
            PrayerNotchController.stop(context);
            cancelBoundary(context);
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID);
            lastRenderKey = "";
            clearTemplate();
            return;
        }
        long now = System.currentTimeMillis();
        long offset = now - SystemClock.elapsedRealtime();
        boolean exact = canUseExactAlarms(context);
        boolean clockChanged = lastClockOffset != 0L && Math.abs(offset - lastClockOffset) > 2000L;
        boolean exactChanged = exact != lastExactAllowed;
        if (clockChanged || exactChanged) {
            lastRenderKey = "";
            lastAlarmAt = 0L;
        }
        lastClockOffset = offset;
        lastExactAllowed = exact;
        PrayerLiveSchedule.State state = PrayerLiveSchedule.select(snapshot, now);
        String templateKey = templateKey(context, state);
        String renderKey = displayKey(context, state, now, templateKey);
        if (!renderKey.equals(lastRenderKey) || !isActive(context)) {
            try {
                NotificationManagerCompat.from(context).notify(NOTIFICATION_ID,
                        notificationForDisplay(context, state, now, templateKey));
                lastRenderKey = renderKey;
            } catch (SecurityException ignored) {
                lastRenderKey = "";
                clearTemplate();
                PrayerNotchController.stop(context);
                cancelBoundary(context);
                return;
            }
        }
        if (state.nextRefreshAtMillis > now) scheduleBoundary(context, state.nextRefreshAtMillis, exact);
        else cancelBoundary(context);
    }

    static synchronized Notification buildForService(Context context) {
        ensureChannel(context);
        long now = System.currentTimeMillis();
        return buildNotification(context, PrayerLiveSchedule.select(PrayerWidgetStore.loadSnapshot(context), now), now);
    }

    /** Called only by the existing foreground owner, with a schedule parsed at a boundary. */
    static synchronized void tick(Context context, PrayerLiveSchedule.State state, long now) {
        if (!isEnabled(context) || state == null) return;
        String templateKey = templateKey(context, state);
        String key = displayKey(context, state, now, templateKey);
        if (key.equals(lastRenderKey)) return;
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID,
                    notificationForDisplay(context, state, now, templateKey));
            lastRenderKey = key;
        } catch (SecurityException ignored) {
            lastRenderKey = "";
            clearTemplate();
            PrayerNotchController.stop(context);
        }
    }

    static boolean isDisplayAllowed(Context context) {
        ensureChannel(context);
        return isNotificationDisplayAllowed(context)
                && PrayerWidgetStore.isPrayerTimesConfigured(PrayerWidgetStore.loadSnapshot(context));
    }

    /** Lightweight heartbeat gate: no loading or parsing of the annual prayer cache. */
    static boolean isNotificationDisplayAllowed(Context context) {
        return isEnabled(context) && hasPostPermission(context)
                && NotificationManagerCompat.from(context).areNotificationsEnabled()
                && isChannelEnabled(context);
    }

    static JSONObject getStatus(Context context) {
        ensureChannel(context);
        JSONObject result = new JSONObject();
        JSONObject snapshot = PrayerWidgetStore.loadSnapshot(context);
        boolean enabled = isEnabled(context);
        boolean configured = PrayerWidgetStore.isPrayerTimesConfigured(snapshot);
        boolean channelEnabled = isChannelEnabled(context);
        boolean permission = hasPostPermission(context)
                && NotificationManagerCompat.from(context).areNotificationsEnabled() && channelEnabled;
        boolean available = PrayerLiveSchedule.select(snapshot, System.currentTimeMillis()).available;
        boolean active = enabled && configured && permission && isActive(context);
        boolean liveCountdown = hasLiveCountdown(context);
        String reason = !enabled ? "disabled" : !configured ? "not-configured" : !permission ? "notifications"
                : !available ? "refresh-needed" : "running";
        try {
            result.put("enabled", enabled);
            result.put("configured", configured);
            result.put("permission", permission);
            result.put("notificationPermission", permission);
            result.put("channelEnabled", channelEnabled);
            result.put("available", available);
            result.put("cacheReady", available);
            result.put("active", active);
            result.put("exactAlarm", canUseExactAlarms(context));
            result.put("exactAlarms", canUseExactAlarms(context));
            result.put("liveCountdown", liveCountdown);
            result.put("countdownMode", liveCountdown ? "live" : "target-time");
            result.put("reason", reason);
            PrayerNotchController.appendStatus(context, result);
        } catch (JSONException ignored) { }
        return result;
    }

    private static Notification buildNotification(Context context, PrayerLiveSchedule.State state, long now) {
        String title = state.available ? state.label + (state.isIqama ? " Iqama" : "")
                : context.getString(R.string.prayer_live_refresh_needed);
        String deadline = state.available ? PrayerTimeFormat.formatMillis(context,
                state.targetAtMillis, state.timeZone) : "--:--";
        PendingIntent open = PendingIntent.getActivity(context, OPEN_REQUEST,
                new Intent(context, LauncherActivity.class)
                        .setData(Uri.parse("https://munibin.com/prayer-times"))
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        long installedUpdateAt = installedUpdateTime(context);
        PendingIntent close = PendingIntent.getBroadcast(context, CLOSE_REQUEST,
                new Intent(context, PrayerLiveNotificationReceiver.class).setAction(ACTION_DISMISS)
                        .setData(Uri.parse("munibin://live-prayer/close/" + generation(context) + "/" + installedUpdateAt))
                        .putExtra(EXTRA_GENERATION, generation(context))
                        .putExtra(EXTRA_PACKAGE_UPDATED_AT, installedUpdateAt),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        RemoteViews compact = createViews(context, R.layout.prayer_live_notification, state, now, title, deadline, close, false);
        RemoteViews expanded = createViews(context, R.layout.prayer_live_notification_expanded, state, now, title, deadline, close, true);
        return new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_icon)
                .setContentTitle(title)
                .setContentText(state.available ? title + " \u2067" + deadline + "\u2069" : title)
                .setCustomContentView(compact)
                .setCustomBigContentView(expanded)
                .setStyle(new NotificationCompat.DecoratedCustomViewStyle())
                .setContentIntent(open)
                .setDeleteIntent(close)
                .addAction(0, context.getString(R.string.prayer_live_close), close)
                .setOngoing(true)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setShowWhen(false)
                .build();
    }

    private static RemoteViews createViews(Context context, int layout, PrayerLiveSchedule.State state,
            long now, String title, String deadline, PendingIntent close, boolean expanded) {
        RemoteViews views = new RemoteViews(context.getPackageName(), layout);
        PrayerWidgetTheme.Palette palette = PrayerWidgetTheme.read(context, state.displayTheme);
        int primary = palette.primary;
        int secondary = palette.secondary;
        int green = palette.highlight;
        int red = palette.iqama;
        int phaseColor = state.isIqama ? red : green;
        views.setInt(R.id.prayer_live_container, "setBackgroundColor", palette.backgroundStart);
        // SystemUI's Chronometer formats numbers with the host locale and ignores
        // textLocale. Render ASCII text through the shared service while the screen is
        // interactive (including the lock screen); asleep/stopped displays show the
        // truthful target clock instead of a paused or negative duration.
        boolean live = state.available && hasLiveCountdown(context);
        if (!expanded) {
            // The collapsed notification has a 48dp budget and retains its compact
            // current-prayer row. These IDs deliberately do not exist in the big view.
            views.setTextViewText(R.id.prayer_live_target, title);
            views.setTextColor(R.id.prayer_live_target, primary);
            views.setTextColor(R.id.prayer_live_close, secondary);
            views.setTextColor(R.id.prayer_live_countdown, phaseColor);
            views.setTextColor(R.id.prayer_live_deadline, primary);
            views.setImageViewResource(R.id.prayer_live_icon, prayerIcon(state.targetKey));
            views.setOnClickPendingIntent(R.id.prayer_live_close, close);
            views.setViewVisibility(R.id.prayer_live_countdown, live ? View.VISIBLE : View.GONE);
            views.setViewVisibility(R.id.prayer_live_deadline, state.available && !live ? View.VISIBLE : View.GONE);
            views.setTextViewText(R.id.prayer_live_deadline, "\u2067" + deadline + "\u2069");
            if (live) views.setTextViewText(R.id.prayer_live_countdown,
                    PrayerNotchService.formatRemaining(state.targetAtMillis - now));
            return views;
        }
        // No duplicate countdown header: its timer lives next to the selected row's
        // name, away from the adhan clock and +minutes on the other side. A stopped
        // owner uses the real target clock; unavailable schedules never show a timer.
        views.setViewVisibility(R.id.prayer_live_note, live ? View.GONE : View.VISIBLE);
        views.setTextViewText(R.id.prayer_live_note, context.getString(state.available
                ? R.string.prayer_live_deadline_note : R.string.prayer_live_refresh_needed));
        views.setTextColor(R.id.prayer_live_note, secondary);
        // Expanded custom notifications have a finite SystemUI height budget. Keep
        // these six rows readable without letting global font scaling clip the last
        // row; the app's accessible font settings and compact view are untouched.
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        // Reserve notification decoration/padding when fitting a narrow phone. The
        // 12-hour suffix, +minutes and a full HH:mm:ss must all remain in one row.
        float widthScale = Math.min(1f, Math.max(220f, metrics.widthPixels / metrics.density - 64f) / 280f);
        float rowSize = Math.min(18f * metrics.scaledDensity, 18f * metrics.density) * widthScale;
        float offsetSize = Math.min(14f * metrics.scaledDensity, 14f * metrics.density) * widthScale;
        float phaseSize = Math.min(10f * metrics.scaledDensity, 10f * metrics.density);
        views.setTextViewTextSize(R.id.prayer_live_note, TypedValue.COMPLEX_UNIT_PX,
                Math.min(11f * metrics.scaledDensity, 11f * metrics.density));
        for (int slot = 0; slot < PrayerLiveSchedule.KEYS.length; slot++) {
            String key = PrayerLiveSchedule.KEYS[slot];
            PrayerLiveSchedule.Event selected = null;
            for (PrayerLiveSchedule.Event event : state.dayEvents) if (event.key.equals(key)) selected = event;
            boolean highlighted = state.available && key.equals(state.targetKey);
            String label = selected == null ? PrayerLiveSchedule.defaultLabel(key) : selected.label;
            views.setTextViewText(LABEL_IDS[slot], label);
            views.setTextViewText(TIME_IDS[slot], selected == null ? "--:--"
                    : "\u2067" + PrayerTimeFormat.formatClock(context, selected.time) + "\u2069");
            String offset = PrayerLiveSchedule.iqamaOffset(selected);
            views.setViewVisibility(IQAMA_IDS[slot], offset.isEmpty() ? View.GONE : View.VISIBLE);
            views.setTextViewText(IQAMA_IDS[slot], offset);
            views.setTextColor(IQAMA_IDS[slot], PrayerLiveSchedule.mutedClockColor(highlighted ? green : primary));
            views.setTextColor(LABEL_IDS[slot], highlighted ? green : secondary);
            views.setTextColor(TIME_IDS[slot], highlighted ? green : primary);
            views.setViewVisibility(COUNTDOWN_IDS[slot], highlighted ? View.VISIBLE : View.GONE);
            views.setTextColor(COUNTDOWN_IDS[slot], phaseColor);
            views.setTextViewText(COUNTDOWN_IDS[slot], !highlighted ? "" : live
                    ? PrayerNotchService.formatRemaining(state.targetAtMillis - now)
                    : "\u2067" + deadline + "\u2069");
            views.setViewVisibility(PHASE_IDS[slot], highlighted && state.isIqama ? View.VISIBLE : View.GONE);
            views.setTextViewText(PHASE_IDS[slot], highlighted && state.isIqama
                    ? context.getString(R.string.prayer_live_iqama_time, "").trim() : "");
            views.setTextColor(PHASE_IDS[slot], red);
            views.setTextViewTextSize(LABEL_IDS[slot], TypedValue.COMPLEX_UNIT_PX, rowSize);
            views.setTextViewTextSize(TIME_IDS[slot], TypedValue.COMPLEX_UNIT_PX, rowSize);
            views.setTextViewTextSize(COUNTDOWN_IDS[slot], TypedValue.COMPLEX_UNIT_PX, rowSize);
            views.setTextViewTextSize(IQAMA_IDS[slot], TypedValue.COMPLEX_UNIT_PX, offsetSize);
            views.setTextViewTextSize(PHASE_IDS[slot], TypedValue.COMPLEX_UNIT_PX, phaseSize);
        }
        return views;
    }

    private static int expandedCountdownId(PrayerLiveSchedule.State state) {
        for (int slot = 0; slot < PrayerLiveSchedule.KEYS.length; slot++) {
            if (PrayerLiveSchedule.KEYS[slot].equals(state.targetKey)) return COUNTDOWN_IDS[slot];
        }
        return 0;
    }

    private static String renderKey(PrayerLiveSchedule.State state, String generation) {
        StringBuilder key = new StringBuilder(generation).append('|').append(state.dayDate).append('|')
                .append(state.timeZone.getID()).append('|').append(state.targetKey).append('|')
                .append(state.label).append('|').append(state.isIqama).append('|').append(state.targetAtMillis);
        for (PrayerLiveSchedule.Event event : state.dayEvents) key.append('|').append(event.key)
                .append(':').append(event.label).append(':').append(event.time).append(':').append(event.atMillis)
                .append(':').append(event.iqamaAtMillis);
        return key.toString();
    }

    private static String templateKey(Context context, PrayerLiveSchedule.State state) {
        boolean live = state.available && hasLiveCountdown(context);
        return renderKey(state, generation(context)) + '|' + PrayerTimeFormat.read(context)
                + '|' + PrayerWidgetTheme.read(context, state.displayTheme).signature()
                + '|' + live;
    }

    private static String displayKey(Context context, PrayerLiveSchedule.State state, long now,
            String templateKey) {
        boolean live = state.available && hasLiveCountdown(context);
        return templateKey + '|' + (live ? Math.max(0L, state.targetAtMillis - now + 999L) / 1000L : 0L);
    }

    /** Static timetable formatting/layout is phase work, not per-second countdown work. */
    private static Notification notificationForDisplay(Context context, PrayerLiveSchedule.State state,
            long now, String templateKey) {
        if (cachedTemplate == null || cachedTemplateState != state || !templateKey.equals(cachedTemplateKey)) {
            cachedTemplate = buildNotification(context, state, now);
            cachedTemplateState = state;
            cachedTemplateKey = templateKey;
        }
        // Clone the immutable base rather than appending actions to retained RemoteViews.
        // Reusing mutable views would grow their action/parcel size every second.
        Notification notification = cachedTemplate.clone();
        if (state.available && hasLiveCountdown(context)) {
            String countdown = PrayerNotchService.formatRemaining(state.targetAtMillis - now);
            if (notification.contentView != null) {
                notification.contentView.setTextViewText(R.id.prayer_live_countdown, countdown);
            }
            if (notification.bigContentView != null) {
                int countdownId = expandedCountdownId(state);
                if (countdownId != 0) notification.bigContentView.setTextViewText(countdownId, countdown);
            }
        }
        return notification;
    }

    private static void clearTemplate() {
        cachedTemplateKey = "";
        cachedTemplateState = null;
        cachedTemplate = null;
    }

    private static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                context.getString(R.string.prayer_live_channel_name), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(context.getString(R.string.prayer_live_channel_description));
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.setShowBadge(false);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        manager.createNotificationChannel(channel);
    }

    private static boolean hasPostPermission(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private static boolean isChannelEnabled(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true;
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return false;
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL_ID);
        return channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    private static boolean isActive(Context context) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return false;
        try {
            for (StatusBarNotification notification : manager.getActiveNotifications()) {
                if (notification.getId() == NOTIFICATION_ID) return true;
            }
        } catch (RuntimeException ignored) { }
        return false;
    }

    private static boolean canUseExactAlarms(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        return alarms != null && alarms.canScheduleExactAlarms();
    }

    private static boolean hasLiveCountdown(Context context) {
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return PrayerNotchService.isCountdownLive() && power != null && power.isInteractive();
    }

    private static void scheduleBoundary(Context context, long at, boolean exact) {
        if (lastAlarmAt == at) return;
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms == null) return;
        PendingIntent pending = boundaryIntent(context, PendingIntent.FLAG_UPDATE_CURRENT);
        try {
            if (exact) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending);
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending);
            lastAlarmAt = at;
        } catch (SecurityException ignored) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending);
            lastAlarmAt = at;
        }
    }

    private static void cancelBoundary(Context context) {
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pending = boundaryIntent(context, PendingIntent.FLAG_NO_CREATE);
        if (alarms != null && pending != null) alarms.cancel(pending);
        lastAlarmAt = 0L;
    }

    private static PendingIntent boundaryIntent(Context context, int flags) {
        return PendingIntent.getBroadcast(context, ALARM_REQUEST,
                new Intent(context, PrayerLiveNotificationReceiver.class).setAction(ACTION_REFRESH),
                flags | PendingIntent.FLAG_IMMUTABLE);
    }

    private static int prayerIcon(String key) {
        if ("fajr".equals(key) || "sunrise".equals(key)) return R.drawable.ic_prayer_sunrise;
        if ("dhuhr".equals(key)) return R.drawable.ic_prayer_sun_max;
        if ("asr".equals(key)) return R.drawable.ic_prayer_sun_haze;
        if ("maghrib".equals(key)) return R.drawable.ic_prayer_sunset;
        if ("isha".equals(key)) return R.drawable.ic_prayer_moon_stars;
        return R.drawable.ic_prayer_clock;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String generation(Context context) {
        SharedPreferences preferences = preferences(context);
        String value = preferences.getString(GENERATION, "");
        if (value.isEmpty()) {
            value = UUID.randomUUID().toString();
            preferences.edit().putString(GENERATION, value).commit();
        }
        return value;
    }
}
