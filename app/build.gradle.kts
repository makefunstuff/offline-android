import com.android.build.api.artifact.SingleArtifact

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    // JVM screenshots of the screens (Robolectric, no emulator): ScreenshotTest.
    alias(libs.plugins.roborazzi)
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
        unitTests.all {
            it.jvmArgs("--add-opens=java.base/java.io=ALL-UNNAMED", "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
            // ScreenshotTest writes its pictures on every run (build/outputs/roborazzi), to look at; nothing is compared.
            it.systemProperty("roborazzi.test.record", "true")
        }
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
    // The network module: the only code allowed to open a connection (checkDebugNetworkConfinement below).
    implementation(project(":net"))
    // CameraX for the "Ask about this" viewfinder; frames stay in this process.
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    // camera-view's video recorder (and media3, which asks for network state) is not used: frames come from ImageAnalysis.
    implementation(libs.camera.view) { exclude(group = "androidx.camera", module = "camera-video"); exclude(group = "androidx.media3") }
    // LiteRT-LM runs the chat model on the GPU (docs/acceleration.md). Google's on-device runtime: no Play Services,
    // no network code of its own (checkDebugNetworkConfinement reads it, native code too).
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
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
}

// --- Privacy checks (docs/spec.md section 3; task classes in buildSrc) ---------------------------

androidComponents {
    onVariants { variant ->
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val manifestCheck = tasks.register<CheckManifestPermissions>("check${name}Permissions") {
            manifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            banned.set(emptyList())
            // Exactly these, so a library can't add one: INTERNET and network state come from :net.
            allowed.set(listOf("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE",
                "android.permission.POST_NOTIFICATIONS", "android.permission.USE_BIOMETRIC", "android.permission.USE_EXACT_ALARM",
                "android.permission.RECEIVE_BOOT_COMPLETED", "android.permission.RECORD_AUDIO", "android.permission.CAMERA",
                "android.permission.READ_CALENDAR", "android.permission.WRITE_CALENDAR", "android.permission.READ_CONTACTS",
                "android.permission.ACCESS_NOTIFICATION_POLICY", "android.permission.FOREGROUND_SERVICE",
                "android.permission.FOREGROUND_SERVICE_DATA_SYNC", "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
                "android.permission.REQUEST_INSTALL_PACKAGES", "android.permission.REQUEST_DELETE_PACKAGES"))
            failure.set("Inkling asks for a permission outside its list (app/build.gradle.kts)")
            report.set(layout.buildDirectory.file("reports/privacy/${variant.name}-permissions.txt"))
        }
        val runtime = configurations.named("${variant.name}RuntimeClasspath")
        val depsCheck = tasks.register<CheckDependencyAllowList>("check${name}Dependencies") {
            modules.set(runtime.flatMap { c -> c.incoming.resolutionResult.rootComponent.map { runtimeModules(it) } })
            // okio is DataStore's file I/O (and OkHttp's). offline-android:net is the network module, with OkHttp.
            // CameraX brings Guava, Dagger (its internal wiring) and annotation-only jars: plain Java, no networking.
            // LiteRT-LM (the on-device runtime) brings Gson for its JSON tool specs.
            allowed.set(baseAllowList + listOf("com.squareup.okio:", "offline-android:net", "com.squareup.okhttp3:okhttp",
                "com.google.guava:", "com.google.dagger:dagger", "com.google.auto.value:auto-value-annotations",
                "jakarta.inject:", "javax.inject:", "com.google.code.findbugs:jsr305", "org.checkerframework:checker-qual",
                "com.google.j2objc:j2objc-annotations", "com.google.ai.edge.litertlm:litertlm-android", "com.google.code.gson:gson",
                // LiteRT (OmniVoice's graphs, voice/OmniRt.kt): the on-device runtime, no networking; it brings Guava's failureaccess.
                "com.google.ai.edge.litert:litert", "com.google.ai.edge.litert:litert-api", "com.google.guava:failureaccess"))
            report.set(layout.buildDirectory.file("reports/privacy/${variant.name}-dependencies.txt"))
        }
        fun libs(type: String) = runtime.get().incoming.artifactView {
            attributes { attribute(org.gradle.api.attributes.Attribute.of("artifactType", String::class.java), type) }
        }.artifacts
        val confinement = tasks.register<CheckNetworkConfinement>("check${name}NetworkConfinement") {
            val classes = libs("android-classes-jar")
            val natives = libs("android-jni")
            libraryClasses.set(classes.resolvedArtifacts)
            libraryNatives.set(natives.resolvedArtifacts)
            libraryFiles.from(classes.artifactFiles, natives.artifactFiles)
            packagedNatives.from(tasks.named("merge${name}NativeLibs"))
            // The network module and the HTTP client it uses (with okio, its I/O).
            allowed.set(listOf(":net", "com.squareup.okhttp3:okhttp", "com.squareup.okhttp3:okhttp-android", "com.squareup.okio:okio-jvm", "com.squareup.okio:okio"))
            // Library classes that hold network code the app never reaches, each with why. Anything new fails the build.
            exceptions.set(mapOf(
                // Tag or wrap a socket someone else opened (TrafficStats); Linkify's WebView.findAddress reads text only.
                "androidx.core:core" to listOf("androidx/core/net/DatagramSocketWrapper", "androidx/core/net/TrafficStatsCompat",
                    "androidx/core/text/util/LinkifyCompat"),
                // Play asset delivery: Play Services are excluded from the build, so it can't run; models come from :net.
                "com.google.ai.edge.litert:litert-api" to listOf("com/google/ai/edge/litert/deployment/AiPackModelProvider"),
                // Read META-INF/services and bundled resources from the APK itself (jar: addresses, no network).
                "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm" to listOf("kotlinx/coroutines/internal/FastServiceLoader"),
                "org.jetbrains.kotlin:kotlin-reflect" to listOf("kotlin/reflect/jvm/internal/impl/serialization/deserialization/builtins/BuiltInsResourceLoader"),
                // URL.readText()/readBytes() helpers; the app's own code may not touch java.net.URL at all.
                "org.jetbrains.kotlin:kotlin-stdlib" to listOf("kotlin/io/TextStreamsKt"),
                // Guava (CameraX's futures): resource reading and address parsing helpers nobody here calls.
                "com.google.guava:guava" to listOf("com/google/common/io/Resources", "com/google/common/net/InetAddresses"),
                // Gson's InetAddress adapter, only for JSON fields of that type; LiteRT-LM's tool specs have none.
                "com.google.code.gson:gson" to listOf("com/google/gson/internal/bind/TypeAdapters"),
            ))
            report.set(layout.buildDirectory.file("reports/privacy/${variant.name}-network.txt"))
        }
        variant.artifacts.forScope(com.android.build.api.variant.ScopedArtifacts.Scope.PROJECT).use(confinement)
            .toGet(com.android.build.api.artifact.ScopedArtifact.CLASSES, CheckNetworkConfinement::projectJars, CheckNetworkConfinement::projectDirs)
        tasks.matching { it.name == "assemble$name" || it.name == "check" }.configureEach {
            dependsOn(manifestCheck, depsCheck, confinement)
        }
        // The last Inkling Net goes inside Inkling, signed with the same key, to bring its settings over (NetHandoff).
        val bundle = tasks.register<BundleApk>("bundle${name}Handoff") {
            dependsOn(":handoff:assemble$name")
            apkDir.set(project(":handoff").layout.buildDirectory.dir("outputs/apk/${variant.name}"))
            assetName.set("inkling-net-handoff.apk")
        }
        variant.sources.assets?.addGeneratedSourceDirectory(bundle, BundleApk::outDir)
    }
}
