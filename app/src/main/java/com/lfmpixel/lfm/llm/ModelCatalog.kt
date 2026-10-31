package com.lfmpixel.lfm.llm

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

enum class ModelRole(val label: String) {
    Text("Chat, tools, writing"),
    Omni("Voice, camera, talk"),
    OmniProjector("Omni's ears and eyes"),
    Vision("Documents"),
    VisionProjector("Documents (projector)"),
    /** The chat model converted for LiteRT-LM, which runs it on the GPU (docs/acceleration.md). */
    TextLiteRt("Chat, on the GPU"),
}

/**
 * A model file we know: its exact name, size and SHA-256, pinned from Hugging Face (LiquidAI, 28 Oct 2026; the
 * LiteRT-LM file from litert-community, 29 Oct 2026).
 */
data class KnownModel(
    val fileName: String,
    val family: String,
    val quant: String,
    val role: ModelRole,
    val sizeBytes: Long,
    val sha256: String,
    val repo: String,
) {
    val sizeLabel get() = formatBytes(sizeBytes)
    val displayName get() = family
}

object ModelCatalog {
    private const val TEXT = "LiquidAI/LFM3-1.7B-GGUF"
    private const val OMNI = "LiquidAI/LFM3-Omni-2B-GGUF"
    private const val VL = "LiquidAI/LFM3-VL-2B-GGUF"
    private const val LITERT = "litert-community/LFM3-1.7B-LiteRT"

    val all = listOf(
        KnownModel("LFM3-1.7B-QAD-Q4_0.gguf", "LFM3-1.7B", "Q4_0", ModelRole.Text, 1049615680, "43a7d09a0e9cba91b63eb7052488b9564569bddfd65754d2e426465e572a7452", TEXT),
        KnownModel("LFM3-1.7B-Q4_K_M.gguf", "LFM3-1.7B", "Q4_K_M", ModelRole.Text, 1099627808, "3793e1e6e2266919d6e015287221b41100175a98eddaba92e111aed3b05c93c4", TEXT),
        KnownModel("LFM3-1.7B-Q5_K_M.gguf", "LFM3-1.7B", "Q5_K_M", ModelRole.Text, 1258663200, "41e51fb0be5f472d323055f2734d986d5a2429570973508fa6322f3f07f6d7f3", TEXT),
        KnownModel("LFM3-1.7B-Q6_K.gguf", "LFM3-1.7B", "Q6_K", ModelRole.Text, 1427638304, "54e1bd947c77167823d45bf495f1c3e2d226ca7fb242f83cfa141ee3a6ab55c6", TEXT),
        KnownModel("LFM3-1.7B-Q8_0.gguf", "LFM3-1.7B", "Q8_0", ModelRole.Text, 1846238496, "8f6b24182258205e9e01e21f1f24a1f208c1e8115578825d202ce39976af20b6", TEXT),
        KnownModel("LFM3-Omni-2B-Q4_0.gguf", "LFM3-Omni-2B", "Q4_0", ModelRole.Omni, 1049617920, "6d2273a383b92dd7c5db0eb4c8dfeae13e805b485a6621c4b391ae179c2b5f28", OMNI),
        KnownModel("LFM3-Omni-2B-Q4_K_M.gguf", "LFM3-Omni-2B", "Q4_K_M", ModelRole.Omni, 1099630080, "9f8d30e9d4fd7a9349d586e077988229b3ca0a0e4ad730897563d1eccc9893e2", OMNI),
        KnownModel("LFM3-Omni-2B-Q8_0.gguf", "LFM3-Omni-2B", "Q8_0", ModelRole.Omni, 1846240768, "59118f661eaacb93d204723d76355b53347f0940b508c11412f6290fd49158d4", OMNI),
        KnownModel("mmproj-LFM3-Omni-2B-Q4_0.gguf", "Omni projector", "Q4_0", ModelRole.OmniProjector, 270576544, "c7341273f1757b33ccd9e6f8b17007eb81307d26c90d8c36c22efb45fd3d82eb", OMNI),
        KnownModel("mmproj-LFM3-Omni-2B-Q4_K_M.gguf", "Omni projector", "Q4_K_M", ModelRole.OmniProjector, 291586720, "0a746c0794782a16d4456b230c2a0b5888a2d94a6677211edbed7d7a4177a39a", OMNI),
        KnownModel("mmproj-LFM3-Omni-2B-Q8_0.gguf", "Omni projector", "Q8_0", ModelRole.OmniProjector, 506842528, "8a25f9d1931244429de404fccacfb7fb933970a36aa5688d2f5069f29c6b27b1", OMNI),
        KnownModel("LFM3-VL-2B-QAD-Q4_0.gguf", "LFM3-VL-2B", "Q4_0", ModelRole.Vision, 1049615648, "621c455376cb96a20891697deb9975c6fb47dd5ea98ed3ad1f3b7e8f24f0b1e6", VL),
        KnownModel("LFM3-VL-2B-Q4_K_M.gguf", "LFM3-VL-2B", "Q4_K_M", ModelRole.Vision, 1099627808, "56dac2e192ff0d9ab8507f4f1aeb4aed0463449bbbefaac79497f041c95f21eb", VL),
        KnownModel("LFM3-VL-2B-Q8_0.gguf", "LFM3-VL-2B", "Q8_0", ModelRole.Vision, 1846238496, "e138e54189a751d90ea8c0b236360490613ecd465d28d68fa6094d3db7fa6727", VL),
        KnownModel("mmproj-LFM3-VL-2B-Q8_0.gguf", "VL projector", "Q8_0", ModelRole.VisionProjector, 203031872, "c5c5b58d544a18da919172d2d0368090ea1a4a351b6feb7f8a326a264a5918ab", VL),
        // MLP gate and up projections int4, the rest int8; a 4,096-token cache (the 2,048 one cuts chats short).
        KnownModel("LFM3-1.7B_mixed_int4_ekv4096.litertlm", "LFM3-1.7B LiteRT", "mixed int4", ModelRole.TextLiteRt, 1441629120, "327486bf01d0b1eefd727b7aadec8627a2b8db143349262857ac98403109d72b", LITERT),
    )

    /** A model as the Models screen lists it: what it is for, the quantisations offered, and the projector it needs. */
    data class Group(val family: String, val purpose: String, val variants: List<KnownModel>, val projector: KnownModel?)

    /** Q4 (smaller, faster) and Q8 (more accurate) of each model, as the spec has them; other pinned files can be imported. */
    val groups get() = listOf(
        Group("LFM3-1.7B", "Chat, tools and writing", listOf(byName("LFM3-1.7B-QAD-Q4_0.gguf")!!, byName("LFM3-1.7B-Q8_0.gguf")!!), null),
        Group("LFM3-Omni-2B", "Voice, photos and short videos", listOf(byName("LFM3-Omni-2B-Q4_0.gguf")!!, byName("LFM3-Omni-2B-Q8_0.gguf")!!),
            byName("mmproj-LFM3-Omni-2B-Q4_K_M.gguf")!!),
        Group("LFM3-VL-2B", "Documents: reads pages, scans and PDFs", listOf(byName("LFM3-VL-2B-QAD-Q4_0.gguf")!!, byName("LFM3-VL-2B-Q8_0.gguf")!!),
            byName("mmproj-LFM3-VL-2B-Q8_0.gguf")!!),
        Group("LFM3-1.7B LiteRT", "The chat model for LiteRT-LM, which can run it on the GPU. The benchmark below finds out which is quicker.",
            listOf(byName("LFM3-1.7B_mixed_int4_ekv4096.litertlm")!!), null),
    )

    /** What first run downloads: chat and voice (2.4 GB). */
    val essentials get() = listOf("LFM3-1.7B-QAD-Q4_0.gguf", "LFM3-Omni-2B-Q4_0.gguf", "mmproj-LFM3-Omni-2B-Q4_K_M.gguf").map { byName(it)!! }

    fun byName(fileName: String): KnownModel? = all.firstOrNull { it.fileName.equals(fileName, ignoreCase = true) }
}

sealed interface CheckResult {
    data class Ok(val model: KnownModel, val sha256: String) : CheckResult
    data class Unknown(val fileName: String) : CheckResult
    data class WrongSize(val model: KnownModel, val size: Long) : CheckResult
    data class WrongHash(val model: KnownModel, val sha256: String) : CheckResult
}

object ModelCheck {
    /** Before copying: is this a file we know, by name and size? */
    fun precheck(fileName: String, size: Long): CheckResult {
        val m = ModelCatalog.byName(fileName) ?: return CheckResult.Unknown(fileName)
        if (size >= 0 && size != m.sizeBytes) return CheckResult.WrongSize(m, size)
        return CheckResult.Ok(m, "")
    }

    fun verify(model: KnownModel, sha256: String): CheckResult =
        if (sha256.equals(model.sha256, ignoreCase = true)) CheckResult.Ok(model, sha256) else CheckResult.WrongHash(model, sha256)

    /** Copies [input] to [output] while hashing it; reports progress in bytes. Returns the hex SHA-256. */
    fun copyAndHash(input: InputStream, output: OutputStream?, onProgress: (Long) -> Unit = {}, isCancelled: () -> Boolean = { false }): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1 shl 20)
        var total = 0L
        while (true) {
            if (isCancelled()) throw InterruptedException("cancelled")
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
            output?.write(buf, 0, n)
            total += n
            onProgress(total)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

fun formatBytes(b: Long): String = when {
    b >= 1_000_000_000L -> java.math.BigDecimal(b / 1e9).setScale(2, java.math.RoundingMode.HALF_UP)
        .stripTrailingZeros().toPlainString() + " GB"
    b >= 1_000_000L -> "%d MB".format((b + 500_000L) / 1_000_000L)
    b >= 1_000L -> "%d KB".format((b + 500L) / 1_000L)
    else -> "$b B"
}
