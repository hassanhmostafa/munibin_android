package com.motoonai.twa;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

/** The user-enabled live notification and optional overlay share one foreground owner. */
final class PrayerNotchController {
    static final String ACTION_STATUS = "com.motoonai.twa.action.PRAYER_NOTCH_STATUS";
    static final String EXTRA_RETRY_EXPLICIT_START = "retry_explicit_start";
    private static final String PREFS = "munibin_prayer_notch_v1";
    private static final String ENABLED = "enabled";
    private static boolean startPending;
    private static boolean stopPending;
    private static boolean retryExplicitStart;

    private PrayerNotchController() {}

    static boolean isEnabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, false);
    }

    static void setEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(ENABLED, enabled).commit();
        if (!enabled) notifySnapshotChanged(context);
        broadcastStatus(context, false);
    }

    static boolean hasOverlayPermission(Context context) {
        try {
            return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    static boolean shouldRun(Context context) {
        return PrayerLiveNotificationController.isDisplayAllowed(context);
    }

    static boolean shouldShowOverlay(Context context) {
        return isEnabled(context) && hasOverlayPermission(context) && shouldRun(context);
    }

    /** Only call from a visible activity or an eligible boot/package-replacement receiver. */
    static synchronized void startFromUser(Context context) {
        if (!shouldRun(context)) {
            stop(context);
            return;
        }
        // A rapid off/on may arrive while the previous service is still being destroyed.
        // Its status broadcast asks only a visible activity to retry once destruction finishes.
        if (stopPending || PrayerNotchService.isStopping()) {
            retryExplicitStart = true;
            return;
        }
        if (PrayerNotchService.isRunning()) {
            sendRefresh(context);
            return;
        }
        if (startPending) return;
        startPending = true;
        try {
            Intent intent = new Intent(context, PrayerNotchService.class);
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
            else context.startService(intent);
        } catch (RuntimeException exception) {
            startPending = false;
            Log.w("PrayerNotch", "Unable to start the live prayer display", exception);
            broadcastStatus(context, false);
        }
    }

    static synchronized void stop(Context context) {
        retryExplicitStart = false;
        // A queued start can be cancelled before onCreate, in which case no onDestroy
        // callback exists. Only an actual instance can make an explicit retry wait.
        if (PrayerNotchService.hasInstance()) stopPending = true;
        startPending = false;
        context.stopService(new Intent(context, PrayerNotchService.class));
    }

    /** Refresh an existing service without using a background cold-start exemption. */
    static void notifySnapshotChanged(Context context) {
        if (!shouldRun(context)) stop(context);
        else if (PrayerNotchService.isRunning()) sendRefresh(context);
    }

    private static void sendRefresh(Context context) {
        context.sendBroadcast(new Intent(PrayerNotchService.ACTION_REFRESH)
                .setPackage(context.getPackageName()));
    }

    static synchronized void notifyStarted(Context context) {
        startPending = false;
        broadcastStatus(context, false);
    }

    static synchronized void notifyStopped(Context context) {
        boolean retry = retryExplicitStart && PrayerLiveNotificationController.isEnabled(context);
        startPending = false;
        stopPending = false;
        retryExplicitStart = false;
        broadcastStatus(context, retry);
    }

    static void appendStatus(Context context, JSONObject status) throws JSONException {
        boolean enabled = isEnabled(context);
        boolean permission = hasOverlayPermission(context);
        boolean running = PrayerNotchService.isRunning() && shouldShowOverlay(context);
        String reason;
        if (!enabled) reason = "disabled";
        else if (!status.optBoolean("enabled", false)) reason = "prayer-disabled";
        else if (!status.optBoolean("configured", false)) reason = "not-configured";
        else if (!status.optBoolean("notificationPermission", false)
                || !status.optBoolean("channelEnabled", true)) reason = "notifications";
        else if (!permission) reason = "overlay-permission";
        else reason = running ? "running" : "start-required";
        status.put("notchEnabled", enabled);
        status.put("overlayPermission", permission);
        status.put("notchRunning", running);
        status.put("notchSupported", true);
        status.put("notchReason", reason);
    }

    static void broadcastStatus(Context context) {
        broadcastStatus(context, false);
    }

    private static void broadcastStatus(Context context, boolean retry) {
        context.sendBroadcast(new Intent(ACTION_STATUS).setPackage(context.getPackageName())
                .putExtra(EXTRA_RETRY_EXPLICIT_START, retry));
    }
}
