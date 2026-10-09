package com.motoonai.twa;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class PrayerScheduleRestoreReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        PrayerWidgetProvider.renderStoredSnapshot(context.getApplicationContext());
        PrayerLiveNotificationController.reconcile(context.getApplicationContext());
        PrayerLocalNotificationScheduler.rescheduleStored(context.getApplicationContext());
        PrayerNotchController.notifySnapshotChanged(context.getApplicationContext());
        if (intent != null && (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction()))) {
            PrayerNotchController.startFromUser(context.getApplicationContext());
        }
    }
}
