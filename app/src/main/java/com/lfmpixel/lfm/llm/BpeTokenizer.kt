package com.lfmpixel.lfm.llm

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.io.Reader
import java.util.BitSet
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * Byte-level BPE read from a Hugging Face `tokenizer.json`, giving the ids the `tokenizers` library gives:
 * added tokens are split out first, then the pre-tokenizer regex, then byte-level BPE. Covers the LFM tokenizers
 * (a Split regex then ByteLevel, no normalizer); a file it can't follow exactly is refused at load.
 * Safe to share between threads.
 */
class BpeTokenizer private constructor(
    private val chars: CharArray, // every vocab token's text, end to end
    private val tokOff: IntArray, // by id: where its text starts in [chars], -1 if the vocab has no such id
    private val tokLen: IntArray,
    private val table: IntArray, // open addressing: vocab id + 1 by hash of its text, 0 = empty
    private val merges: Merges,
    private val ignoreMerges: Boolean,
    private val splits: Array<Pattern>,
    private val added: Array<String?>, // by id
    private val special: BooleanArray, // by id
    private val addedIds: Map<String, Int>,
    private val rawAdded: AddedSet, // matched first, on the text as written
    private val normAdded: AddedSet, // then in what is left
    val size: Int,
) {
    private val byteIds = IntArray(256) { find(charArrayOf(BYTE_CHAR[it]), 0, 1) }
    private val cache = ConcurrentHashMap<String, IntArray>()

    /** Ids for [text]. Added/special tokens written in the text are matched first as single ids; no BOS is added. */
    fun encode(text: String): IntArray = IntList(false).also { encodeInto(text, it) }.toArray()

    /** Count only; may be faster than encode. */
    fun count(text: String): Int = IntList(true).also { encodeInto(text, it) }.size

    /** Text for [ids]; [skipSpecial] drops special added tokens. Bad UTF-8 becomes U+FFFD, as in `tokenizers`. */
    fun decode(ids: IntArray, skipSpecial: Boolean = false): String {
        val out = ByteArrayOutputStream(ids.size * 4)
        for (id in ids) {
            if (id < 0 || id >= size || (skipSpecial && special[id])) continue
            out.write(bytesOf(id))
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    /** The raw bytes one id stands for (special tokens: their UTF-8 text), for streaming decode where a character spans tokens. */
    fun bytesOf(id: Int): ByteArray {
        if (id < 0 || id >= size) return ByteArray(0)
        val text = added[id] ?: if (tokOff[id] < 0) return ByteArray(0) else String(chars, tokOff[id], tokLen[id])
        // As the ByteLevel decoder does: a token with any char outside the byte map stands for its own UTF-8.
        val b = ByteArray(text.length)
        for (i in text.indices) {
            val c = text[i].code
            if (c >= CHAR_BYTE.size || CHAR_BYTE[c] < 0) return text.toByteArray(Charsets.UTF_8)
            b[i] = CHAR_BYTE[c].toByte()
        }
        return b
    }

    /** Id of a vocab or added token, or null. */
    fun id(token: String): Int? = addedIds[token] ?: find(token.toCharArray(), 0, token.length).takeIf { it >= 0 }

    private fun encodeInto(text: String, out: IntList) {
        rawAdded.split(text, 0, text.length, out) { s, e ->
            normAdded.split(text, s, e, out) { s2, e2 -> pieces(text, s2, e2, 0, out) }
        }
    }

    /** Splits text[from, to) with pattern [level] and on down, as Split(Isolated): matches and gaps alike are pieces. */
    private fun pieces(text: String, from: Int, to: Int, level: Int, out: IntList) {
        if (from >= to) return
        if (level == splits.size) return bpe(text.substring(from, to), out)
        val m = splits[level].matcher(text).region(from, to) // opaque bounds: lookahead stops at the piece's end
        var at = from
        while (m.find()) {
            if (m.start() > at) pieces(text, at, m.start(), level + 1, out)
            pieces(text, m.start(), m.end(), level + 1, out)
            at = m.end()
        }
        pieces(text, at, to, level + 1, out)
    }

    private fun bpe(piece: String, out: IntList) {
        cache[piece]?.let { out.addAll(it); return }
        val ids = merge(piece.toByteArray(Charsets.UTF_8))
        if (piece.length <= 64) {
            if (cache.size > 20_000) cache.clear()
            cache[piece] = ids
        }
        out.addAll(ids)
    }

    /** Byte-level BPE of one pre-token: lowest-rank pair first, leftmost on ties, as `tokenizers` does. */
    private fun merge(bytes: ByteArray): IntArray {
        if (ignoreMerges) {
            val cs = CharArray(bytes.size) { BYTE_CHAR[bytes[it].toInt() and 0xff] }
            val whole = find(cs, 0, cs.size)
            if (whole >= 0) return intArrayOf(whole)
        }
        val ids = IntArray(bytes.size)
        var n = 0
        for (b in bytes) {
            val id = byteIds[b.toInt() and 0xff]
            if (id >= 0) ids[n++] = id // a byte the vocab lacks is dropped, as with no unk token
        }
        if (n <= 1) return ids.copyOf(n)
        val prev = IntArray(n) { it - 1 }
        val next = IntArray(n) { if (it == n - 1) -1 else it + 1 }
        val heap = Heap(n * 2)
        for (i in 0 until n - 1) {
            val s = merges.slot(ids[i], ids[i + 1])
            if (s >= 0) heap.push(merges.rank[s], i)
        }
        while (heap.size > 0) {
            val top = heap.pop()
            val rank = (top ushr 32).toInt()
            val pos = top.toInt()
            if (ids[pos] < 0) continue
            val right = next[pos]
            if (right < 0) continue
            val s = merges.slot(ids[pos], ids[right])
            if (s < 0 || merges.rank[s] != rank) continue // the pair changed since this was queued
            ids[pos] = merges.out[s]
            ids[right] = -1
            next[pos] = next[right]
            if (next[right] >= 0) prev[next[right]] = pos
            val p = prev[pos]
            if (p >= 0) merges.slot(ids[p], ids[pos]).let { if (it >= 0) heap.push(merges.rank[it], p) }
            val q = next[pos]
            if (q >= 0) merges.slot(ids[pos], ids[q]).let { if (it >= 0) heap.push(merges.rank[it], pos) }
        }
        var k = 0
        var i = 0
        while (i >= 0) { ids[k++] = ids[i]; i = next[i] }
        return ids.copyOf(k)
    }

    /** Vocab id of text cs[off, off + len), or -1. */
    private fun find(cs: CharArray, off: Int, len: Int): Int = findIn(table, chars, tokOff, tokLen, cs, off, len)

    /** Added tokens of one kind, matched leftmost-longest as `tokenizers` does. */
    private class AddedSet(tokens: List<Pair<String, Int>>) {
        private val first = BitSet()
        private val byFirst = HashMap<Char, Array<Pair<String, Int>>>()

        init {
            for ((c, list) in tokens.filter { it.first.isNotEmpty() }.groupBy { it.first.first() }) {
                first.set(c.code)
                byFirst[c] = list.sortedByDescending { it.first.length }.toTypedArray()
            }
        }

        /** Writes the ids of tokens found in text[from, to) to [out] and hands each stretch between them to [rest]. */
        inline fun split(text: String, from: Int, to: Int, out: IntList, rest: (Int, Int) -> Unit) {
            var at = from
            var i = from
            while (i < to) {
                if (first[text[i].code]) {
                    val hit = byFirst.getValue(text[i]).firstOrNull { it.first.length <= to - i && text.startsWith(it.first, i) }
                    if (hit != null) {
                        if (i > at) rest(at, i)
                        out.add(hit.second)
                        i += hit.first.length
                        at = i
                        continue
                    }
                }
                i++
            }
            if (to > at) rest(at, to)
        }
    }

    /** Merge rules keyed by the id pair: rank and the id they make. Open addressing over primitive arrays. */
    private class Merges {
        private var keys = LongArray(1 shl 16).apply { fill(-1) }
        var rank = IntArray(1 shl 16)
        var out = IntArray(1 shl 16)
        private var count = 0

        fun slot(a: Int, b: Int): Int {
            val key = (a.toLong() shl 32) or b.toLong()
            val mask = keys.size - 1
            var i = mix(key) and mask
            while (true) {
                val k = keys[i]
                if (k == key) return i
                if (k == -1L) return -1
                i = (i + 1) and mask
            }
        }

        /** A later rule for the same pair replaces the earlier, as in `tokenizers`. */
        fun put(a: Int, b: Int, r: Int, id: Int) {
            if (count * 4 >= keys.size * 3) grow()
            val key = (a.toLong() shl 32) or b.toLong()
            val mask = keys.size - 1
            var i = mix(key) and mask
            while (keys[i] != -1L && keys[i] != key) i = (i + 1) and mask
            if (keys[i] == -1L) count++
            keys[i] = key; rank[i] = r; out[i] = id
        }

        private fun grow() {
            val ok = keys; val or = rank; val oo = out
            keys = LongArray(ok.size * 2).apply { fill(-1) }
            rank = IntArray(ok.size * 2); out = IntArray(ok.size * 2)
            count = 0
            for (i in ok.indices) if (ok[i] != -1L) put((ok[i] ushr 32).toInt(), ok[i].toInt(), or[i], oo[i])
        }

        private fun mix(key: Long): Int {
            var h = key * -0x61c8864680b583ebL
            h = h xor (h ushr 29)
            return h.toInt()
        }
    }

    /** Min-heap of rank (high 32 bits) and position (low 32 bits). */
    private class Heap(capacity: Int) {
        private var a = LongArray(maxOf(capacity, 4))
        var size = 0

        fun push(rank: Int, pos: Int) {
            if (size == a.size) a = a.copyOf(size * 2)
            val v = (rank.toLong() shl 32) or pos.toLong()
            var i = size++
            while (i > 0) {
                val p = (i - 1) shr 1
                if (a[p] <= v) break
                a[i] = a[p]; i = p
            }
            a[i] = v
        }

        fun pop(): Long {
            val top = a[0]
            val v = a[--size]
            var i = 0
            while (true) {
                var c = 2 * i + 1
                if (c >= size) break
                if (c + 1 < size && a[c + 1] < a[c]) c++
                if (a[c] >= v) break
                a[i] = a[c]; i = c
            }
            a[i] = v
            return top
        }
    }

    private class IntList(private val countOnly: Boolean) {
        private var a = IntArray(if (countOnly) 0 else 64)
        var size = 0

        fun add(v: Int) {
            if (!countOnly) {
                if (size == a.size) a = a.copyOf(size * 2)
                a[size] = v
            }
            size++
        }

        fun addAll(vs: IntArray) {
            if (!countOnly) {
                if (size + vs.size > a.size) a = a.copyOf(maxOf(a.size * 2, size + vs.size))
                vs.copyInto(a, size)
            }
            size += vs.size
        }

        fun toArray() = a.copyOf(size)
    }

    /** Stream-reads tokenizer.json, keeping the vocab as one char array and the merges as primitive arrays. */
    private class Loader(private val json: Json) {
        var chars = CharArray(1 shl 20)
        var used = 0
        var tokOff = IntArray(1 shl 16).apply { fill(-1) }
        var tokLen = IntArray(1 shl 16)
        var maxId = -1
        var vocabCount = 0
        var table: IntArray? = null
        val merges = Merges()
        val pending = ArrayList<String>() // merges seen before the vocab, as a, b, a, b...
        var ignoreMerges = false
        val splits = ArrayList<Pattern>()
        var addedList: List<Any?> = emptyList()

        fun read(): BpeTokenizer {
            var model = false
            json.obj { key ->
                when (key) {
                    "added_tokens" -> addedList = json.value() as? List<Any?> ?: emptyList()
                    "normalizer" -> json.value()?.let { checkNormalizer(it as Map<*, *>) }
                    "pre_tokenizer" -> preTokenizer(json.value() as? Map<*, *> ?: unsupported("no pre_tokenizer"))
                    "decoder" -> json.value()?.let { checkDecoder(it as Map<*, *>) }
                    "model" -> { model(); model = true }
                    else -> json.value() // post_processor only adds BOS/offsets; truncation, padding: not used
                }
            }
            if (!model) unsupported("no model")
            if (!byteLevel) unsupported("pre-tokenizer without ByteLevel")
            val t = table ?: buildTable()
            for (i in pending.indices step 2) addMerge(pending[i], pending[i + 1], i / 2, t)

            val raw = ArrayList<Pair<String, Int>>()
            val norm = ArrayList<Pair<String, Int>>()
            val ids = HashMap<String, Int>()
            val specials = ArrayList<Int>()
            var top = maxId
            for (a in addedList) {
                a as Map<*, *>
                val id = (a["id"] as Number).toInt()
                val content = a["content"] as String
                if (a["single_word"] == true || a["lstrip"] == true || a["rstrip"] == true) unsupported("added token options on $content")
                ids[content] = id
                (if (a["normalized"] == true) norm else raw).add(content to id)
                if (a["special"] == true) specials.add(id)
                top = maxOf(top, id)
            }
            val size = top + 1
            val added = arrayOfNulls<String>(size)
            for ((c, id) in raw + norm) added[id] = c
            val special = BooleanArray(size)
            for (id in specials) special[id] = true
            val off = IntArray(size) { if (it < tokOff.size && it <= maxId) tokOff[it] else -1 }
            val len = IntArray(size) { if (it < tokLen.size && it <= maxId) tokLen[it] else 0 }
            return BpeTokenizer(chars.copyOf(used), off, len, t, merges, ignoreMerges, splits.toTypedArray(),
                added, special, ids, AddedSet(raw), AddedSet(norm), size)
        }

        private var byteLevel = false

        private fun preTokenizer(p: Map<*, *>) {
            if (byteLevel) unsupported("pre-tokenizer after ByteLevel")
            when (p["type"]) {
                "Sequence" -> (p["pretokenizers"] as List<*>).forEach { preTokenizer(it as Map<*, *>) }
                "Split" -> {
                    if (p["behavior"] != "Isolated" || p["invert"] == true) unsupported("Split ${p["behavior"]}")
                    val pat = p["pattern"] as Map<*, *>
                    splits += (pat["Regex"] as String?)?.let { Pattern.compile(javaRegex(it)) }
                        ?: Pattern.compile(Pattern.quote(pat["String"] as String))
                }
                "ByteLevel" -> {
                    if (p["add_prefix_space"] == true) unsupported("ByteLevel add_prefix_space")
                    if (p["use_regex"] != false) splits += Pattern.compile(javaRegex(GPT2_REGEX))
                    byteLevel = true
                }
                else -> unsupported("pre-tokenizer ${p["type"]}")
            }
        }

        private fun checkNormalizer(n: Map<*, *>) {
            if (n["type"] != "Sequence" || (n["normalizers"] as List<*>).isNotEmpty()) unsupported("normalizer ${n["type"]}")
        }

        private fun checkDecoder(d: Map<*, *>) {
            when (d["type"]) {
                "ByteLevel" -> {}
                "Sequence" -> (d["decoders"] as List<*>).forEach { checkDecoder(it as Map<*, *>) }
                else -> unsupported("decoder ${d["type"]}")
            }
        }

        private fun model() {
            json.obj { key ->
                when (key) {
                    "type" -> json.string().let { if (it != "BPE") unsupported("model $it") }
                    "ignore_merges" -> ignoreMerges = json.value() == true
                    "vocab" -> vocab()
                    "merges" -> merges()
                    "dropout", "continuing_subword_prefix", "end_of_word_suffix", "unk_token" ->
                        json.value()?.let { if (it != "") unsupported("BPE $key") }
                    else -> json.value() // fuse_unk, byte_fallback: only matter for bytes the vocab lacks
                }
            }
        }

        private fun vocab() {
            json.objRaw {
                if (used + json.strLen > chars.size) chars = chars.copyOf(maxOf(chars.size * 2, used + json.strLen))
                json.str.copyInto(chars, used, 0, json.strLen)
                val start = used
                val len = json.strLen
                used += len
                val id = json.int()
                if (id >= tokOff.size) {
                    val n = maxOf(tokOff.size * 2, id + 1)
                    tokOff = tokOff.copyOf(n).also { it.fill(-1, tokOff.size) }
                    tokLen = tokLen.copyOf(n)
                }
                tokOff[id] = start; tokLen[id] = len
                maxId = maxOf(maxId, id)
                vocabCount++
            }
            table = buildTable()
        }

        private fun buildTable(): IntArray {
            var cap = 16
            while (cap < vocabCount * 2) cap *= 2
            val t = IntArray(cap)
            for (id in 0..maxId) {
                if (tokOff[id] < 0) continue
                var i = hash(chars, tokOff[id], tokLen[id]) and (cap - 1)
                while (t[i] != 0) i = (i + 1) and (cap - 1)
                t[i] = id + 1
            }
            return t
        }

        private var buf = CharArray(256)

        private fun merges() {
            var rank = 0
            json.arr {
                val la: Int
                val lb: Int
                if (json.peek() == '[') {
                    json.expect('[')
                    json.readStr(); la = json.strLen; put(0, la)
                    json.expect(',')
                    json.readStr(); lb = json.strLen; put(la, lb)
                    json.expect(']')
                } else {
                    json.readStr()
                    val sp = (0 until json.strLen).firstOrNull { json.str[it] == ' ' } ?: unsupported("merge without a space")
                    la = sp; lb = json.strLen - sp - 1
                    put(0, la); json.str.copyInto(buf, la, sp + 1, json.strLen)
                }
                val t = table
                if (t == null) {
                    pending += String(buf, 0, la); pending += String(buf, la, lb)
                } else {
                    val a = findIn(t, chars, tokOff, tokLen, buf, 0, la)
                    val b = findIn(t, chars, tokOff, tokLen, buf, la, lb)
                    val ab = findIn(t, chars, tokOff, tokLen, buf, 0, la + lb)
                    if (a >= 0 && b >= 0 && ab >= 0) merges.put(a, b, rank, ab)
                }
                rank++
            }
        }

        private fun put(at: Int, len: Int) {
            if (at + json.strLen + 1 > buf.size) buf = buf.copyOf((at + json.strLen + 1) * 2)
            json.str.copyInto(buf, at, 0, len)
        }

        private fun addMerge(a: String, b: String, rank: Int, t: IntArray) {
            val ab = (a + b).toCharArray()
            val ia = findIn(t, chars, tokOff, tokLen, ab, 0, a.length)
            val ib = findIn(t, chars, tokOff, tokLen, ab, a.length, b.length)
            val iab = findIn(t, chars, tokOff, tokLen, ab, 0, ab.size)
            if (ia >= 0 && ib >= 0 && iab >= 0) merges.put(ia, ib, rank, iab)
        }
    }

    /** Just enough JSON: a buffered pull reader with a reusable string buffer, so the vocab and merges make no garbage. */
    private class Json(private val r: Reader) : Closeable {
        private val buf = CharArray(1 shl 16)
        private var pos = 0
        private var lim = 0
        var str = CharArray(256)
        var strLen = 0

        private fun more(): Boolean {
            val n = r.read(buf, 0, buf.size)
            pos = 0; lim = maxOf(n, 0)
            return n > 0
        }

        fun peek(): Char {
            while (true) {
                if (pos >= lim && !more()) throw EOFException("tokenizer.json ends early")
                val c = buf[pos]
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') pos++ else return c
            }
        }

        private fun next(): Char {
            if (pos >= lim && !more()) throw EOFException("tokenizer.json ends early")
            return buf[pos++]
        }

        fun expect(c: Char) {
            val got = peek()
            if (got != c) throw IllegalArgumentException("tokenizer.json: expected '$c', found '$got'")
            pos++
        }

        /** Reads a string into [str] and [strLen]. */
        fun readStr() {
            expect('"')
            var n = 0
            var s = str
            while (true) {
                var c = next()
                if (c == '"') break
                if (c == '\\') {
                    c = when (val e = next()) {
                        'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'; 'b' -> '\b'; 'f' -> '\u000C'
                        'u' -> { var v = 0; repeat(4) { v = v * 16 + Character.digit(next(), 16) }; v.toChar() }
                        else -> e
                    }
                }
                if (n == s.size) s = s.copyOf(n * 2)
                s[n++] = c
            }
            str = s; strLen = n
        }

        fun string(): String { readStr(); return String(str, 0, strLen) }

        private fun scalar(): String {
            val sb = StringBuilder()
            peek()
            while (true) {
                if (pos >= lim && !more()) break
                val c = buf[pos]
                if (c == ',' || c == '}' || c == ']' || c == ' ' || c == '\n' || c == '\r' || c == '\t') break
                sb.append(c); pos++
            }
            return sb.toString()
        }

        fun int(): Int = scalar().toInt()

        /** Calls [block] for each key, left in [str]; [block] must read the value. */
        inline fun objRaw(block: () -> Unit) {
            expect('{')
            if (peek() == '}') { expect('}'); return }
            while (true) {
                readStr(); expect(':'); block()
                if (peek() == ',') expect(',') else { expect('}'); return }
            }
        }

        inline fun obj(block: (String) -> Unit) = objRaw { block(String(str, 0, strLen)) }

        inline fun arr(block: () -> Unit) {
            expect('[')
            if (peek() == ']') { expect(']'); return }
            while (true) {
                block()
                if (peek() == ',') expect(',') else { expect(']'); return }
            }
        }

        /** Any value as maps, lists, strings, Long/Double, Boolean or null. */
        fun value(): Any? = when (peek()) {
            '{' -> LinkedHashMap<String, Any?>().also { m -> obj { m[it] = value() } }
            '[' -> ArrayList<Any?>().also { l -> arr { l.add(value()) } }
            '"' -> string()
            else -> when (val s = scalar()) {
                "true" -> true
                "false" -> false
                "null" -> null
                else -> s.toLongOrNull() ?: s.toDouble()
            }
        }

        override fun close() = r.close()
    }

    companion object {
        /** Loads tokenizer.json. Stream-parsed; the vocab and merges are kept as primitive arrays. */
        fun load(file: File): BpeTokenizer =
            Json(InputStreamReader(FileInputStream(file), Charsets.UTF_8)).use { Loader(it).read() }

        /** The GPT-2 byte map: each byte as a printable char, so byte strings can be vocab keys. */
        private val BYTE_CHAR = CharArray(256)
        private val CHAR_BYTE = IntArray(324).apply { fill(-1) }

        init {
            var extra = 0
            for (b in 0 until 256) {
                val printable = b in '!'.code..'~'.code || b in 0xA1..0xAC || b in 0xAE..0xFF
                val c = if (printable) b else 256 + extra++
                BYTE_CHAR[b] = c.toChar()
                CHAR_BYTE[c] = b
            }
        }

        private const val GPT2_REGEX = "'s|'t|'re|'ve|'m|'ll|'d| ?\\p{L}+| ?\\p{N}+| ?[^\\s\\p{L}\\p{N}]+|\\s+(?!\\S)|\\s+"

        /** Unicode White_Space, which is what `\s` means in the Oniguruma regexes `tokenizers` runs (Java's `\s` is ASCII only). */
        private const val WS = "\\t\\n\\x0B\\f\\r\\x20\\x85\\xA0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000"

        /** The tokenizer.json regex for java.util.regex: only `\s` and `\S` need rewriting. */
        private fun javaRegex(re: String): String {
            val sb = StringBuilder()
            var inClass = false
            var i = 0
            while (i < re.length) {
                val c = re[i]
                if (c == '\\' && i + 1 < re.length) {
                    when (val e = re[i + 1]) {
                        's' -> sb.append(if (inClass) WS else "[$WS]")
                        'S' -> if (inClass) unsupported("\\S in a class") else sb.append("[^$WS]")
                        else -> sb.append(c).append(e)
                    }
                    i += 2
                    continue
                }
                if (c == '[' && !inClass) inClass = true else if (c == ']' && inClass) inClass = false
                sb.append(c)
                i++
            }
            return sb.toString()
        }

        private fun hash(cs: CharArray, off: Int, len: Int): Int {
            var h = 0
            for (i in off until off + len) h = 31 * h + cs[i].code
            return (h * -0x61c88647).let { it xor (it ushr 15) }
        }

        private fun findIn(table: IntArray, chars: CharArray, tokOff: IntArray, tokLen: IntArray, cs: CharArray, off: Int, len: Int): Int {
            val mask = table.size - 1
            var i = hash(cs, off, len) and mask
            while (true) {
                val v = table[i]
                if (v == 0) return -1
                val id = v - 1
                if (tokLen[id] == len && chars.regionEquals(tokOff[id], cs, off, len)) return id
                i = (i + 1) and mask
            }
        }

        private fun CharArray.regionEquals(at: Int, other: CharArray, off: Int, len: Int): Boolean {
            for (k in 0 until len) if (this[at + k] != other[off + k]) return false
            return true
        }

        private fun unsupported(what: String): Nothing = throw IllegalArgumentException("tokenizer.json: unsupported $what")
    }
}
