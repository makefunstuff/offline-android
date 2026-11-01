package com.lfmpixel.lfm.llm

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File

/** A model file in the app's own storage, checked against its pinned hash when it was imported. */
data class InstalledModel(val known: KnownModel, val file: File, val verified: Boolean)

/** Where models live (the app's private files) and how they get there (import from a file the owner picks). */
class ModelStore(private val context: Context) {
    val dir = File(context.filesDir, "models").apply { mkdirs() }
    private val _installed = MutableStateFlow(scan())
    val installed: StateFlow<List<InstalledModel>> = _installed

    private fun scan(): List<InstalledModel> = dir.listFiles().orEmpty()
        .filter { it.isFile }
        .mapNotNull { f ->
            val known = ModelCatalog.byName(f.name) ?: return@mapNotNull null
            InstalledModel(known, f, verified = File(dir, f.name + ".sha256").let { it.exists() && it.readText().trim() == known.sha256 })
        }
        .sortedBy { it.known.role.ordinal }

    fun refresh() { _installed.value = scan() }

    // With two of a kind (Q4 and Q8), the one added last is used: a new download or import takes over.
    private fun newest(role: ModelRole) = _installed.value.filter { it.known.role == role && it.verified }
        .maxByOrNull { File(dir, it.file.name + ".sha256").lastModified() }
    fun text(): InstalledModel? = newest(ModelRole.Text)
    /** The chat model the owner picked, if it is still here; else the text model added last. */
    fun text(preferred: String): InstalledModel? =
        _installed.value.firstOrNull { it.known.role == ModelRole.Text && it.verified && it.file.name == preferred } ?: text()
    fun omni(): InstalledModel? = newest(ModelRole.Omni)
    /** Every voice graph Omni needs is here and checked. */
    fun omniSpeech(): Boolean = ModelCatalog.all.filter { it.role == ModelRole.OmniSpeech }
        .all { m -> _installed.value.any { it.known == m && it.verified } }
    fun tokenizer(): InstalledModel? = newest(ModelRole.Tokenizer)
    fun vision(): InstalledModel? = newest(ModelRole.Vision)
    fun embedder(): InstalledModel? = newest(ModelRole.Embed)
    /** Every d1 file is here and checked. */
    fun decide(): Boolean = ModelCatalog.all.filter { it.role == ModelRole.Decide }
        .all { m -> _installed.value.any { it.known == m && it.verified } }

    /**
     * Model files from before LFMP-8 (GGUF for llama.cpp, which the phone no longer runs) and files of an older
     * pin: nothing uses them, so the Models screen offers to free the space.
     */
    fun leftovers(): List<File> = dir.listFiles().orEmpty().filter { f ->
        f.isFile && (f.name.endsWith(".gguf") || f.name.endsWith(".gguf.sha256") ||
            (f.name.endsWith(".litertlm") && ModelCatalog.byName(f.name) == null))
    }

    fun deleteLeftovers(): Long = leftovers().sumOf { f -> f.length().also { f.delete() } }.also { refresh() }

    fun delete(m: InstalledModel) {
        m.file.delete()
        // Omni's XNNPACK weight cache (about 2.7 GB) is made from its .litertlm: it goes with it.
        if (m.known.role == ModelRole.Omni) File(dir, com.lfmpixel.lfm.voice.OmniVoice.CACHE_DIR).deleteRecursively()
        File(dir, m.file.name + ".sha256").delete()
        refresh()
    }

    fun usedBytes() = dir.listFiles().orEmpty().sumOf { it.length() }
    fun freeBytes() = dir.usableSpace

    data class Picked(val uri: Uri, val name: String, val size: Long)

    fun describe(uri: Uri): Picked {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "model.litertlm"
        var size = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        return Picked(uri, name, size)
    }

    sealed interface Step {
        data class Copying(val done: Long, val total: Long) : Step
        data class Checked(val result: CheckResult) : Step
    }

    /**
     * Copies a picked file into the app while hashing it, then checks the hash against the pinned one.
     * A file that is not a known model, or whose hash does not match, is not kept.
     */
    suspend fun import(p: Picked, onStep: (Step) -> Unit): CheckResult = withContext(Dispatchers.IO) {
        val pre = ModelCheck.precheck(p.name, p.size)
        if (pre !is CheckResult.Ok) return@withContext pre
        val model = pre.model
        val part = File(dir, model.fileName + ".part")
        val hash = try {
            context.contentResolver.openInputStream(p.uri)!!.use { input ->
                part.outputStream().use { out ->
                    ModelCheck.copyAndHash(input, out, onProgress = { onStep(Step.Copying(it, model.sizeBytes)) })
                }
            }
        } catch (e: Exception) {
            part.delete()
            throw e
        }
        val result = ModelCheck.verify(model, hash)
        onStep(Step.Checked(result))
        if (result is CheckResult.Ok) {
            val target = File(dir, model.fileName)
            target.delete()
            part.renameTo(target)
            File(dir, model.fileName + ".sha256").writeText(hash)
        } else {
            part.delete()
        }
        refresh()
        result
    }

    /** Puts a downloaded, hash-checked file in place. */
    fun adopt(model: KnownModel, part: File, sha256: String) {
        val target = File(dir, model.fileName)
        target.delete()
        if (!part.renameTo(target)) throw java.io.IOException("Could not move ${model.fileName} into place")
        File(dir, model.fileName + ".sha256").writeText(sha256)
        refresh()
    }

    fun partFile(model: KnownModel) = File(dir, model.fileName + ".download")

    /** Offers to free the space the original took; works where the file's provider lets us delete it. */
    fun deleteOriginal(uri: Uri): Boolean = runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }.getOrDefault(false)
}
