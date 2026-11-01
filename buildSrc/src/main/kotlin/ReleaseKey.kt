import org.gradle.api.GradleException
import org.gradle.api.Project
import java.io.File
import java.util.Properties

/**
 * The owner's release key, from `keystore.properties` at the top of the repo (never committed; see README):
 *
 *     storeFile=/path/to/inkling-release.jks
 *     storePassword=…
 *     keyAlias=inkling
 *     keyPassword=…
 *
 * Without the file, release builds come out unsigned (and never with the public debug key).
 */
class ReleaseKey(val storeFile: File, val storePassword: String, val keyAlias: String, val keyPassword: String)

fun Project.releaseKey(): ReleaseKey? {
    val f = rootProject.file("keystore.properties")
    if (!f.exists()) return null
    val p = Properties().apply { f.inputStream().use { load(it) } }
    fun need(k: String) = p.getProperty(k)?.takeIf { it.isNotBlank() } ?: throw GradleException("keystore.properties has no $k")
    val store = rootProject.file(need("storeFile"))
    if (!store.exists()) throw GradleException("keystore.properties: no key store at $store")
    return ReleaseKey(store, need("storePassword"), need("keyAlias"), need("keyPassword"))
}
