package com.lfmpixel.lfm.ipc

/**
 * The only files LFM Net will download: name, Hugging Face repo, exact size, and where it sits in the repo at a pinned
 * revision. LFM checks each file's SHA-256 on arrival (ModelCatalog); a unit test keeps the two lists the same.
 */
object PinnedFiles {
    data class File(val name: String, val repo: String, val size: Long, val path: String = name, val rev: String = "main") {
        val url get() = "https://huggingface.co/$repo/resolve/$rev/$path"
    }

    private const val TEXT = "litert-community/LFM3-1.7B-LiteRT"
    private const val TEXT_REV = "9bbda264c623f30c905b9c247761ee7463f2b00d"
    private const val OMNI = "litert-community/LFM3-Omni-2B-LiteRT"
    private const val OMNI_REV = "a287b6e6e2535de6a4b48b50e5a5a5b9b82632cc"
    private const val VL = "litert-community/LFM3-VL-2B-LiteRT"
    private const val VL_REV = "580859278a59710b3c93b298982f3f9e884d80e8"

    val all = listOf(
        File("LFM3-1.7B_mixed_int4_ekv4096.litertlm", TEXT, 1441629120, rev = TEXT_REV),
        File("LFM3-Omni-2B_int8.litertlm", OMNI, 3226750192, rev = OMNI_REV),
        File("LFM3-Omni-2B_depth_frame2_f32.tflite", OMNI, 435046636, "speech/LFM3-Omni-2B_depth_frame2_f32.tflite", OMNI_REV),
        File("LFM3-Omni-2B_frame_embed_f32.tflite", OMNI, 198281972, "speech/LFM3-Omni-2B_frame_embed_f32.tflite", OMNI_REV),
        File("LFM3-Omni-2B_codec_decoder_f12_f32.tflite", OMNI, 152370912, "speech/LFM3-Omni-2B_codec_decoder_f12_f32.tflite", OMNI_REV),
        File("codec_window_f32.npy", OMNI, 5248, "speech/codec_window_f32.npy", OMNI_REV),
        File("LFM3-Omni-2B_contract.json", OMNI, 8362, "runner/contract.json", OMNI_REV),
        File("LFM3-Omni-2B_bundle_sections.json", OMNI, 2919, "runner/bundle_sections.json", OMNI_REV),
        File("LFM3_tokenizer.json", OMNI, 17908634, "tokenizer.json", OMNI_REV),
        File("LFM3-VL-2B_int4.litertlm", VL, 2071595259, rev = VL_REV),
    )

    /** Exact match only: no paths, no other repos. */
    fun byName(name: String): File? = all.firstOrNull { it.name == name }
}
