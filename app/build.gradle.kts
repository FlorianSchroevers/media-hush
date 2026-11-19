import java.util.Properties

plugins {
    id("com.android.application")
}

// Release signing is optional. Point MH_KEYSTORE_PROPERTIES (env var or Gradle property) at a
// properties file with storeFile, storePassword, keyAlias and keyPassword. Without it, release
// builds come out unsigned; debug builds always work.
val keystorePropsPath: String? =
    (findProperty("MH_KEYSTORE_PROPERTIES") as String?) ?: System.getenv("MH_KEYSTORE_PROPERTIES")
val keystoreProps: Properties? = keystorePropsPath?.let { path ->
    Properties().apply { file(path).inputStream().use { load(it) } }
}

android {
    namespace = "fyi.fyrnir.mediahush"
    compileSdk = 35

    defaultConfig {
        applicationId = "fyi.fyrnir.mediahush"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        if (keystoreProps != null) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    compileOnly(project(":xposed-stubs"))
}
