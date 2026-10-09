package com.motoonai.twa;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Private phase and dismiss actions refresh an existing owner without cold-starting it. */
public final class PrayerLiveNotificationReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        if (PrayerLiveNotificationController.ACTION_DISMISS.equals(intent.getAction())) {
            // Package replacement can remove an old notification and deliver its
            // delete callback. Only this installation's user-close action may
            // change the persisted opt-in; stale callbacks must not turn it off.
            if (intent.getLongExtra(PrayerLiveNotificationController.EXTRA_PACKAGE_UPDATED_AT, -1L)
                    == PrayerLiveNotificationController.installedUpdateTime(context)) {
                PrayerLiveNotificationController.dismiss(context,
                        intent.getStringExtra(PrayerLiveNotificationController.EXTRA_GENERATION));
            }
        } else if (PrayerLiveNotificationController.ACTION_REFRESH.equals(intent.getAction())) {
            PrayerLiveNotificationController.sync(context);
            PrayerNotchController.notifySnapshotChanged(context);
        }
        PrayerNotchController.broadcastStatus(context);
    }
}
