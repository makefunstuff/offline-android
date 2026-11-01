package com.lfmpixel.lfm.data

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Search by meaning next to keyword search (docs/embeddings.md): the closest vectors to a query, and reciprocal rank
 * fusion of those with the keyword hits. Pure, so SemanticSearchTest runs it on EmbeddingGemma 2's own vectors.
 */
object HybridSearch {
    /**
     * A note counts as a meaning match only if its similarity is at least [FLOOR] and within [MARGIN] of the best one.
     * Unit vectors from EmbeddingGemma 2: on our test notebook the right note scored 0.61–0.86, unrelated top-5 notes
     * mostly 0.57–0.68, so a fixed cut alone can't tell them apart; the margin keeps the clear winners.
     */
    const val FLOOR = 0.60f
    const val MARGIN = 0.07f
    /** Reciprocal rank fusion's constant (Cormack et al. 2009): ranks matter, raw scores of the two lists don't. */
    private const val K = 60

    fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in 0 until minOf(a.size, b.size)) s += a[i] * b[i]
        return s
    }

    /** Ids of the vectors closest to [query], best first, at most [limit], filtered as [FLOOR] and [MARGIN] say. */
    fun nearest(query: FloatArray, vectors: Map<Long, FloatArray>, limit: Int): List<Pair<Long, Float>> {
        val scored = vectors.entries.map { it.key to dot(query, it.value) }.sortedByDescending { it.second }
        val best = scored.firstOrNull()?.second ?: return emptyList()
        return scored.takeWhile { it.second >= FLOOR && it.second >= best - MARGIN }.take(limit)
    }

    /** One list from several ranked lists of ids: those high in both come first, then the rest by their best rank. */
    fun fuse(vararg lists: List<Long>): List<Long> {
        val score = LinkedHashMap<Long, Double>()
        for (l in lists) l.forEachIndexed { r, id -> score[id] = (score[id] ?: 0.0) + 1.0 / (K + r + 1) }
        return score.entries.sortedByDescending { it.value }.map { it.key }
    }

    fun pack(v: FloatArray): ByteArray =
        ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { asFloatBuffer().put(v) }.array()

    fun unpack(b: ByteArray): FloatArray =
        FloatArray(b.size / 4).also { ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }
}
