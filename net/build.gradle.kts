plugins {
    alias(libs.plugins.android.library)
}

// The network module: the only code in Inkling that may open a connection (docs/spec.md section 3). Search, fetch,
// pinned model downloads and Edge requests to the owner's server; :app:checkDebugNetworkConfinement fails the build
// if any other module or library uses the network.
android {
    namespace = "com.lfmpixel.lfm.net"
    compileSdk = 37
    defaultConfig { minSdk = 36 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.okhttp.mockwebserver)
}
