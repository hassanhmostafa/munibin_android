# JavaScript calls these methods by their source-level names.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Native widget / alarm entry points are also referenced from AndroidManifest.xml.
-keep class com.motoonai.twa.PrayerWidgetProvider { *; }
-keep class com.motoonai.twa.PrayerNotificationReceiver { *; }
-keep class com.motoonai.twa.PrayerScheduleRestoreReceiver { *; }
-keep class com.motoonai.twa.PrayerLiveNotificationReceiver { *; }
-keep class com.motoonai.twa.PrayerNotchService { *; }
