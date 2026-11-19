// Compile-time stand-ins for the Xposed API (de.robv.android.xposed:api:82).
// The app depends on this with compileOnly; LSPosed provides the real classes at runtime.
plugins { `java-library` }
java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}
