# SDK consumer rules preserve native/JNA entry points. Remove vendor raw logging.
-assumenosideeffects class android.util.Log {
    public static *** *(...);
}
