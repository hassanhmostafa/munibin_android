package com.motoonai.twa;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

public class PrayerLockWidgetProvider extends AppWidgetProvider {
    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int appWidgetId, Bundle options) {
        super.onAppWidgetOptionsChanged(context, manager, appWidgetId, options);
        PrayerWidgetProvider.renderCompact(context, manager, appWidgetId);
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        for (int widgetId : appWidgetIds) {
            PrayerWidgetProvider.renderCompact(context, manager, widgetId);
        }
        PrayerWidgetProvider.refreshInBackground(context, this.goAsync());
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (PrayerWidgetProvider.ACTION_REFRESH.equals(intent.getAction())) {
            PrayerWidgetProvider.renderStoredSnapshot(context);
            PrayerWidgetProvider.refreshInBackground(context, this.goAsync());
        }
    }
}
