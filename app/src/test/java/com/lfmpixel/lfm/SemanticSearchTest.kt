package com.lfmpixel.lfm

import com.lfmpixel.lfm.data.Entry
import com.lfmpixel.lfm.data.EntryKind
import com.lfmpixel.lfm.data.FtsQuery
import com.lfmpixel.lfm.data.HybridSearch
import com.lfmpixel.lfm.data.SemanticIndex
import com.lfmpixel.lfm.data.VectorKey
import com.lfmpixel.lfm.llm.EmbedPrompt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Search by meaning on EmbeddingGemma 2's own vectors of a made-up notebook (embed_eval.tsv; the vectors from
 * app/src/test/python/embed_eval.py --golden, so no model is needed here).
 */
class SemanticSearchTest {
    private fun lines(name: String) = javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split("\t") }

    private val eval = lines("embed_eval.tsv")
    private val notes = eval.filter { it[0] == "N" }.associate { it[1].toLong() to (it[3] + " " + it[4]) }
    private val queries = eval.filter { it[0] == "Q" }.map { it[1] to it[2].split(",").map(String::toLong) }
    private val vectors = lines("embed_eval_vectors.tsv")
    private val noteVec = vectors.filter { it[0] == "N" }.associate { it[1].toLong() to vec(it[2]) }
    private val queryVec = vectors.filter { it[0] == "Q" }.associate { it[1] to vec(it[2]) }

    private fun vec(s: String) = s.split(" ").map(String::toFloat).toFloatArray()

    /** FtsQuery's rule (every word a prefix, all required) over the test notes. */
    private fun keyword(q: String): List<Long> {
        val words = FtsQuery.from(q)?.split(" ")?.map { it.removeSuffix("*") } ?: return emptyList()
        return notes.filter { (_, text) ->
            val toks = text.lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
            words.all { w -> toks.any { it.startsWith(w) } }
        }.keys.toList()
    }

    @Test fun vectorsMatchTheModelsOutputSize() {
        assertEquals(40, noteVec.size)
        assertEquals(queries.size, queryVec.size)
        noteVec.values.forEach { assertEquals(EmbedPrompt.DIM, it.size) }
    }

    @Test fun meaningFindsWhatKeywordsMiss() {
        var kwHits = 0
        var hybridHits = 0
        for ((q, relevant) in queries) {
            val kw = keyword(q)
            val near = HybridSearch.nearest(queryVec.getValue(q), noteVec, 8).map { it.first }
            val top = HybridSearch.fuse(kw, near).take(5)
            if (kw.take(5).any { it in relevant }) kwHits++
            if (top.any { it in relevant }) hybridHits++
            assertTrue("\"$q\" should find one of $relevant, got $top", top.any { it in relevant })
        }
        // Keyword search alone finds the right note for almost none of these questions.
        assertTrue("keyword hits $kwHits", kwHits <= 3)
        assertEquals(queries.size, hybridHits)
    }

    @Test fun cutoffKeepsResultListsShort() {
        val sizes = queries.map { (q, _) -> HybridSearch.nearest(queryVec.getValue(q), noteVec, 50).size }
        // Not every note: the margin keeps the clear winners only.
        assertTrue("mean ${sizes.average()}", sizes.average() < 10)
        assertTrue(sizes.all { it >= 1 })
    }

    @Test fun fusionPutsFoundBothWaysFirst() {
        assertEquals(listOf(2L, 1L, 3L), HybridSearch.fuse(listOf(1L, 2L), listOf(2L, 3L)))
        assertEquals(listOf(7L), HybridSearch.fuse(emptyList(), listOf(7L)))
    }

    @Test fun packRoundTrips() {
        val v = floatArrayOf(0.5f, -1.25f, 3e-5f)
        assertArrayEquals(v, HybridSearch.unpack(HybridSearch.pack(v)), 0f)
        assertEquals(12, HybridSearch.pack(v).size)
    }

    @Test fun indexEmbedsOnlyWhatChanged() {
        val a = Entry(id = 1, kind = EntryKind.Note, title = "Dentist", body = "Moved to Kossuth street")
        val b = Entry(id = 2, kind = EntryKind.Idea, title = "Herbs", body = "Basil and mint")
        val m = "embeddinggemma-2-text-270m.litertlm"
        val held = listOf(VectorKey(1, SemanticIndex.hashOf(m, a), m), VectorKey(2, 0L, m), VectorKey(9, 5L, m))
        val plan = SemanticIndex.plan(listOf(a, b).map { it.id to SemanticIndex.hashOf(m, it) }, held, m)
        assertEquals(listOf(2L), plan.embed)
        assertEquals(listOf(9L), plan.delete)
        // An edit or a new model means a new vector.
        assertNotEquals(SemanticIndex.hashOf(m, a), SemanticIndex.hashOf(m, a.copy(body = "Moved to Bem street")))
        assertNotEquals(SemanticIndex.hashOf(m, a), SemanticIndex.hashOf("other.litertlm", a))
        assertEquals(SemanticIndex.hashOf(m, a), SemanticIndex.hashOf(m, a.copy(pinned = true)))
    }

    @Test fun promptsAreTheModelCardsOnes() {
        assertEquals("task: search result | query: where is the dentist", EmbedPrompt.query(" where is the dentist "))
        assertEquals("title: Dentist | text: Kossuth street", EmbedPrompt.document("Dentist", "Kossuth street"))
        assertEquals("title: none | text: x", EmbedPrompt.document("", "x"))
        assertEquals("title: Dentist | text: Kossuth street", SemanticIndex.textOf(Entry(kind = EntryKind.Note, title = "Dentist", body = "Kossuth street")))
    }
}
