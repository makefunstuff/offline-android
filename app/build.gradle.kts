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

    buildTypes {
        debug {
            // Native code is always built optimised: a debug llama.cpp is far too slow to chat with.
            externalNativeBuild { cmake { arguments += "-DCMAKE_BUILD_TYPE=Release" } }
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
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
        jniLibs { useLegacyPackaging = false }
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

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}

// --- Privacy checks (docs/spec.md section 3) -------------------------------------------------

/** Fails the build if the merged manifest asks for a network permission. */
abstract class CheckNoNetworkPermission : DefaultTask() {
    @get:InputFile
    abstract val manifest: RegularFileProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val text = manifest.get().asFile.readText()
        val banned = listOf("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.CHANGE_NETWORK_STATE", "android.permission.ACCESS_WIFI_STATE")
        val found = banned.filter { Regex("""android:name="${Regex.escape(it)}"""").containsMatchIn(text) }
        if (found.isNotEmpty()) {
            throw GradleException("LFM must not ask for network access, but the merged manifest has: $found")
        }
        report.get().asFile.writeText("OK: no network permission in ${manifest.get().asFile.name}\n")
    }
}

/** Fails the build if a dependency outside the allow-list ends up in the app (no analytics, crash or network SDKs). */
abstract class CheckDependencyAllowList : DefaultTask() {
    @get:Input
    abstract val modules: ListProperty<String>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val allowed = listOf("androidx.", "org.jetbrains.kotlin", "org.jetbrains.kotlinx", "org.jetbrains:annotations",
            "org.jspecify", "com.google.guava:listenablefuture", "com.google.errorprone:error_prone_annotations",
            "com.squareup.okio:") // okio is DataStore's file I/O; it has no networking of its own
        val bad = modules.get().filterNot { m -> allowed.any { m.startsWith(it) } }
        if (bad.isNotEmpty()) {
            throw GradleException("Dependencies outside the allow-list: $bad")
        }
        report.get().asFile.writeText(modules.get().sorted().joinToString("\n", postfix = "\n"))
    }
}

androidComponents {
    onVariants { variant ->
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val manifestCheck = tasks.register<CheckNoNetworkPermission>("check${name}NoNetwork") {
            manifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            report.set(layout.buildDirectory.file("reports/privacy/${variant.name}-no-network.txt"))
        }
        val runtime = configurations.named("${variant.name}RuntimeClasspath")
        val depsCheck = tasks.register<CheckDependencyAllowList>("check${name}Dependencies") {
            modules.set(runtime.flatMap { c ->
                c.incoming.resolutionResult.rootComponent.map { root ->
                    val seen = mutableSetOf<String>()
                    fun walk(c: org.gradle.api.artifacts.result.ResolvedComponentResult) {
                        c.dependencies.filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>().forEach {
                            val id = it.selected.moduleVersion ?: return@forEach
                            if (seen.add("${id.group}:${id.name}")) walk(it.selected)
                        }
                    }
                    walk(root)
                    seen.toList()
                }
            })
            report.set(layout.buildDirectory.file("reports/privacy/${variant.name}-dependencies.txt"))
        }
        tasks.matching { it.name == "assemble$name" || it.name == "check" }.configureEach {
            dependsOn(manifestCheck, depsCheck)
        }
    }
}
