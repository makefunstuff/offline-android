package com.lfmpixel.lfm.net

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Where Edge mode may go: the owner's own server on their home network or tailnet, never the internet. Settings
 * uses these rules to check the address the owner types, the network module before every request (and on every
 * address a name resolves to, so a name can't point it elsewhere).
 */
object EdgeRules {
    /** The only paths Wren will open on the server: health, models, text, speech in and speech out. */
    val PATHS = setOf("/health", "/v1/models", "/v1/completions", "/v1/chat/completions", "/v1/audio/transcriptions", "/tts")

    /**
     * A private address: 10/8, 172.16/12, 192.168/16 (home networks), 100.64/10 (Tailscale and other CGNAT tailnets),
     * fc00::/7 (IPv6 private, Tailscale's fd7a:115c:a1e0::/48 among them). Not loopback, link-local or anything public.
     */
    fun isPrivate(a: InetAddress): Boolean = when (a) {
        is Inet4Address -> a.address.let { b ->
            val x = b[0].toInt() and 0xff
            val y = b[1].toInt() and 0xff
            x == 10 || (x == 172 && y in 16..31) || (x == 192 && y == 168) || (x == 100 && y in 64..127)
        }
        is Inet6Address -> (a.address[0].toInt() and 0xfe) == 0xfc
        else -> false
    }

    /** A Tailscale address literal: 100.64/10 or Tailscale's IPv6 fd7a:115c:a1e0::/48. Never looked up. */
    fun isTailnetLiteral(host: String): Boolean {
        val b = literal(host)?.address ?: return false
        return (b.size == 4 && (b[0].toInt() and 0xff) == 100 && (b[1].toInt() and 0xff) in 64..127) ||
            (b.size == 16 && (b[0].toInt() and 0xff) == 0xfd && (b[1].toInt() and 0xff) == 0x7a && (b[2].toInt() and 0xff) == 0x11 && (b[3].toInt() and 0xff) == 0x5c)
    }

    /** An IPv4 or IPv6 literal, parsed without any lookup; null for a name. */
    fun literal(host: String): InetAddress? {
        val h = host.removePrefix("[").removeSuffix("]")
        val v4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
        return when {
            v4.matches(h) -> if (h.split('.').all { it.toInt() <= 255 }) InetAddress.getByName(h) else null
            ':' in h && h.all { it.isLetterOrDigit() || it == ':' || it == '.' } -> runCatching { InetAddress.getByName(h) }.getOrNull()
            else -> null
        }
    }

    /**
     * Why [host] can't be the server, or null if it may be: a private address, or a name (a tailnet name like
     * beefy.tail1234.ts.net, or one on the home network), which the network module checks when it resolves it.
     */
    fun refuse(host: String): String? {
        if (host.isBlank()) return "No address"
        literal(host)?.let { return if (isPrivate(it)) null else "Only an address on your home network or tailnet (like 100.x or 192.168.x)" }
        if (!Regex("""[A-Za-z0-9.-]+""").matches(host) || host.startsWith('.') || host.startsWith('-')) return "That doesn't look like an address"
        return null
    }
}
