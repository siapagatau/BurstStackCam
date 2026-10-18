-keep class com.burststack.cam.NativeLib { *; }
-keep interface com.burststack.cam.ProgressListener { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
