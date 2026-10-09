package com.motoonai.twa;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Rect;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.OrientationEventListener;
import android.view.Surface;

/**
 * The phone host permits portrait and both landscapes, never reverse portrait.
 * Tablets use Android's four-way user policy. Rotation lock remains the user's
 * choice; no settings are written, and no sensor runs while paused or locked.
 */
final class PhoneTabletOrientationController {
    private static final int TABLET_MIN_SHORT_SIDE_DP = 600;
    private static final int CARDINAL_ACCEPTANCE_DEGREES = 30;

    private final Activity activity;
    private final OrientationEventListener sensor;
    private final ContentObserver rotationSettingsObserver;
    private boolean tablet;
    private boolean naturalLandscape;
    private boolean reverseDefaultRotation;
    private boolean resumed;
    private boolean disposed;
    private boolean observingSettings;
    private boolean sensorEnabled;
    private boolean autoRotateEnabled;
    private int lastAllowedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;

    PhoneTabletOrientationController(Activity activity) {
        this.activity = activity;
        sensor = new OrientationEventListener(activity, SensorManager.SENSOR_DELAY_NORMAL) {
            @Override
            public void onOrientationChanged(int degrees) {
                if (disposed || !resumed || tablet || !autoRotateEnabled) return;
                int requested = orientationForSensor(degrees, naturalLandscape, reverseDefaultRotation);
                if (requested == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) return;
                lastAllowedOrientation = requested;
                requestIfChanged(requested);
            }
        };
        rotationSettingsObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange) {
                if (!disposed && resumed) applyUserRotationPolicy();
            }
        };
        readDisplayConfiguration();
        lastAllowedOrientation = orientationForRotation(currentRotation(), naturalLandscape, reverseDefaultRotation);
        applyUserRotationPolicy();
    }

    void onResume() {
        if (disposed) return;
        resumed = true;
        readDisplayConfiguration();
        observeRotationSettings();
        applyUserRotationPolicy();
    }

    void onPause() {
        resumed = false;
        setSensorEnabled(false);
        stopObservingSettings();
    }

    void onConfigurationChanged() {
        if (disposed) return;
        readDisplayConfiguration();
        applyUserRotationPolicy();
    }

    void dispose() {
        onPause();
        disposed = true;
    }

    private void applyUserRotationPolicy() {
        if (disposed) return;
        if (tablet) {
            autoRotateEnabled = false;
            setSensorEnabled(false);
            stopObservingSettings();
            requestIfChanged(ActivityInfo.SCREEN_ORIENTATION_FULL_USER);
            return;
        }
        if (resumed) observeRotationSettings();
        autoRotateEnabled = readSystemInt(Settings.System.ACCELEROMETER_ROTATION, 0) == 1;
        if (!autoRotateEnabled) {
            int lockedRotation = readSystemInt(Settings.System.USER_ROTATION, currentRotation());
            lastAllowedOrientation = orientationForRotation(lockedRotation, naturalLandscape, reverseDefaultRotation);
        }
        requestIfChanged(lastAllowedOrientation);
        // If settings observation or the orientation sensor is unavailable, keep
        // the last permitted orientation instead of falling back to USER, which
        // some OEMs allow to rotate into reverse portrait.
        setSensorEnabled(resumed && autoRotateEnabled && observingSettings && sensor.canDetectOrientation());
    }

    private void requestIfChanged(int requested) {
        if (activity.getRequestedOrientation() != requested) {
            activity.setRequestedOrientation(requested);
        }
    }

    private void setSensorEnabled(boolean enabled) {
        if (sensorEnabled == enabled) return;
        sensorEnabled = enabled;
        if (enabled) sensor.enable();
        else sensor.disable();
    }

    private void observeRotationSettings() {
        if (observingSettings || tablet || disposed || !resumed) return;
        ContentResolver resolver = activity.getContentResolver();
        try {
            resolver.registerContentObserver(Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION), false, rotationSettingsObserver);
            resolver.registerContentObserver(Settings.System.getUriFor(Settings.System.USER_ROTATION), false, rotationSettingsObserver);
            observingSettings = true;
        } catch (SecurityException unavailable) {
            // Remove a possibly registered first URI if the second registration
            // failed. Safe fallback is a fixed permitted phone orientation.
            try { resolver.unregisterContentObserver(rotationSettingsObserver); }
            catch (SecurityException ignored) {}
            observingSettings = false;
        }
    }

    private void stopObservingSettings() {
        if (!observingSettings) return;
        observingSettings = false;
        try { activity.getContentResolver().unregisterContentObserver(rotationSettingsObserver); }
        catch (SecurityException ignored) {}
    }

    private int readSystemInt(String key, int fallback) {
        try { return Settings.System.getInt(activity.getContentResolver(), key, fallback); }
        catch (SecurityException unavailable) { return fallback; }
    }

    private int currentRotation() {
        return activity.getWindowManager().getDefaultDisplay().getRotation();
    }

    private void readDisplayConfiguration() {
        Configuration configuration = activity.getResources().getConfiguration();
        int width;
        int height;
        int densityDpi = configuration.densityDpi;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Maximum bounds describe the full display area available to this
            // activity; current bounds/smallestScreenWidthDp shrink in split view.
            Rect bounds = activity.getWindowManager().getMaximumWindowMetrics().getBounds();
            width = bounds.width();
            height = bounds.height();
        } else {
            DisplayMetrics metrics = new DisplayMetrics();
            activity.getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
            width = metrics.widthPixels;
            height = metrics.heightPixels;
            if (densityDpi <= 0) densityDpi = metrics.densityDpi;
        }
        if (densityDpi <= 0) densityDpi = DisplayMetrics.DENSITY_DEFAULT;
        boolean wasTablet = tablet;
        boolean previousNaturalLandscape = naturalLandscape;
        boolean previousReverseDefault = reverseDefaultRotation;
        tablet = isTabletDisplay(width, height, densityDpi);
        naturalLandscape = isNaturalLandscape(width, height, currentRotation());
        reverseDefaultRotation = readReverseDefaultRotation();
        if (wasTablet != tablet || previousNaturalLandscape != naturalLandscape
                || previousReverseDefault != reverseDefaultRotation) {
            lastAllowedOrientation = orientationForRotation(currentRotation(), naturalLandscape, reverseDefaultRotation);
        }
    }

    private boolean readReverseDefaultRotation() {
        // AOSP uses this OEM overlay to map landscape/portrait display rotations.
        // Looking up the resource needs neither reflection nor a hidden API.
        Resources resources = activity.getResources();
        int resource = resources.getIdentifier("config_reverseDefaultRotation", "bool", "android");
        if (resource == 0) return false;
        try { return resources.getBoolean(resource); }
        catch (Resources.NotFoundException unavailable) { return false; }
    }

    static boolean isTabletDisplay(int width, int height, int densityDpi) {
        return width > 0 && height > 0 && densityDpi > 0
                && (long) Math.min(width, height) * DisplayMetrics.DENSITY_DEFAULT
                >= (long) TABLET_MIN_SHORT_SIDE_DP * densityDpi;
    }

    static boolean isNaturalLandscape(int width, int height, int rotation) {
        boolean quarterTurn = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270;
        return quarterTurn ? height > width : width > height;
    }

    static int orientationForRotation(int rotation, boolean naturalLandscape, boolean reverseDefault) {
        int landscape = naturalLandscape
                ? Surface.ROTATION_0
                : (reverseDefault ? Surface.ROTATION_270 : Surface.ROTATION_90);
        if (rotation == landscape) return ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
        if (rotation == (landscape + 2) % 4) return ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
        // Includes normal portrait, forbidden reverse portrait, and bad values.
        return ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
    }

    static int orientationForSensor(int degrees, boolean naturalLandscape, boolean reverseDefault) {
        if (degrees < 0 || degrees >= 360) return ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        int nearest = ((degrees + 45) / 90) % 4;
        int difference = Math.abs(degrees - nearest * 90);
        difference = Math.min(difference, 360 - difference);
        if (difference > CARDINAL_ACCEPTANCE_DEGREES) return ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        int rotation = (4 - nearest) % 4;
        int portrait = naturalLandscape
                ? (reverseDefault ? Surface.ROTATION_90 : Surface.ROTATION_270)
                : Surface.ROTATION_0;
        if (rotation == (portrait + 2) % 4) return ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        return orientationForRotation(rotation, naturalLandscape, reverseDefault);
    }
}
