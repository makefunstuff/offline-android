package com.lfmpixel.lfm.llm

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

enum class ModelRole(val label: String) {
    /** LFM3-1.7B for LiteRT-LM: chat, tools, writing (docs/model-pool.md). */
    Text("Chat, tools, writing"),
    /** LFM3-Omni-2B's .litertlm: its decoder and audio encoder, run by OmniVoice. */
    Omni("Voice and talk"),
    /** Omni's speech graphs and the files that drive them: its voice. */
    OmniSpeech("Omni's voice"),
    /** LFM3's tokenizer.json: Omni's prompts, and counting chat tokens. */
    Tokenizer("Tokenizer"),
    /** LFM3-VL-2B for LiteRT-LM: photos, video frames and documents. */
    Vision("Photos and documents"),
}

/**
 * A model file we know: its exact name, size and SHA-256, pinned from Hugging Face (litert-community, 29 Oct to
 * 1 Nov 2026). [remotePath] is where it sits in [repo] at revision [rev] when that differs from [fileName], which
 * must be unique on the phone (two repos both have a tokenizer.json).
 */
data class KnownModel(
    val fileName: String,
    val family: String,
    val quant: String,
    val role: ModelRole,
    val sizeBytes: Long,
    val sha256: String,
    val repo: String,
    val remotePath: String = fileName,
    val rev: String = "main",
) {
    val sizeLabel get() = formatBytes(sizeBytes)
    val displayName get() = family
}

object ModelCatalog {
    private const val TEXT = "litert-community/LFM3-1.7B-LiteRT"
    private const val TEXT_REV = "9bbda264c623f30c905b9c247761ee7463f2b00d"
    private const val OMNI = "litert-community/LFM3-Omni-2B-LiteRT"
    private const val OMNI_REV = "a287b6e6e2535de6a4b48b50e5a5a5b9b82632cc"
    private const val VL = "litert-community/LFM3-VL-2B-LiteRT"
    private const val VL_REV = "580859278a59710b3c93b298982f3f9e884d80e8"

    private fun speech(name: String, size: Long, sha: String, path: String = "speech/$name") =
        KnownModel(name, "Omni voice", "graph", ModelRole.OmniSpeech, size, sha, OMNI, path, OMNI_REV)

    val all = listOf(
        // MLP gate and up projections int4, the rest int8; a 4,096-token cache (the 2,048 one cuts chats short).
        KnownModel("LFM3-1.7B_mixed_int4_ekv4096.litertlm", "LFM3-1.7B", "mixed int4", ModelRole.Text, 1441629120, "327486bf01d0b1eefd727b7aadec8627a2b8db143349262857ac98403109d72b", TEXT, rev = TEXT_REV),
        // The decoder int8, the audio and vision encoders inside; speech out runs on the graphs below.
        KnownModel("LFM3-Omni-2B_int8.litertlm", "LFM3-Omni-2B", "int8", ModelRole.Omni, 3226750192, "959617973a7ddcb3d887fa0d9891d79556a266490d5c432b02a18ffcac3124ed", OMNI, rev = OMNI_REV),
        speech("LFM3-Omni-2B_depth_frame2_f32.tflite", 435046636, "19646b570f453711a60add2523febb4884e3214e8c3b7f5ca898dc29dae3d60a"),
        speech("LFM3-Omni-2B_frame_embed_f32.tflite", 198281972, "71b68529124ef770a495554ea6928f42a66c8c0f2756df2452b15b22bdbc707d"),
        speech("LFM3-Omni-2B_codec_decoder_f12_f32.tflite", 152370912, "7134523fb54c2fd9b31697373b37e1a5bd1669c3a432ab7896a8a7d070f38ed3"),
        speech("codec_window_f32.npy", 5248, "e83a39f565c8edd9d516989d4998631bac2d257734ea11166a407959440591b6"),
        speech("LFM3-Omni-2B_contract.json", 8362, "6ea3753fae712eb6426f5d81b0605cc25a2ea360b9dfb5d3cd4c6260257e3746", "runner/contract.json"),
        speech("LFM3-Omni-2B_bundle_sections.json", 2919, "93832d50f1fa84c5c9acd79496a0b3080062c139271021a0958685af29df7d5b", "runner/bundle_sections.json"),
        // Omni's copy of LFM3's tokenizer (one byte from LFM3-1.7B's: the same tokens), for both.
        KnownModel("LFM3_tokenizer.json", "LFM3 tokenizer", "", ModelRole.Tokenizer, 17908634, "41927bd314d17d7d7fadb44e10cd0919f7b71528d7741596d8cbd15c6945ec44", OMNI, "tokenizer.json", OMNI_REV),
        // int4 decoder, a 2,048-token cache: 2.5 times the float16 file's speed on a phone GPU (its card).
        KnownModel("LFM3-VL-2B_int4.litertlm", "LFM3-VL-2B", "int4", ModelRole.Vision, 2071595259, "8c208746b7758996c41e27eec8507b3c875a9e3bbde4b6da0e4c145d3e450c41", VL, rev = VL_REV),
    )

    /** A model as the Models screen lists it: what it is for, the file offered, and the files it needs beside it. */
    data class Group(val family: String, val purpose: String, val variants: List<KnownModel>, val parts: List<KnownModel>)

    private val tokenizer get() = byName("LFM3_tokenizer.json")!!

    val groups get() = listOf(
        Group("LFM3-1.7B", "Chat, tools and writing", listOf(byName("LFM3-1.7B_mixed_int4_ekv4096.litertlm")!!), listOf(tokenizer)),
        Group("LFM3-Omni-2B", "Voice: dictation, read-aloud and Talk", listOf(byName("LFM3-Omni-2B_int8.litertlm")!!),
            all.filter { it.role == ModelRole.OmniSpeech } + tokenizer),
        Group("LFM3-VL-2B", "Photos, short videos and documents", listOf(byName("LFM3-VL-2B_int4.litertlm")!!), emptyList()),
    )

    /** What first run downloads: chat and voice. */
    val essentials get() = groups.take(2).flatMap { it.variants + it.parts }.distinct()

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
