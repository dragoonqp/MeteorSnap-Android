# UVCAndroid 1.0.13 已内置 R8 consumer rules。若改用旧 ProGuard，可保留以下规则。
-keep class com.herohan.uvcapp.** { *; }
-keep class com.serenegiant.usb.** { *; }
-keepclassmembers class * implements com.serenegiant.usb.IFrameCallback { *; }
