import com.android.build.api.artifact.SingleArtifact

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.lfmpixel.lfm"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.lfmpixel.lfm"
        minSdk = 36
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            // arm64-v8a for the Pixel; x86_64 so the emulator runs the same LiteRT natives.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild { cmake { arguments += "-DCMAKE_BUILD_TYPE=Release" } }
    }

    // One small library since LFMP-8: OmniVoice's bridge to LiteRT's C API (omni_rt.cpp). No llama.cpp.
    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
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

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Robolectric reaches into FileDescriptor on JDK 17+.
        unitTests.all { it.jvmArgs("--add-opens=java.base/java.io=ALL-UNNAMED", "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED") }
    }

    packaging {
        // Extracted to disk, as LiteRT-LM loads its GPU accelerator from nativeLibraryDir.
        jniLibs { useLegacyPackaging = true }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(project(":ipc"))
    // CameraX for the "Ask about this" viewfinder; frames stay in this process.
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    // camera-view's video recorder (and media3, which asks for network state) is not used: frames come from ImageAnalysis.
    implementation(libs.camera.view) { exclude(group = "androidx.camera", module = "camera-video"); exclude(group = "androidx.media3") }
    // LiteRT-LM runs the chat model on the GPU (docs/acceleration.md). Google's on-device runtime: no Play Services,
    // no network code of its own, and Inkling has no INTERNET permission anyway (checkDebugNoNetwork).
    implementation(libs.litertlm.android)
    // LiteRT runs the graphs LiteRT-LM can't: Omni's voice (voice/OmniRt.kt) and d1's decisions (docs/model-pool.md), on
    // the CPU, no network code. Its Play asset delivery (AiPackModelProvider, Play Services) is left out: models come
    // from the app's own downloads.
    implementation(libs.litert) {
        exclude(group = "com.google.android.play")
        exclude(group = "com.google.android.gms")
    }

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}

// --- Privacy checks (docs/spec.md section 3; task classes in buildSrc) ---------------------------

androidComponents {
    onVariants { variant ->
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val manifestCheck = tasks.register<CheckManifestPermissions>("check${name}NoNetwork") {
            manifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            banned.set(listOf("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE",
                "android.permission.CHANGE_NETWORK_STATE", "android.permission.ACCESS_WIFI_STATE"))
            allowed.set(emptyList())
            failure.set("LFM must not ask for network access, but the merged manifest has")
            report.set(layout.buildDirectory.file("reports/privacy/${variant.name}-no-network.txt"))
        }
        val runtime = configurations.named("${variant.name}RuntimeClasspath")
        val depsCheck = tasks.register<CheckDependencyAllowList>("check${name}Dependencies") {
            modules.set(runtime.flatMap { c -> c.incoming.resolutionResult.rootComponent.map { runtimeModules(it) } })
            // okio is DataStore's file I/O; it has no networking of its own. offline-android:ipc is the AIDL to LFM Net.
            // CameraX brings Guava, Dagger (its internal wiring) and annotation-only jars: plain Java, no networking.
            // LiteRT-LM (the on-device runtime) brings Gson for its JSON tool specs.
            allowed.set(baseAllowList + listOf("com.squareup.okio:", "offline-android:ipc",
                "com.google.guava:", "com.google.dagger:dagger", "com.google.auto.value:auto-value-annotations",
                "jakarta.inject:", "javax.inject:", "com.google.code.findbugs:jsr305", "org.checkerframework:checker-qual",
                "com.google.j2objc:j2objc-annotations", "com.google.ai.edge.litertlm:litertlm-android", "com.google.code.gson:gson",
                // LiteRT (OmniVoice's graphs, voice/OmniRt.kt): the on-device runtime, no networking; it brings Guava's failureaccess.
                "com.google.ai.edge.litert:litert", "com.google.ai.edge.litert:litert-api", "com.google.guava:failureaccess"))
            report.set(layout.buildDirectory.file("reports/privacy/${variant.name}-dependencies.txt"))
        }
        tasks.matching { it.name == "assemble$name" || it.name == "check" }.configureEach {
            dependsOn(manifestCheck, depsCheck)
        }
        // LFM Net goes inside LFM, signed with the same key, for "Install LFM Net" (NetInstaller).
        val bundle = tasks.register<BundleApk>("bundle${name}LfmNet") {
            dependsOn(":net:assemble$name")
            apkDir.set(project(":net").layout.buildDirectory.dir("outputs/apk/${variant.name}"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(bundle, BundleApk::outDir)
    }
}
