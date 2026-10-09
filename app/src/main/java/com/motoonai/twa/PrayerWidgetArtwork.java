package com.motoonai.twa;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Bundle;

/** RemoteViews-compatible rounded backgrounds for the exact resolved app palette. */
final class PrayerWidgetArtwork {
    private static final java.util.LinkedHashMap<String, Bitmap> BACKGROUNDS = new java.util.LinkedHashMap<>(4, 0.75f, true);
    private PrayerWidgetArtwork() {}

    static synchronized Bitmap background(Context context, AppWidgetManager manager, int widgetId,
                             PrayerWidgetTheme.Palette palette, boolean compact, boolean configured) {
        Bundle options = manager.getAppWidgetOptions(widgetId);
        int widthDp = options == null ? 280 : options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 280);
        int heightDp = compact
                ? (options == null ? (configured ? 164 : 132)
                    : options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 228))
                : (configured ? 190 : 132);
        widthDp = Math.max(120, Math.min(800, widthDp));
        heightDp = Math.max(80, Math.min(800, heightDp));
        float density = context.getResources().getDisplayMetrics().density;
        float scale = Math.min(density, 1024f / Math.max(widthDp, heightDp));
        String cacheKey = palette.signature() + ":" + widthDp + ":" + heightDp + ":" + density;
        Bitmap cached = BACKGROUNDS.get(cacheKey);
        if (cached != null) return cached;
        Bitmap bitmap = Bitmap.createBitmap(Math.max(1, Math.round(widthDp * scale)),
                Math.max(1, Math.round(heightDp * scale)), Bitmap.Config.ARGB_8888);
        bitmap.setDensity(context.getResources().getDisplayMetrics().densityDpi);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        RectF bounds = new RectF(scale / 2f, scale / 2f, bitmap.getWidth() - scale / 2f, bitmap.getHeight() - scale / 2f);
        paint.setShader(new LinearGradient(0, 0, bitmap.getWidth(), bitmap.getHeight(),
                palette.backgroundStart, palette.backgroundEnd, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(bounds, 28f * scale, 28f * scale, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(scale);
        paint.setColor(palette.border);
        canvas.drawRoundRect(bounds, 28f * scale, 28f * scale, paint);
        BACKGROUNDS.put(cacheKey, bitmap);
        if (BACKGROUNDS.size() > 2) BACKGROUNDS.remove(BACKGROUNDS.keySet().iterator().next());
        return bitmap;
    }

    static Bitmap iconShell(Context context, int color, boolean circle) {
        float density = context.getResources().getDisplayMetrics().density;
        int size = Math.max(1, Math.min(256, Math.round(32f * density)));
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        bitmap.setDensity(context.getResources().getDisplayMetrics().densityDpi);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color);
        float radius = circle ? size / 2f : 12f * density;
        new Canvas(bitmap).drawRoundRect(new RectF(0, 0, size, size), radius, radius, paint);
        return bitmap;
    }
}
