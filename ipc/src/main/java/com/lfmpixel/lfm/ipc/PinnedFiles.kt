package com.lfmpixel.lfm.ipc

/**
 * The only files LFM Net will download: name, Hugging Face repo and exact size. LFM checks each file's SHA-256
 * on arrival (ModelCatalog); a unit test keeps the two lists the same.
 */
object PinnedFiles {
    data class File(val name: String, val repo: String, val size: Long) {
        val url get() = "https://huggingface.co/$repo/resolve/main/$name"
    }

    private const val TEXT = "LiquidAI/LFM3-1.7B-GGUF"
    private const val OMNI = "LiquidAI/LFM3-Omni-2B-GGUF"
    private const val VL = "LiquidAI/LFM3-VL-2B-GGUF"
    private const val LITERT = "litert-community/LFM3-1.7B-LiteRT"

    val all = listOf(
        File("LFM3-1.7B-QAD-Q4_0.gguf", TEXT, 1049615680),
        File("LFM3-1.7B-Q4_K_M.gguf", TEXT, 1099627808),
        File("LFM3-1.7B-Q5_K_M.gguf", TEXT, 1258663200),
        File("LFM3-1.7B-Q6_K.gguf", TEXT, 1427638304),
        File("LFM3-1.7B-Q8_0.gguf", TEXT, 1846238496),
        File("LFM3-Omni-2B-Q4_0.gguf", OMNI, 1049617920),
        File("LFM3-Omni-2B-Q4_K_M.gguf", OMNI, 1099630080),
        File("LFM3-Omni-2B-Q8_0.gguf", OMNI, 1846240768),
        File("mmproj-LFM3-Omni-2B-Q4_0.gguf", OMNI, 270576544),
        File("mmproj-LFM3-Omni-2B-Q4_K_M.gguf", OMNI, 291586720),
        File("mmproj-LFM3-Omni-2B-Q8_0.gguf", OMNI, 506842528),
        File("LFM3-VL-2B-QAD-Q4_0.gguf", VL, 1049615648),
        File("LFM3-VL-2B-Q4_K_M.gguf", VL, 1099627808),
        File("LFM3-VL-2B-Q8_0.gguf", VL, 1846238496),
        File("mmproj-LFM3-VL-2B-Q8_0.gguf", VL, 203031872),
        File("LFM3-1.7B_mixed_int4_ekv4096.litertlm", LITERT, 1441629120),
    )

    /** Exact match only: no paths, no other repos. */
    fun byName(name: String): File? = all.firstOrNull { it.name == name }
}
