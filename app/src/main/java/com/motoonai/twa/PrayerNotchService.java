package com.motoonai.twa;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.util.Locale;

/** One foreground owner for the live ASCII notification and optional finite overlay windows. */
public final class PrayerNotchService extends Service {
    static final String ACTION_REFRESH = "com.motoonai.twa.action.REFRESH_PRAYER_NOTCH";
    static final String ACTION_STOP = "com.motoonai.twa.action.STOP_PRAYER_NOTCH";
    private static volatile boolean running;
    private static volatile boolean instanceCreated;
    private static volatile boolean instanceStopping;
    private static volatile boolean tickerInteractive;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windows;
    private View bar;
    private View popup;
    private WindowManager.LayoutParams barParams;
    private WindowManager.LayoutParams popupParams;
    private TextView countdown;
    private ImageView barIcon;
    private TextView target;
    private TextView targetTime;
    private TextView remaining;
    private ImageView popupIcon;
    private GridLayout times;
    private PrayerLiveSchedule.State state;
    private boolean barAttached;
    private boolean popupAttached;
    private boolean receiverRegistered;
    private boolean foregroundStarted;
    private boolean stopping;
    private boolean screenSuppressed;
    private long lastWallTime;
    private long lastElapsedTime;
    private long lastAccessCheck;
    private int cutoutTop;
    private int cutoutLeft;
    private int cutoutRight;

    static boolean isRunning() { return running && !instanceStopping; }
    static boolean hasInstance() { return instanceCreated; }
    static boolean isStopping() { return instanceCreated && instanceStopping; }
    static boolean isCountdownLive() { return isRunning() && tickerInteractive; }

    private final Runnable collapse = this::hidePopup;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (stopping) return;
            if (!isScreenInteractive()) {
                hideWindows();
                PrayerLiveNotificationController.tick(PrayerNotchService.this, state, System.currentTimeMillis());
                return;
            }
            long now = System.currentTimeMillis();
            long elapsed = SystemClock.elapsedRealtime();
            // Permission/channel polling uses no stored schedule parsing. The annual JSON
            // is loaded only on a snapshot event, clock change, unlock, or prayer boundary.
            if (elapsed - lastAccessCheck >= 10000L) {
                lastAccessCheck = elapsed;
                if (!PrayerLiveNotificationController.isNotificationDisplayAllowed(PrayerNotchService.this)) {
                    stopSafely();
                    return;
                }
            }
            boolean clockChanged = lastWallTime != 0L
                    && Math.abs((now - lastWallTime) - (elapsed - lastElapsedTime)) > 2000L;
            lastWallTime = now;
            lastElapsedTime = elapsed;
            if (state == null || clockChanged
                    || (state.nextRefreshAtMillis > 0L && now >= state.nextRefreshAtMillis)) {
                refresh();
                return;
            }
            PrayerLiveNotificationController.tick(PrayerNotchService.this, state, now);
            if (canShowWindows()) {
                if (bar == null) createWindows();
                renderCountdown(now);
                if (!attachBar()) return;
            } else hideWindows();
            handler.postDelayed(this, 1000L - (now % 1000L));
        }
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_STOP.equals(action)) {
                PrayerNotchController.setEnabled(context, false);
                refresh();
            } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                screenSuppressed = true;
                tickerInteractive = false;
                hideWindows();
                PrayerLiveNotificationController.tick(context, state, System.currentTimeMillis());
            } else {
                if (Intent.ACTION_SCREEN_ON.equals(action) || Intent.ACTION_USER_PRESENT.equals(action)) {
                    screenSuppressed = false;
                }
                if (Intent.ACTION_CONFIGURATION_CHANGED.equals(action)) rebuildWindows();
                refresh();
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        instanceCreated = true;
        instanceStopping = false;
        // Start foreground immediately; the permission/snapshot gate follows it to satisfy
        // Android's foreground-service startup deadline even on slower devices.
        try {
            Notification notification = PrayerLiveNotificationController.buildForService(this);
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(PrayerLiveNotificationController.NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(PrayerLiveNotificationController.NOTIFICATION_ID, notification);
            }
            foregroundStarted = true;
        } catch (RuntimeException exception) {
            stopSafely();
            return;
        }
        if (!PrayerNotchController.shouldRun(this)) {
            stopSafely();
            return;
        }
        windows = (WindowManager) getSystemService(WINDOW_SERVICE);
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_REFRESH);
        filter.addAction(ACTION_STOP);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        filter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
        try {
            ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        } catch (RuntimeException exception) {
            stopSafely();
            return;
        }
        receiverRegistered = true;
        running = true;
        PrayerNotchController.notifyStarted(this);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            PrayerNotchController.setEnabled(this, false);
            refresh();
        } else if (!stopping) {
            refresh();
        }
        // Resource-pressure restarts revalidate the persisted user opt-in in onCreate.
        // Android force-stop/Task Manager Stop remains authoritative; no timer cold-starts it.
        return START_STICKY;
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        rebuildWindows();
        refresh();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void refresh() {
        handler.removeCallbacks(tick);
        if (stopping) return;
        if (!PrayerNotchController.shouldRun(this)) {
            stopSafely();
            return;
        }
        JSONObject snapshot = PrayerWidgetStore.loadSnapshot(this);
        long now = System.currentTimeMillis();
        state = PrayerLiveSchedule.select(snapshot, now);
        lastWallTime = now;
        lastElapsedTime = SystemClock.elapsedRealtime();
        lastAccessCheck = lastElapsedTime;
        tickerInteractive = isScreenInteractive();
        PrayerLiveNotificationController.sync(this);
        if (canShowWindows()) {
            if (bar == null) createWindows();
            renderPhase();
            renderCountdown(now);
            if (!attachBar()) return;
            if (popupAttached) updatePopupSize();
        } else {
            hideWindows();
        }
        if (isScreenInteractive()) handler.postDelayed(tick, 1000L - (now % 1000L));
    }

    private boolean isScreenInteractive() {
        if (screenSuppressed) return false;
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        return power != null && power.isInteractive();
    }

    private boolean canShowWindows() {
        KeyguardManager keyguard = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        return windows != null && isScreenInteractive() && keyguard != null && !keyguard.isKeyguardLocked()
                && PrayerNotchController.isEnabled(this) && PrayerNotchController.hasOverlayPermission(this);
    }

    private void createWindows() {
        LayoutInflater inflater = LayoutInflater.from(this);
        bar = inflater.inflate(R.layout.prayer_notch_bar, null);
        countdown = bar.findViewById(R.id.prayer_notch_countdown);
        barIcon = bar.findViewById(R.id.prayer_notch_icon);
        popup = inflater.inflate(R.layout.prayer_notch_popup, null);
        target = popup.findViewById(R.id.prayer_notch_target);
        targetTime = popup.findViewById(R.id.prayer_notch_target_time);
        remaining = popup.findViewById(R.id.prayer_notch_remaining);
        popupIcon = popup.findViewById(R.id.prayer_notch_popup_icon);
        times = popup.findViewById(R.id.prayer_notch_times);
        barParams = params(dp(146), dp(34), false);
        popupParams = params(popupWidth(), WindowManager.LayoutParams.WRAP_CONTENT, true);
        bar.setOnClickListener(view -> { if (popupAttached) hidePopup(); else showPopup(); });
        popup.findViewById(R.id.prayer_notch_close).setOnClickListener(view -> {
            PrayerNotchController.setEnabled(this, false);
            refresh();
        });
        popup.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                hidePopup();
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) armCollapse();
            return false;
        });
        if (Build.VERSION.SDK_INT >= 28) {
            bar.setOnApplyWindowInsetsListener((view, insets) -> {
                recordCutout(insets);
                updateWindowPositions();
                return insets;
            });
        }
        readPhysicalCutout();
        updateWindowPositions();
    }

    private WindowManager.LayoutParams params(int width, int height, boolean expanded) {
        int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        if (expanded) flags |= WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
        WindowManager.LayoutParams result = new WindowManager.LayoutParams(width, height, type,
                flags, PixelFormat.TRANSLUCENT);
        result.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        result.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
        if (Build.VERSION.SDK_INT >= 28) {
            result.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER;
        }
        if (Build.VERSION.SDK_INT >= 30) {
            result.setFitInsetsTypes(WindowInsets.Type.systemBars());
        }
        result.setTitle(expanded ? "Munibin prayer times" : "Munibin prayer countdown");
        return result;
    }

    private void readPhysicalCutout() {
        cutoutTop = cutoutLeft = cutoutRight = 0;
        if (Build.VERSION.SDK_INT >= 29 && windows != null) {
            DisplayCutout cutout = windows.getDefaultDisplay().getCutout();
            if (cutout != null) {
                cutoutTop = cutout.getSafeInsetTop();
                cutoutLeft = cutout.getSafeInsetLeft();
                cutoutRight = cutout.getSafeInsetRight();
            }
        }
    }

    private void recordCutout(WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= 28 && insets.getDisplayCutout() != null) {
            DisplayCutout cutout = insets.getDisplayCutout();
            cutoutTop = Math.max(cutoutTop, cutout.getSafeInsetTop());
            cutoutLeft = Math.max(cutoutLeft, cutout.getSafeInsetLeft());
            cutoutRight = Math.max(cutoutRight, cutout.getSafeInsetRight());
        }
    }

    private void updateWindowPositions() {
        if (barParams == null) return;
        // WindowManager already places this non-fullscreen window inside the safe
        // status/cutout frame. Adding the physical inset again lowered the old bar.
        barParams.y = dp(4);
        barParams.x = 0;
        popupParams.y = dp(44);
        popupParams.x = barParams.x;
        if (barAttached) safeUpdate(bar, barParams);
        if (popupAttached) updatePopupSize();
    }

    private int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : dp(24);
    }

    private int popupWidth() {
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        return Math.max(dp(120), Math.min(dp(360), screenWidth - cutoutLeft - cutoutRight - dp(24)));
    }

    private void updatePopupSize() {
        if (popup == null || popupParams == null) return;
        popupParams.width = popupWidth();
        int heightLimit = Math.max(dp(80), getResources().getDisplayMetrics().heightPixels
                - Math.max(statusBarHeight(), cutoutTop) - popupParams.y - dp(24));
        popup.measure(View.MeasureSpec.makeMeasureSpec(popupParams.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(heightLimit, View.MeasureSpec.AT_MOST));
        popupParams.height = Math.min(heightLimit, popup.getMeasuredHeight());
        if (popupAttached) safeUpdate(popup, popupParams);
    }

    private boolean attachBar() {
        if (barAttached) return true;
        try {
            windows.addView(bar, barParams);
            barAttached = true;
            bar.requestApplyInsets();
            return true;
        } catch (RuntimeException exception) {
            disableOverlayWindows();
            // Overlay failure must not stop the independent notification ticker.
            return true;
        }
    }

    private void showPopup() {
        if (stopping || popupAttached || !canShowWindows()) return;
        updatePopupSize();
        try {
            windows.addView(popup, popupParams);
            popupAttached = true;
            popup.setAlpha(0f);
            popup.setScaleX(0.94f);
            popup.setScaleY(0.94f);
            popup.setPivotY(0f);
            popup.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160L).start();
            armCollapse();
        } catch (RuntimeException exception) {
            disableOverlayWindows();
        }
    }

    private void armCollapse() {
        handler.removeCallbacks(collapse);
        handler.postDelayed(collapse, 8000L);
    }

    private void hidePopup() {
        handler.removeCallbacks(collapse);
        if (popup != null) popup.animate().cancel();
        if (popupAttached) {
            safeRemove(popup);
            popupAttached = false;
        }
    }

    private void hideWindows() {
        handler.removeCallbacks(tick);
        hidePopup();
        if (barAttached) {
            safeRemove(bar);
            barAttached = false;
        }
    }

    private void rebuildWindows() {
        hideWindows();
        bar = null;
        popup = null;
    }

    private void safeUpdate(View view, WindowManager.LayoutParams params) {
        try { windows.updateViewLayout(view, params); }
        catch (RuntimeException exception) { disableOverlayWindows(); }
    }

    private void disableOverlayWindows() {
        PrayerNotchController.setEnabled(this, false);
        hideWindows();
    }

    private void safeRemove(View view) {
        try { if (windows != null) windows.removeViewImmediate(view); }
        catch (RuntimeException ignored) { /* Already removed by the system. */ }
    }

    private void renderPhase() {
        if (state == null || target == null) return;
        int icon = iconFor(state.targetKey);
        barIcon.setImageResource(icon);
        popupIcon.setImageResource(icon);
        if (state.available) {
            target.setText(state.label + (state.isIqama ? " · " + getString(R.string.prayer_notch_iqama) : ""));
            targetTime.setText("\u2067" + PrayerTimeFormat.formatMillis(this, state.targetAtMillis, state.timeZone) + "\u2069");
            targetTime.setTextColor(getColorCompat(R.color.prayer_notch_text));
        } else {
            target.setText(R.string.prayer_notch_refresh_needed);
            targetTime.setText("—");
        }
        times.removeAllViews();
        for (String key : PrayerLiveSchedule.KEYS) {
            PrayerLiveSchedule.Event event = null;
            for (PrayerLiveSchedule.Event candidate : state.dayEvents) {
                if (key.equals(candidate.key)) { event = candidate; break; }
            }
            boolean active = state.available && key.equals(state.targetKey);
            int color = getColorCompat(active ? R.color.prayer_notch_remaining_color : R.color.prayer_notch_text);
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(dp(3), dp(10), dp(3), dp(10));
            TextView label = new TextView(this);
            label.setGravity(Gravity.CENTER);
            label.setTextSize(13.5f);
            label.setTextColor(color);
            label.setText(event == null ? PrayerLiveSchedule.defaultLabel(key) : event.label);
            TextView time = new TextView(this);
            time.setGravity(Gravity.CENTER);
            time.setTextDirection(View.TEXT_DIRECTION_LTR);
            time.setTypeface(Typeface.MONOSPACE);
            time.setTextSize(15f);
            time.setTextColor(color);
            time.setText(event == null ? "—" : "\u2067" + PrayerTimeFormat.formatClock(this, event.time) + "\u2069");
            cell.addView(label);
            cell.addView(time);
            String offset = PrayerLiveSchedule.iqamaOffset(event);
            if (!offset.isEmpty()) {
                TextView iqama = new TextView(this);
                iqama.setGravity(Gravity.CENTER);
                iqama.setTextSize(12f);
                iqama.setTextDirection(View.TEXT_DIRECTION_LTR);
                iqama.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
                iqama.setTextColor(PrayerLiveSchedule.mutedClockColor(color));
                iqama.setText(offset);
                cell.addView(iqama);
            }
            GridLayout.LayoutParams cellParams = new GridLayout.LayoutParams();
            cellParams.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            cellParams.width = 0;
            cellParams.height = GridLayout.LayoutParams.WRAP_CONTENT;
            times.addView(cell, cellParams);
        }
    }

    private void renderCountdown(long now) {
        if (state == null || countdown == null) return;
        String value = state.available ? formatRemaining(state.targetAtMillis - now) : "—";
        countdown.setText(value);
        int color = getColorCompat(state.isIqama
                ? R.color.prayer_notch_iqama : R.color.prayer_notch_remaining_color);
        countdown.setTextColor(color);
        String name = state.available ? state.label
                + (state.isIqama ? " " + getString(R.string.prayer_notch_iqama) : "") : getString(R.string.prayer_notch_refresh_needed);
        bar.setContentDescription(name + " " + value + ". " + getString(R.string.prayer_notch_expand));
        remaining.setText(getString(R.string.prayer_notch_remaining) + "  \u2066" + value + "\u2069");
        remaining.setTextColor(color);
    }

    static String formatRemaining(long millis) {
        long seconds = millis <= 0L ? 0L : 1L + ((millis - 1L) / 1000L);
        return String.format(Locale.US, "%02d:%02d:%02d", seconds / 3600L, (seconds / 60L) % 60L, seconds % 60L);
    }

    private int iconFor(String key) {
        if ("fajr".equals(key)) return R.drawable.ic_prayer_sun_haze;
        if ("sunrise".equals(key)) return R.drawable.ic_prayer_sunrise;
        if ("dhuhr".equals(key)) return R.drawable.ic_prayer_sun_max;
        if ("asr".equals(key)) return R.drawable.ic_prayer_sun_haze;
        if ("maghrib".equals(key)) return R.drawable.ic_prayer_sunset;
        if ("isha".equals(key)) return R.drawable.ic_prayer_moon_stars;
        return R.drawable.ic_prayer_clock;
    }

    private int getColorCompat(int resource) { return ContextCompat.getColor(this, resource); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void stopSafely() {
        if (stopping) return;
        stopping = true;
        instanceStopping = true;
        tickerInteractive = false;
        hideWindows();
        stopSelf();
    }

    @Override public void onDestroy() {
        stopping = true;
        instanceStopping = true;
        tickerInteractive = false;
        running = false;
        instanceCreated = false;
        hideWindows();
        handler.removeCallbacksAndMessages(null);
        if (receiverRegistered) {
            unregisterReceiver(receiver);
            receiverRegistered = false;
        }
        if (foregroundStarted) {
            boolean keepNotification = PrayerLiveNotificationController.isDisplayAllowed(this);
            if (Build.VERSION.SDK_INT >= 24) {
                stopForeground(keepNotification ? STOP_FOREGROUND_DETACH : STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(!keepNotification);
            }
            // Once foreground ownership is released, restore or remove the regular
            // notification using the same permission and dated-schedule gate.
            PrayerLiveNotificationController.sync(this);
        }
        PrayerNotchController.notifyStopped(this);
        super.onDestroy();
    }
}
