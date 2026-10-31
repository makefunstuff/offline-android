import com.android.build.api.artifact.SingleArtifact

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// LFM Net: the small helper that is the only part with network access (docs/spec.md section 3).
// It must be signed with the same key as LFM: its service is guarded by a signature permission.
android {
    namespace = "com.lfmpixel.lfm.net"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.lfmpixel.lfm.net"
        minSdk = 36
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
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
            // The owner's key from keystore.properties (ReleaseKey.kt); unsigned without it, never the debug key.
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }
}

dependencies {
    implementation(project(":ipc"))
    implementation(libs.okhttp)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.okhttp.mockwebserver)
}

// --- Privacy checks (task classes in buildSrc) ----------------------------------------------------

androidComponents {
    onVariants { variant ->
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val manifestCheck = tasks.register<CheckManifestPermissions>("check${name}Permissions") {
            manifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            banned.set(emptyList())
            // The network, and nothing else: no contacts, files, location or phone state.
            allowed.set(listOf("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE"))
            failure.set("LFM Net may only ask for network access, but the merged manifest has")
            report.set(layout.buildDirectory.file("reports/privacy/${variant.name}-permissions.txt"))
        }
        val runtime = configurations.named("${variant.name}RuntimeClasspath")
        val depsCheck = tasks.register<CheckDependencyAllowList>("check${name}Dependencies") {
            modules.set(runtime.flatMap { c -> c.incoming.resolutionResult.rootComponent.map { runtimeModules(it) } })
            // OkHttp and its okio, and nothing else (spec section 7, tech stack).
            allowed.set(baseAllowList + listOf("com.squareup.okhttp3:okhttp", "com.squareup.okio:", "offline-android:ipc"))
            report.set(layout.buildDirectory.file("reports/privacy/${variant.name}-dependencies.txt"))
        }
        tasks.matching { it.name == "assemble$name" || it.name == "check" }.configureEach {
            dependsOn(manifestCheck, depsCheck)
        }
    }
}
