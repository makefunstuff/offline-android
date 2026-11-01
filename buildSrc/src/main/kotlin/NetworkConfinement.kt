import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/**
 * The network stays in one module (docs/spec.md section 3). Fails the build if any code in the app other than the
 * modules in [allowed] (:net and the OkHttp it uses) could open a connection: the app's own classes, every library's
 * classes, and every library's native code are read, and any reference to a socket, an HTTP client, a URL connection
 * or a DNS lookup is reported. [exceptions] lists the few library classes that hold such code without the app ever
 * calling it (a helper for reading a URL, say), each with why it is harmless; the app's own code has no exceptions.
 */
abstract class CheckNetworkConfinement : DefaultTask() {
    /** The app's own compiled classes, as AGP hands them over (jars and directories). */
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val projectJars: ListProperty<RegularFile>

    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val projectDirs: ListProperty<Directory>

    /** Each library's classes jar, and each library's native libraries (jni directories). */
    @get:Internal
    abstract val libraryClasses: SetProperty<ResolvedArtifactResult>

    @get:Internal
    abstract val libraryNatives: SetProperty<ResolvedArtifactResult>

    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val libraryFiles: ConfigurableFileCollection

    /** Every native library that goes into the APK (64-bit only, as Wren ships), the app's own among them. */
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val packagedNatives: ConfigurableFileCollection

    /** Modules allowed to use the network: ":net" for a project, "group:name" for a library. */
    @get:Input
    abstract val allowed: ListProperty<String>

    /** "group:name" to the classes in it (a/b/C) allowed to hold network code, with why. */
    @get:Input
    abstract val exceptions: MapProperty<String, List<String>>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val found = mutableListOf<String>()
        val scanned = mutableListOf<String>()
        var own = 0
        (projectJars.get().map { it.asFile } + projectDirs.get().map { it.asFile }).forEach { f ->
            classes(f).forEach { (name, bytes) -> own++; (hits(bytes) + hits(bytes, APP_TYPES)).forEach { found += "app: $name uses $it" } }
        }
        if (own == 0) throw GradleException("No app classes to check: the check is wired wrong")
        scanned += "app: $own classes"
        val allow = allowed.get().toSet()
        val except = exceptions.get()
        libraryClasses.get().forEach { a ->
            val id = idOf(a)
            if (id in allow) { scanned += "$id (allowed)"; return@forEach }
            val ok = except[id].orEmpty().toSet()
            classes(a.file).forEach { (name, bytes) ->
                if (name.removeSuffix(".class").substringBefore('$') !in ok) hits(bytes).forEach { found += "$id: $name uses $it" }
            }
            scanned += id
        }
        libraryNatives.get().forEach { a ->
            val id = idOf(a)
            if (id in allow) return@forEach
            a.file.walkTopDown().filter { it.isFile && it.name.endsWith(".so") }.forEach { so ->
                scanned += "$id: ${so.parentFile.name}/${so.name} (native, ${imports(so).size} imports)"
                imports(so).filter { it in NATIVE }.forEach { found += "$id: ${so.name} (native) imports $it()" }
            }
        }
        // The APK's own copy: also the app's native code, which comes from no library. A library's own .so was read above.
        val fromLibs = libraryNatives.get().filter { idOf(it) !in allow }.flatMap { a -> a.file.walkTopDown().filter { it.name.endsWith(".so") }.map { it.name }.toList() }.toSet()
        packagedNatives.files.flatMap { d -> d.walkTopDown().filter { it.isFile && it.name.endsWith(".so") }.toList() }.forEach { so ->
            if (so.name in fromLibs) return@forEach
            scanned += "packaged: ${so.parentFile.name}/${so.name} (native, ${imports(so).size} imports)"
            imports(so).filter { it in NATIVE }.forEach { found += "app: ${so.name} (native) imports $it()" }
        }
        if (found.isNotEmpty()) throw GradleException(
            "Network code outside the network module (only ${allow.joinToString()} may open connections):\n  " + found.distinct().joinToString("\n  "))
        report.get().asFile.writeText("OK: no network code outside ${allow.joinToString()}\nScanned:\n" + scanned.sorted().joinToString("\n", postfix = "\n"))
    }

    private fun idOf(a: ResolvedArtifactResult): String = when (val c = a.id.componentIdentifier) {
        is ProjectComponentIdentifier -> c.projectPath
        is ModuleComponentIdentifier -> "${c.group}:${c.module}"
        else -> c.displayName
    }

    /** Class files in a directory or a jar, by name. */
    private fun classes(f: File): Sequence<Pair<String, ByteArray>> = when {
        f.isDirectory -> f.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.map { it.relativeTo(f).path to it.readBytes() }
        f.isFile && f.name.endsWith(".jar") -> ZipFile(f).use { z ->
            z.entries().asSequence().filter { it.name.endsWith(".class") && !it.name.startsWith("META-INF/") }
                .map { it.name to z.getInputStream(it).readBytes() }.toList().asSequence()
        }
        else -> emptySequence()
    }

    companion object {
        /** Types whose only use is talking over the network. */
        val TYPES = setOf(
            "java/net/Socket", "java/net/ServerSocket", "java/net/DatagramSocket", "java/net/MulticastSocket",
            "java/net/URLConnection", "java/net/HttpURLConnection", "javax/net/ssl/HttpsURLConnection",
            "javax/net/SocketFactory", "javax/net/ServerSocketFactory", "javax/net/ssl/SSLSocketFactory", "javax/net/ssl/SSLSocket",
            "java/nio/channels/SocketChannel", "java/nio/channels/ServerSocketChannel", "java/nio/channels/DatagramChannel",
            "java/nio/channels/AsynchronousSocketChannel", "java/nio/channels/AsynchronousServerSocketChannel",
            "android/net/SSLCertificateSocketFactory", "android/webkit/WebView",
        )
        /** Whole packages of HTTP clients and cloud SDKs. */
        val PACKAGES = listOf("okhttp3/", "com/android/okhttp/", "org/apache/http/", "android/net/http/", "java/net/http/",
            "org/chromium/net/", "io/grpc/", "io/ktor/", "com/google/firebase/", "com/google/android/gms/")
        /** A type plus one of its methods that goes out: opening a URL, a DNS lookup, a network's own sockets. */
        val CALLS = mapOf(
            "java/net/URL" to setOf("openConnection", "openStream"),
            "java/net/InetAddress" to setOf("getByName", "getAllByName"),
            "android/net/Network" to setOf("openConnection", "getSocketFactory", "bindSocket", "getAllByName", "getByName"),
        )
        /** Native functions that open a connection or look a name up. */
        val NATIVE = setOf("socket", "connect", "getaddrinfo", "gethostbyname", "gethostbyname2", "gethostbyname_r",
            "android_getaddrinfofornetwork", "android_setsocknetwork")

        private fun refers(u: String, type: String) = u == type || u.contains("L$type;") || u == "[L$type;"
        private fun refersPkg(u: String, pkg: String) = u.startsWith(pkg) || u.contains("L$pkg")

        /** Stricter for the app's own code: no addresses, lookups or proxies of its own; those are :net's. */
        val APP_TYPES = setOf("java/net/URL", "java/net/InetAddress", "java/net/Inet4Address", "java/net/Inet6Address",
            "java/net/InetSocketAddress", "java/net/Proxy")

        /** What in this class file reaches the network, read from its constant pool. */
        fun hits(bytes: ByteArray, types: Set<String> = TYPES): List<String> {
            val strings = utf8s(bytes) ?: return emptyList()
            val out = mutableListOf<String>()
            if (types !== TYPES) return types.filter { t -> strings.any { refers(it, t) } }.map { it.replace('/', '.') }
            TYPES.filter { t -> strings.any { refers(it, t) } }.forEach { out += it.replace('/', '.') }
            PACKAGES.filter { p -> strings.any { refersPkg(it, p) } }.forEach { out += it.replace('/', '.') + "*" }
            CALLS.forEach { (t, methods) ->
                if (strings.any { refers(it, t) }) methods.filter { it in strings }.forEach { m -> out += "${t.replace('/', '.')}.$m" }
            }
            return out
        }

        /** The UTF-8 entries of a class file's constant pool; null if it isn't one. */
        fun utf8s(bytes: ByteArray): Set<String>? {
            val d = DataInputStream(bytes.inputStream())
            if (d.readInt() != 0xCAFEBABE.toInt()) return null
            d.readUnsignedShort(); d.readUnsignedShort()
            val n = d.readUnsignedShort()
            val out = HashSet<String>()
            var i = 1
            while (i < n) {
                when (val tag = d.readUnsignedByte()) {
                    1 -> out += d.readUTF()
                    3, 4 -> d.skipBytes(4)
                    5, 6 -> { d.skipBytes(8); i++ }
                    7, 8, 16, 19, 20 -> d.skipBytes(2)
                    9, 10, 11, 12, 17, 18 -> d.skipBytes(4)
                    15 -> d.skipBytes(3)
                    else -> throw GradleException("Unknown constant pool tag $tag")
                }
                i++
            }
            return out
        }

        /** Undefined symbols in a 64-bit ELF's dynamic symbol table: what it imports. */
        fun imports(f: File): Set<String> {
            val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            if (b.limit() < 64 || b.getInt(0) != 0x464c457f || b.get(4).toInt() != 2) return emptySet()
            val shoff = b.getLong(0x28).toInt()
            val shentsize = b.getShort(0x3A).toInt() and 0xffff
            val shnum = b.getShort(0x3C).toInt() and 0xffff
            fun sh(i: Int) = shoff + i * shentsize
            val out = HashSet<String>()
            for (i in 0 until shnum) {
                if (b.getInt(sh(i) + 4) != 11) continue // SHT_DYNSYM
                val off = b.getLong(sh(i) + 0x18).toInt()
                val size = b.getLong(sh(i) + 0x20).toInt()
                val entsize = b.getLong(sh(i) + 0x38).toInt().takeIf { it > 0 } ?: 24
                val strOff = b.getLong(sh(b.getInt(sh(i) + 0x28)) + 0x18).toInt()
                for (s in 0 until size / entsize) {
                    val e = off + s * entsize
                    if ((b.getShort(e + 6).toInt() and 0xffff) != 0) continue // defined here
                    var p = strOff + b.getInt(e)
                    val sb = StringBuilder()
                    while (b.get(p).toInt() != 0) sb.append(b.get(p++).toInt().toChar())
                    out += sb.toString().substringBefore('@')
                }
            }
            return out
        }
    }
}
