plugins {
    alias(libs.plugins.android.application)
}

// The last Inkling Net: an update to the old helper app whose only job is to hand its search settings (provider,
// address, keys, proxy) to Inkling, which now has the network itself (docs/spec.md section 3). No network, no
// screen, no permissions. Inkling carries it inside its APK and offers it once; then Inkling Net can be uninstalled.
android {
    namespace = "com.lfmpixel.lfm.handoff"
    compileSdk = 37

    defaultConfig {
        // The old helper's id and a higher version, so it installs as an update and can read the helper's settings.
        applicationId = "com.lfmpixel.lfm.net"
        minSdk = 36
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0-handoff"
    }

    releaseKey()?.let { key ->
        signingConfigs.create("release") {
            storeFile = key.storeFile
            storePassword = key.storePassword
            keyAlias = key.keyAlias
            keyPassword = key.keyPassword
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Same key as Inkling (and the old Inkling Net), or Android won't take it as an update.
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
