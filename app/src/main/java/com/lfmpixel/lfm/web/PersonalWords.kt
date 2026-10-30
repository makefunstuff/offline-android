package com.lfmpixel.lfm.web

/** A personal word found in a search query, and where it is known from. */
data class PersonalHit(val word: String, val kind: Kind) {
    enum class Kind(val what: String, val noun: String) {
        Contact("a name from your contacts", "name"),
        Journal("a name from your journal", "name"),
        Note("a name from your notes", "name"),
        Phone("a phone number", "number"),
        Email("an e-mail address", "address"),
        Address("an address", "address"),
    }
}

/** What the phone knows that is personal: contact names, and names and places written in the journal and notes. */
data class KnownWords(val contacts: List<String> = emptyList(), val journal: List<String> = emptyList(), val notes: List<String> = emptyList())

/**
 * Checks a search query, on the phone, for personal data before it goes out (spec section 4): names in contacts,
 * phone numbers, e-mail addresses, street addresses and postcodes, and names of people and places from the
 * journal and notes. No model: plain matching, so it is quick and predictable.
 */
object PersonalWords {
    private val email = Regex("""[\w.+-]+@[\w-]+(\.[\w-]+)+""")
    private val phone = Regex("""\+?\(?\d[\d\s().-]{5,}\d""")
    private val street = Regex("""\b\d{1,5}[a-z]?\s+(?:[A-Za-z]+\s+){0,3}(?:street|st|road|rd|avenue|ave|lane|ln|drive|dr|close|way|place|pl|court|ct|crescent|terrace|gardens|square|sq|boulevard|blvd|grove|mews|hill|row|walk)\b""", RegexOption.IGNORE_CASE)
    private val postcode = Regex("""\b[A-Z]{1,2}\d[A-Z\d]?\s?\d[A-Z]{2}\b""")
    private val word = Regex("""\p{L}[\p{L}'’-]*\p{L}""")

    /** Capitalised words that are not personal: days, months, and words often capitalised. */
    private val common = (
        "monday tuesday wednesday thursday friday saturday sunday january february march april may june july august september " +
            "october november december today tomorrow yesterday english french german spanish google android pixel iphone apple " +
            "the and but for with from this that what when where which who how why not all any you your our their his her its " +
            "mum mom dad mr mrs ms dr christmas easter covid internet wifi bluetooth okay hello thanks please also just lfm " +
            "will mark bill rose hope grace joy art frank sunny summer amber dawn faith penny"
        ).split(' ').toSet()

    fun find(query: String, known: KnownWords): List<PersonalHit> {
        val hits = mutableListOf<PersonalHit>()
        email.findAll(query).forEach { hits += PersonalHit(it.value, PersonalHit.Kind.Email) }
        phone.findAll(query).filter { m -> m.value.count { it.isDigit() } >= 7 && hits.none { it.word.contains(m.value) } }
            .forEach { hits += PersonalHit(it.value.trim(), PersonalHit.Kind.Phone) }
        street.findAll(query).forEach { hits += PersonalHit(it.value, PersonalHit.Kind.Address) }
        postcode.findAll(query).forEach { hits += PersonalHit(it.value, PersonalHit.Kind.Address) }

        val q = query.lowercase()
        fun has(w: String) = Regex("""(?<![\p{L}\p{N}])${Regex.escape(w.lowercase())}(?![\p{L}\p{N}])""").find(q)
        fun add(span: IntRange, kind: PersonalHit.Kind) {
            if (hits.any { h -> query.indexOf(h.word).let { it >= 0 && span.first < it + h.word.length && it < span.last + 1 } }) return
            hits += PersonalHit(query.substring(span), kind)
        }
        // A whole contact name first ("Ana Pereira"), then its parts on their own ("Pereira").
        for (name in known.contacts.sortedByDescending { it.length }) {
            // A one-word name that is also a common word ("Will") would warn on everything.
            name.trim().takeIf { ' ' in it || it.lowercase() !in common }?.let { full -> has(full)?.let { add(it.range, PersonalHit.Kind.Contact) } }
            word.findAll(name).map { it.value }.filter { it.length >= 3 && it.lowercase() !in common }.forEach { part ->
                has(part)?.let { add(it.range, PersonalHit.Kind.Contact) }
            }
        }
        for ((list, kind) in listOf(known.journal to PersonalHit.Kind.Journal, known.notes to PersonalHit.Kind.Note)) {
            for (name in names(list)) has(name)?.let { add(it.range, kind) }
        }
        return hits
    }

    /** Proper names in free text: capitalised words that don't start a sentence and aren't common words. */
    fun names(texts: List<String>): Set<String> {
        val out = linkedSetOf<String>()
        for (t in texts) {
            for (sentence in t.split(Regex("""(?<=[.!?:;])\s+|\n+|\s[–—-]\s"""))) {
                word.findAll(sentence).drop(1).forEach { m ->
                    val w = m.value
                    if (w.length >= 3 && w[0].isUpperCase() && w.drop(1).any { it.isLowerCase() } && w.lowercase() !in common) out += w
                }
            }
        }
        return out
    }

    /** The query with the found words taken out. */
    fun remove(query: String, hits: List<PersonalHit>): String {
        var q = query
        for (h in hits.sortedByDescending { it.word.length }) q = q.replace(h.word, " ", ignoreCase = true)
        return q.replace(Regex("""\s+'s\b"""), " ").replace(Regex("\\s+"), " ").trim()
    }
}
