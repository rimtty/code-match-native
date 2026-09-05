# ML Kit discovers these registrars from Manifest metadata and instantiates
# them reflectively. The transitive firebase-components 16.1.0 rule keeps
# class names but not constructors under the current R8 configuration.
# Without explicit constructor retention, getClient() returns a missing
# component and camera startup fails in the minified scannerPoc variant.
-keep class com.google.mlkit.** implements com.google.firebase.components.ComponentRegistrar {
    public <init>();
}
