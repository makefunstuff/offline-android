package com.lfmpixel.lfm

import com.lfmpixel.lfm.assistant.InkKind
import com.lfmpixel.lfm.assistant.InkLine
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone's rules (InkLine.guess) on the label set the d1 evaluation used (labels_eval.tsv): the floor d1 replaces
 * when the server is away. Rules only guess a kind; a reminder counts as a to-do or an event, a question as wrong.
 */
class LabelSetRulesTest {
    @Test fun rulesKindAccuracy() {
        val rows = javaClass.getResourceAsStream("/labels_eval.tsv")!!.bufferedReader().readLines().map { it.split('\t') }.filter { it[0] == "C" }
        var right = 0
        for (r in rows) {
            val g = InkLine.guess(r[1]).kind
            val ok = when (r[2]) {
                "todo" -> g == InkKind.Todo || g == InkKind.Reminder
                "event" -> g == InkKind.Reminder
                "idea" -> g == InkKind.Idea
                "note" -> g == InkKind.Note
                "journal" -> g == InkKind.Journal
                else -> false
            }
            if (ok) right++
        }
        println("rules kind accuracy: $right / ${rows.size}")
        assertTrue(rows.size == 80)
    }
}
