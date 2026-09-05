# The official Inateck Android SDK 2.0.0 logs raw notification bytes. The
# release build removes platform logging calls so scan payloads and setting
# replies do not enter Logcat.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}

# SDK 2.0.0 also writes connection and command objects with System.out.
# Remove those calls from the non-debuggable release build as well.
-assumenosideeffects class java.io.PrintStream {
    public void print(...);
    public void println(...);
}

# The SDK and JNA locate native entry points reflectively. Preserve names while
# still allowing R8 to optimize method bodies (including Log calls above).
-keep,allowoptimization class com.inateck.scanner.** { *; }
-keep,allowoptimization class com.clj.fastble.** { *; }
-keep class com.sun.jna.** { *; }
-keep interface jp.rimtty.codematch.scanner.inateck.InateckScannerCmdJna$Api { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# JNA core contains dormant desktop-AWT helpers. Android never calls them;
# suppress only those unavailable desktop types while retaining native JNA.
-dontwarn java.awt.**
