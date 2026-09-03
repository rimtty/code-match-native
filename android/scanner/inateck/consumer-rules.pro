# Inateck's SDK uses JNA/native lookup and FastBle callback types.
-keep,allowoptimization class com.inateck.scanner.** { *; }
-keep,allowoptimization class com.clj.fastble.** { *; }
-keep class com.sun.jna.** { *; }
# JNA builds a proxy from this interface and resolves its method names at
# runtime. R8 must not merge, rename, or rewrite the interface.
-keep interface jp.rimtty.codematch.scanner.inateck.InateckScannerCmdJna$Api { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
-dontwarn java.awt.**
