-keep,allowoptimization class com.inateck.scanner.** { *; }
-keep,allowoptimization class com.clj.fastble.** { *; }
-keep class com.sun.jna.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
-dontwarn java.awt.**
# Vendor callbacks log raw notification data by default. Strip it in BOTH builds.
-assumenosideeffects class android.util.Log {
    public static *** *(...);
}
