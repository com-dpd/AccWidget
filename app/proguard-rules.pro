# Keep entry points referenced from the manifest / reflection.
-keep class com.dp.accwidget.AccWidgetApp { *; }
-keep class com.dp.accwidget.widget.** { *; }
-keep class com.dp.accwidget.smart.** { *; }
-keep class com.dp.accwidget.ui.MainActivity { *; }

# DataStore / coroutines
-dontwarn kotlinx.coroutines.**
-keepclassmembers class * extends androidx.datastore.core.Serializer { *; }
