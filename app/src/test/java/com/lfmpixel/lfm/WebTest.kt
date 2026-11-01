package com.lfmpixel.lfm

import com.lfmpixel.lfm.data.SearchSource
import com.lfmpixel.lfm.net.PinnedFiles
import com.lfmpixel.lfm.llm.ModelCatalog
import com.lfmpixel.lfm.llm.ModelRole
import com.lfmpixel.lfm.web.ArticleText
import com.lfmpixel.lfm.web.KnownWords
import com.lfmpixel.lfm.web.PersonalHit.Kind
import com.lfmpixel.lfm.web.PersonalWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The on-phone parts of web search and downloads: personal-word check, page reading, pinned files. */
class WebTest {
    private val known = KnownWords(
        contacts = listOf("Ana Pereira", "Sam Okafor", "Will"),
        journal = listOf("Lunch with Mia, laughed a lot. Walked along Roundhay Park after."),
        notes = listOf("Boiler service. Model Vaillant ecoTEC 832. Last service March."),
    )

    @Test fun contactNameIsFound() {
        val hits = PersonalWords.find("Ana Pereira café reviews", known)
        assertEquals(listOf("Ana Pereira" to Kind.Contact), hits.map { it.word to it.kind })
        assertEquals("café reviews", PersonalWords.remove("Ana Pereira café reviews", hits))
    }

    @Test fun surnameAloneAndLowercaseAreFound() {
        assertEquals("pereira", PersonalWords.find("pereira bakery leeds", known).single().word)
    }

    @Test fun commonWordsInContactNamesAreNotFlagged() {
        // "Will" is a contact, but too common to warn about on its own.
        assertTrue(PersonalWords.find("will it rain tomorrow", known).isEmpty())
    }

    @Test fun journalAndNoteNamesAreFound() {
        assertEquals(Kind.Journal, PersonalWords.find("Roundhay opening hours", known).single().kind)
        assertEquals(Kind.Note, PersonalWords.find("vaillant boiler pressure drop", known).single().kind)
        // March starts no sentence here, but it's a month, not a name.
        assertTrue(PersonalWords.find("march weather", known).isEmpty())
    }

    @Test fun numbersMailsAndAddressesAreFound() {
        assertEquals(Kind.Phone, PersonalWords.find("who called +44 7700 900123", known).single().kind)
        assertEquals(Kind.Email, PersonalWords.find("ana.p@example.com", known).single().kind)
        assertEquals(Kind.Address, PersonalWords.find("12 Elm Grove Leeds damp", known).first().kind)
        assertEquals(Kind.Address, PersonalWords.find("bin day LS8 2AB", known).single().kind)
        assertTrue("a year is not a phone number", PersonalWords.find("plumber call-out fee Leeds 2026", known).isEmpty())
    }

    @Test fun articleIsTakenFromTheMainTextNotTheMenus() {
        val html = """<html><head><title>Plumbing prices in Leeds, 2026 guide</title></head><body>
            <nav><a>Home</a><a>Find a trade</a><p>Menu text that is long enough to count as a paragraph of words.</p></nav>
            <article><h1>Plumbing prices</h1>
            <p>Most Leeds plumbers charge &pound;60&ndash;&pound;95 for a call-out, which covers the first hour of work.</p>
            <p>Evenings and weekends often cost one and a half times the normal rate, so ask first.</p>
            <table><tr><td>Call-out</td><td>£60–£95</td></tr></table>
            <p>Unrelated text about bathroom tiles and grout colours that goes on for a while here.</p>
            </article><footer><p>Copyright notice and other footer text that is long enough to be a paragraph.</p></footer></body></html>"""
        val a = ArticleText.extract(html)
        assertEquals("Plumbing prices in Leeds, 2026 guide", a.title)
        assertTrue(a.paragraphs.first().startsWith("Most Leeds plumbers charge £60–£95"))
        assertFalse(a.paragraphs.any { "Menu" in it || "Copyright" in it })
        assertTrue(a.paragraphs.any { it == "Call-out | £60–£95" })
        val ex = ArticleText.excerpt(a, "plumber call-out fee Leeds", 200)
        assertTrue(ex.startsWith("Most Leeds plumbers"))
        assertTrue(ex.length <= 200)
    }

    @Test fun sourcesSurviveTheDatabase() {
        val list = listOf(SearchSource(1, "A\ttab", "https://www.a.example/x?y=1", "line\nbreak", true), SearchSource(2, "B", "https://b.example", "", false))
        val back = SearchSource.decode(SearchSource.encode(list))
        assertEquals(2, back.size)
        assertEquals("a.example", back[0].host)
        assertEquals("A tab", back[0].title)
        assertTrue(back[0].read)
        assertFalse(back[1].read)
    }

    @Test fun lfmNetDownloadsExactlyTheCatalog() {
        // The network module's pinned list and Inkling's hash list must name the same files, sizes, repos, paths and revisions.
        assertEquals(ModelCatalog.all.map { listOf(it.fileName, it.repo, it.sizeBytes, it.remotePath, it.rev) }.toSet(),
            PinnedFiles.all.map { listOf(it.name, it.repo, it.size, it.path, it.rev) }.toSet())
        // Every file is LiteRT's (no GGUF on the phone), pinned to a revision, and offered by one group or as a part.
        assertTrue(ModelCatalog.all.none { it.fileName.endsWith(".gguf") } && ModelCatalog.all.all { it.rev.length == 40 })
        assertEquals(ModelCatalog.all.toSet(), ModelCatalog.groups.flatMap { it.variants + it.parts }.toSet())
        assertTrue(ModelCatalog.groups.all { g -> g.variants.all { it.family == g.family } })
    }
}
