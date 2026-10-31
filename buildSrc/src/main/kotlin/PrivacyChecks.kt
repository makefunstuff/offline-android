import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

// Privacy checks (docs/spec.md section 3), shared by LFM and LFM Net.

/**
 * Fails the build if the merged manifest asks for a permission in [banned], or, when [allowed] is set, for any
 * android.permission outside it.
 */
abstract class CheckManifestPermissions : DefaultTask() {
    @get:InputFile
    abstract val manifest: RegularFileProperty

    @get:Input
    abstract val banned: ListProperty<String>

    @get:Input
    abstract val allowed: ListProperty<String>

    @get:Input
    abstract val failure: org.gradle.api.provider.Property<String>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val text = manifest.get().asFile.readText()
        val asked = Regex("""<uses-permission[^>]*android:name="([^"]+)"""").findAll(text).map { it.groupValues[1] }.toList()
        val found = asked.filter { it in banned.get() } +
            if (allowed.get().isEmpty()) emptyList() else asked.filter { it.startsWith("android.permission.") && it !in allowed.get() }
        if (found.isNotEmpty()) throw GradleException("${failure.get()}: $found")
        report.get().asFile.writeText("OK: ${asked.sorted()} in ${manifest.get().asFile.name}\n")
    }
}

/** Fails the build if a dependency outside the allow-list ends up in the app (no analytics, crash or network SDKs). */
abstract class CheckDependencyAllowList : DefaultTask() {
    @get:Input
    abstract val modules: ListProperty<String>

    @get:Input
    abstract val allowed: ListProperty<String>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val bad = modules.get().filterNot { m -> allowed.get().any { m.startsWith(it) } }
        if (bad.isNotEmpty()) {
            throw GradleException("Dependencies outside the allow-list: $bad")
        }
        report.get().asFile.writeText(modules.get().sorted().joinToString("\n", postfix = "\n"))
    }
}

/** Every module on a resolved runtime classpath, as group:name. */
fun runtimeModules(root: org.gradle.api.artifacts.result.ResolvedComponentResult): List<String> {
    val seen = mutableSetOf<String>()
    fun walk(c: org.gradle.api.artifacts.result.ResolvedComponentResult) {
        c.dependencies.filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>().forEach {
            val id = it.selected.moduleVersion ?: return@forEach
            if (seen.add("${id.group}:${id.name}")) walk(it.selected)
        }
    }
    walk(root)
    return seen.toList()
}

/** Libraries both apps may use: AndroidX, Kotlin and their annotations. */
val baseAllowList = listOf("androidx.", "org.jetbrains.kotlin", "org.jetbrains.kotlinx", "org.jetbrains:annotations",
    "org.jspecify", "com.google.guava:listenablefuture", "com.google.errorprone:error_prone_annotations")

/** Copies LFM Net's built APK into LFM's assets as lfm-net.apk, so LFM can offer to install it. */
abstract class BundleApk : DefaultTask() {
    @get:org.gradle.api.tasks.InputDirectory
    abstract val apkDir: org.gradle.api.file.DirectoryProperty

    @get:org.gradle.api.tasks.OutputDirectory
    abstract val outDir: org.gradle.api.file.DirectoryProperty

    @TaskAction
    fun copy() {
        val apk = apkDir.get().asFile.listFiles().orEmpty().singleOrNull { it.name.endsWith(".apk") }
            ?: throw GradleException("Expected one LFM Net APK in ${apkDir.get().asFile}")
        val out = outDir.get().asFile
        out.deleteRecursively(); out.mkdirs()
        apk.copyTo(java.io.File(out, "lfm-net.apk"))
    }
}
