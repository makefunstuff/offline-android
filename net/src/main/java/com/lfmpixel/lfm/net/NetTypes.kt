package com.lfmpixel.lfm.net

/** One search result. [source] is "web" or "wikipedia". */
data class SearchHit(val title: String, val url: String, val snippet: String, val source: String)

/** One request the network module made: when, to which host, the full address, what it sent beyond the address, bytes received. */
data class NetRequest(val at: Long, val host: String, val url: String, val sent: String, val bytes: Long, val ok: Boolean)
