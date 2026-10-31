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
            // arm64-v8a for the Pixel; x86_64 so the emulator runs the same native code.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DBUILD_SHARED_LIBS=ON",
                    "-DLLAMA_BUILD_COMMON=OFF",
                    "-DLLAMA_BUILD_TESTS=OFF",
                    "-DLLAMA_BUILD_TOOLS=OFF",
                    "-DLLAMA_BUILD_EXAMPLES=OFF",
                    "-DLLAMA_BUILD_SERVER=OFF",
                    "-DLLAMA_BUILD_APP=OFF",
                    "-DLLAMA_BUILD_MTMD=ON",
                    "-DLLAMA_OPENSSL=OFF",
                    "-DLLAMA_SUBPROCESS=OFF",
                    "-DGGML_NATIVE=OFF",
                    "-DGGML_BACKEND_DL=ON",
                    "-DGGML_CPU_ALL_VARIANTS=ON",
                    "-DGGML_LLAMAFILE=OFF",
                )
            }
        }
    }

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
        debug {
            // Native code is always built optimised: a debug llama.cpp is far too slow to chat with.
            externalNativeBuild { cmake { arguments += "-DCMAKE_BUILD_TYPE=Release" } }
        }
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
        // Extracted to disk: llama.cpp picks its CPU backend at run time by loading libggml-cpu-*.so from nativeLibraryDir.
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
                "com.google.j2objc:j2objc-annotations", "com.google.ai.edge.litertlm:litertlm-android", "com.google.code.gson:gson"))
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
