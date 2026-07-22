# Rules added in release-polish task.

# AUSBC (libausbc) / libuvc JNI — native callbacks reference these by name.
-keep class com.jiangdg.** { *; }
-dontwarn com.jiangdg.**

# libuvc's USBMonitor and friends live in com.serenegiant.** on AUSBC 3.2.7
# (not com.jiangdg.usb as in newer releases); native code binds to them by name too.
-keep class com.serenegiant.** { *; }
-dontwarn com.serenegiant.**

# Keep USB monitor callbacks invoked reflectively/by JNI.
-keepclassmembers class * implements com.serenegiant.usb.USBMonitor$OnDeviceConnectListener { *; }

# Kotlin coroutines debug metadata.
-dontwarn kotlinx.coroutines.**
