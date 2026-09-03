# Inateck's SDK uses JNA/native lookup and FastBle callback types.
-keep,allowoptimization class com.inateck.scanner.** { *; }
-keep,allowoptimization class com.clj.fastble.** { *; }
-keep class com.sun.jna.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
-dontwarn java.awt.**
