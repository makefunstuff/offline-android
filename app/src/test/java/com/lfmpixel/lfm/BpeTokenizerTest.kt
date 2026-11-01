package com.lfmpixel.lfm

import com.lfmpixel.lfm.llm.BpeTokenizer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The tokenizer against ids from Hugging Face `tokenizers` (bpe_golden.tsv, made by src/test/python/bpe_golden.py).
 * The real tokenizer.json files are not in the repo: point LFM_TOKENIZER_JSON / D1_TOKENIZER_JSON (env or system
 * property) at them, or those tests are skipped. The mini tokenizer below always runs.
 */
class BpeTokenizerTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun lfm3MatchesTokenizers() = matchGolden("lfm3", "LFM_TOKENIZER_JSON")

    @Test fun d1MatchesTokenizers() = matchGolden("d1", "D1_TOKENIZER_JSON")

    @Test fun miniTokenizerMergesByRank() {
        val t = BpeTokenizer.load(mini(arrays = true))
        assertEquals(269, t.size)
        assertArrayEquals(intArrayOf(259, 264), t.encode("hello world"))
        assertArrayEquals(intArrayOf(268, 'a'.code), t.encode("aaa")) // equal ranks: leftmost first
        assertArrayEquals(intArrayOf(265, 259, 266, 267), t.encode("<|im_start|>hello<x>abc")) // abc: ignore_merges
        assertEquals(4, t.count("<|im_start|>hello<x>abc"))
        assertArrayEquals(intArrayOf(256, 'y'.code), t.encode("hey"))
        assertArrayEquals(intArrayOf(0xC3, 0xA9), t.encode("é"))
        assertArrayEquals(byteArrayOf(0xC3.toByte()), t.bytesOf(0xC3))
        assertArrayEquals("<|im_start|>".toByteArray(), t.bytesOf(265))
        assertEquals("<|im_start|>hello<x>abc", t.decode(intArrayOf(265, 259, 266, 267)))
        assertEquals("hello<x>abc", t.decode(intArrayOf(265, 259, 266, 267), skipSpecial = true))
        assertEquals("é", t.decode(intArrayOf(0xC3, 0xA9)))
        assertEquals("�", t.decode(intArrayOf(0xC3)))
        assertEquals(259, t.id("hello"))
        assertEquals(266, t.id("<x>"))
        assertEquals(' '.code, t.id("Ġ"))
        assertNull(t.id("zzz"))
        assertArrayEquals(intArrayOf(10, 10, 32, 32, 260), t.encode("\n\n   w")) // "\n\n", "  ", " w"
    }

    @Test fun miniTokenizerReadsStringMerges() {
        val t = BpeTokenizer.load(mini(arrays = false))
        assertArrayEquals(intArrayOf(259, 264), t.encode("hello world"))
    }

    @Test fun unsupportedTokenizerIsRefused() {
        val f = tmp.newFile("wordpiece.json").apply { writeText("""{"model": {"type": "WordPiece", "vocab": {}}}""") }
        val e = runCatching { BpeTokenizer.load(f) }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
    }

    private fun matchGolden(name: String, key: String) {
        val path = System.getProperty(key) ?: System.getenv(key)
        assumeTrue("$key not set", path != null && File(path).isFile)
        val rt = Runtime.getRuntime()
        System.gc()
        val before = rt.totalMemory() - rt.freeMemory()
        val start = System.nanoTime()
        val t = BpeTokenizer.load(File(path!!))
        val ms = (System.nanoTime() - start) / 1_000_000
        System.gc()
        val heap = (rt.totalMemory() - rt.freeMemory() - before) / (1 shl 20)
        println("$name: loaded ${File(path).length() / (1 shl 20)} MB tokenizer.json in $ms ms, ~$heap MB heap, size ${t.size}")

        val bad = ArrayList<String>()
        var checked = 0
        for (line in javaClass.getResourceAsStream("/bpe_golden.tsv")!!.bufferedReader().readLines()) {
            val f = line.split('\t')
            if (f.size < 3 || f[1] != name) continue
            checked++
            when (f[0]) {
                "S" -> if (t.size != f[2].toInt()) bad += "size ${t.size} != ${f[2]}"
                "E" -> {
                    val text = unescape(f[2])
                    val want = ids(f[3])
                    val got = t.encode(text)
                    if (!got.contentEquals(want)) bad += "encode ${f[2]}\n  want ${want.toList()}\n  got  ${got.toList()}"
                    if (t.count(text) != want.size) bad += "count ${f[2]}"
                    val streamed = ByteArrayOutputStream().apply { for (id in got) write(t.bytesOf(id)) }
                    if (String(streamed.toByteArray(), Charsets.UTF_8) != t.decode(got)) bad += "bytesOf ${f[2]}"
                }
                "D" -> {
                    val got = t.decode(ids(f[2]), skipSpecial = f[3] == "1")
                    if (got != unescape(f[4])) bad += "decode ${f[2]} skip=${f[3]}: want ${f[4]} got $got"
                }
                "I" -> {
                    val want = f.getOrNull(3)?.takeIf { it.isNotEmpty() }?.toInt()
                    if (t.id(unescape(f[2])) != want) bad += "id ${f[2]}: want $want got ${t.id(unescape(f[2]))}"
                }
            }
        }
        assertTrue("no golden lines for $name", checked > 100)
        assertTrue("${bad.size} of $checked differ:\n" + bad.take(20).joinToString("\n"), bad.isEmpty())

        // Speed on a long text, for the log.
        val long = javaClass.getResourceAsStream("/bpe_golden.tsv")!!.bufferedReader().readText()
        val t0 = System.nanoTime()
        val n = t.encode(long).size
        println("$name: ${long.length} chars -> $n tokens in ${(System.nanoTime() - t0) / 1_000_000} ms")
    }

    private fun ids(s: String) = if (s.isEmpty()) IntArray(0) else s.split(',').map { it.toInt() }.toIntArray()

    private fun unescape(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\') { sb.append(c); i++; continue }
            when (s[i + 1]) {
                't' -> { sb.append('\t'); i += 2 }
                'n' -> { sb.append('\n'); i += 2 }
                'r' -> { sb.append('\r'); i += 2 }
                'u' -> { sb.append(s.substring(i + 2, i + 6).toInt(16).toChar()); i += 6 }
                'U' -> { sb.appendCodePoint(s.substring(i + 2, i + 10).toInt(16)); i += 10 }
                else -> { sb.append(s[i + 1]); i += 2 }
            }
        }
        return sb.toString()
    }

    /** A tiny byte-level BPE: the 256 byte tokens (id = byte), a few merges, two added tokens and ignore_merges. */
    private fun mini(arrays: Boolean): File {
        val byteChar = CharArray(256)
        var extra = 0
        for (b in 0 until 256) {
            val printable = b in '!'.code..'~'.code || b in 0xA1..0xAC || b in 0xAE..0xFF
            byteChar[b] = (if (printable) b else 256 + extra++).toChar()
        }
        fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        val vocab = (0 until 256).map { q(byteChar[it].toString()) + ": $it" } + listOf(
            "he" to 256, "ll" to 257, "hell" to 258, "hello" to 259, "Ġw" to 260, "or" to 261, "Ġwor" to 262,
            "ld" to 263, "Ġworld" to 264, "abc" to 267, "aa" to 268,
        ).map { (s, id) -> q(s) + ": $id" }
        val merges = listOf("h" to "e", "l" to "l", "he" to "ll", "hell" to "o", "Ġ" to "w", "o" to "r", "Ġw" to "or",
            "l" to "d", "Ġwor" to "ld", "a" to "a")
            .map { (a, b) -> if (arrays) "[${q(a)}, ${q(b)}]" else q("$a $b") }
        val regex = "(?i:'s|'t|'re|'ve|'m|'ll|'d)|[^\\\\r\\\\n\\\\p{L}\\\\p{N}]?\\\\p{L}+|\\\\p{N}{1,3}| ?[^\\\\s\\\\p{L}\\\\p{N}]+[\\\\r\\\\n]*|\\\\s*[\\\\r\\\\n]+|\\\\s+(?!\\\\S)|\\\\s+"
        val json = """
            {"version": "1.0", "truncation": null, "padding": null,
             "added_tokens": [
               {"id": 265, "content": "<|im_start|>", "single_word": false, "lstrip": false, "rstrip": false, "normalized": false, "special": true},
               {"id": 266, "content": "<x>", "single_word": false, "lstrip": false, "rstrip": false, "normalized": true, "special": false}],
             "normalizer": null,
             "pre_tokenizer": {"type": "Sequence", "pretokenizers": [
               {"type": "Split", "pattern": {"Regex": "$regex"}, "behavior": "Isolated", "invert": false},
               {"type": "ByteLevel", "add_prefix_space": false, "trim_offsets": true, "use_regex": false}]},
             "post_processor": {"type": "ByteLevel", "add_prefix_space": true, "trim_offsets": false, "use_regex": true},
             "decoder": {"type": "ByteLevel", "add_prefix_space": true, "trim_offsets": true, "use_regex": true},
             "model": {"type": "BPE", "dropout": null, "unk_token": null, "continuing_subword_prefix": null,
               "end_of_word_suffix": null, "fuse_unk": false, "byte_fallback": false, "ignore_merges": true,
               "vocab": {${vocab.joinToString(", ")}},
               "merges": [${merges.joinToString(", ")}]}}
        """.trimIndent()
        return tmp.newFile("mini-$arrays.json").apply { writeText(json) }
    }
}
