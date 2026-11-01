package com.lfmpixel.lfm

import com.lfmpixel.lfm.assistant.D1Prompt
import com.lfmpixel.lfm.assistant.D1Question
import com.lfmpixel.lfm.assistant.D1Tokens
import com.lfmpixel.lfm.assistant.Decide
import com.lfmpixel.lfm.assistant.InkKind
import com.lfmpixel.lfm.assistant.InkLine
import com.lfmpixel.lfm.edge.CaptureLabel
import com.lfmpixel.lfm.llm.BpeTokenizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime

/**
 * d1's request ids against the provider's prompt.encode() (d1_golden.tsv, made by src/test/python/d1_golden.py).
 * The "fake" rows use one id per character, so the budget, truncation and marker rules are checked without the model's
 * tokenizer; the "real" rows need D1_TOKENIZER_JSON (env or system property) pointing at d1's tokenizer.json.
 */
class D1PromptTest {
    private val lines = javaClass.getResourceAsStream("/d1_golden.tsv")!!.bufferedReader(Charsets.UTF_8).readLines().map { it.split('\t') }
    private val temperatures = lines.filter { it[0] == "T" }.associate { it[1] to it[2].toDouble() }
    private val questions: Map<String, D1Question> = lines.filter { it[0] == "Q" }.associate { f ->
        val parts = f.drop(4).map(::unescape)
        val criteria = if (f[2] == "score") parts.mapIndexed { i, v -> "$i" to v } else parts.chunked(2).map { it[0] to it[1] }
        f[1] to D1Question(f[2], unescape(f[3]), criteria)
    }

    @Test fun inklingQuestionsMatchTheGoldenOnes() {
        assertEquals(questions["kind"], Decide.KIND)
        assertEquals(questions["urgency"], Decide.URGENCY)
        assertEquals(questions["energy"], Decide.ENERGY)
        assertEquals(questions["sensitive"], Decide.TOPIC)
        assertEquals(questions["intent"], Decide.INTENT)
        assertEquals(10, temperatures.size)
    }

    @Test fun fakeTokenizerRowsMatchPython() = match("fake", FAKE)

    @Test fun realTokenizerRowsMatchPython() {
        val key = "D1_TOKENIZER_JSON"
        val path = System.getProperty(key) ?: System.getenv(key)
        assumeTrue("$key not set", path != null && File(path).isFile)
        match("real", Decide.tokens(BpeTokenizer.load(File(path!!))))
    }

    @Test fun readoutMatchesPython() {
        var n = 0
        for (f in lines.filter { it[0] == "R" }) {
            val q = questions.getValue(f[1])
            val z = f[3].split(',').map { it.toDouble() }.toDoubleArray()
            val want = f[4].split(',').map { it.toDouble() }
            val got = D1Prompt.readout(z, q, f[2] == "1", temperatures)
            for (i in want.indices) assertEquals("${f[1]} $i", want[i], got[i], 1e-12)
            n++
        }
        assertTrue(n > 50)
    }

    @Test fun escapeKeepsDelimitersOut() {
        assertEquals("a <¦mask¦> b <¦reserved_7¦>", D1Prompt.escape("a <|mask|> b <|reserved_7|>"))
        assertEquals("<|not a token|>", D1Prompt.escape("<|not a token|>"))
    }

    @Test fun whenWordsAreTheLinesOwn() {
        val now = LocalDateTime.of(2026, 11, 1, 10, 0)
        assertEquals("tuesday at 3", Decide.whenWords("dentist tuesday at 3", now))
        assertEquals("tomorrow 9:30", Decide.whenWords("team standup tomorrow 9:30", now))
        assertEquals("tonight at 8", Decide.whenWords("dinner with Sam and Alex tonight at 8", now))
        assertEquals("friday 4pm", Decide.whenWords("therapy session friday 4pm", now))
        assertEquals("Saturday 7pm", Decide.whenWords("Ana's birthday party Saturday 7pm.", now))
        assertEquals("", Decide.whenWords("the parking spot is level 3, row F", now))
        assertEquals("", Decide.whenWords("buy oat milk and bananas", now))
        assertEquals("", Decide.whenWords("bike tyre size 700x28c", now))
    }

    @Test fun d1LabelWithRuleWordsMakesAReminder() {
        val now = LocalDateTime.of(2026, 11, 1, 10, 0)
        val line = "dentist tuesday at 3"
        val g = InkLine.withLabel(line, CaptureLabel("event", Decide.whenWords(line, now), "soon", "low", true, 300), now)
        assertEquals(InkKind.Reminder, g.kind)
        assertEquals("dentist", g.text)
        assertEquals("tuesday at 3", g.reminderAt)
        val idea = InkLine.withLabel("could turn the spare room into a studio", CaptureLabel("idea", "", "later", "high", false, 300), now)
        assertEquals(InkKind.Idea, idea.kind)
    }

    private fun match(tok: String, t: D1Tokens) {
        var checked = 0
        val bad = ArrayList<String>()
        for (f in lines.filter { (it[0] == "E" || it[0] == "X") && it[1] == tok }) {
            val maxLen = f[2].toInt()
            val q = questions.getValue(f[3])
            val state = unescape(f[4])
            checked++
            if (f[0] == "X") {
                if (runCatching { D1Prompt.encode(t, state, q, maxLen) }.isSuccess) bad += "should refuse: ${f[3]} $maxLen ${f[4].take(40)}"
                continue
            }
            val row = D1Prompt.encode(t, state, q, maxLen)
            val ids = f[5].split(',').map { it.toInt() }
            val markers = f[6].split(',').map { it.toInt() }
            if (row.ids.toList() != ids || row.markers.toList() != markers)
                bad += "${f[3]} $maxLen ${f[4].take(40)}\n  want $ids $markers\n  got  ${row.ids.toList()} ${row.markers.toList()}"
        }
        assertTrue("no golden rows for $tok", checked > 300)
        assertTrue("${bad.size} of $checked rows differ:\n" + bad.take(5).joinToString("\n"), bad.isEmpty())
    }

    companion object {
        /** d1_golden.py's Fake: one id per code point, the delimiters' real ids. */
        val FAKE = object : D1Tokens {
            private val special = mapOf("<|startoftext|>" to 1, "<|mask|>" to 16, "<|reserved_7|>" to 17, "<|reserved_8|>" to 18,
                "<|reserved_9|>" to 19, "<|reserved_10|>" to 20, "<|reserved_11|>" to 21)
            override fun encode(text: String) = text.codePoints().map { 100 + it % 60000 }.toArray()
            override fun id(token: String) = special.getValue(token)
        }

        fun unescape(s: String): String {
            val sb = StringBuilder()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    sb.append(when (s[i + 1]) { 't' -> '\t'; 'n' -> '\n'; 'r' -> '\r'; else -> s[i + 1] })
                    i += 2
                } else { sb.append(c); i++ }
            }
            return sb.toString()
        }
    }
}
