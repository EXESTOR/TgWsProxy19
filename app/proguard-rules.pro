-keep class com.chaquo.python.** { *; }

# Chaquopy / Python
-keep class com.chaquo.python.** { *; }
-dontwarn com.chaquo.**

# мост JS ↔ Kotlin
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.tgwsproxy19.WebAppBridge { *; }

# AppWidget
-keep class com.tgwsproxy19.** extends android.appwidget.AppWidgetProvider { *; }
