plugins {
    alias(libs.plugins.android.library)
}

// The narrow interface between LFM and LFM Net (docs/spec.md section 3). No dependencies, no permissions.
android {
    namespace = "com.lfmpixel.lfm.ipc"
    compileSdk = 37
    defaultConfig { minSdk = 36 }
    buildFeatures { aidl = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
