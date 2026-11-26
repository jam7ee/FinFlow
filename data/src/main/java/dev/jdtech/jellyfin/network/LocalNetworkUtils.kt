package dev.jdtech.jellyfin.network

import java.net.URI

/**
 * Normalizes a raw Wi-Fi SSID returned by Android APIs by trimming surrounding quotes and rejecting
 * sentinel/unknown placeholders.
 */
fun normalizeWifiSsid(rawSsid: String?): String? {
    val trimmed = rawSsid?.trim() ?: return null
    if (trimmed.isEmpty()) return null
    val unquoted =
        if (trimmed.length >= 2 && trimmed.startsWith('"') && trimmed.endsWith('"')) {
            trimmed.substring(1, trimmed.length - 1).trim()
        } else {
            trimmed
        }
    if (unquoted.isEmpty()) return null
    if (unquoted.equals("<unknown ssid>", ignoreCase = true)) return null
    if (unquoted.equals("0x", ignoreCase = true)) return null
    return unquoted
}

/**
 * Returns whether [urlString] targets a private or local-network host using pure string/IP parsing
 * (never triggering blocking DNS resolution). CGNAT (100.64.0.0/10, e.g. Tailscale) returns false.
 */
fun isPrivateOrLocalAddress(urlString: String): Boolean {
    val trimmed = urlString.trim()
    if (trimmed.isEmpty()) return false

    val host =
        try {
                val withScheme = if ("://" in trimmed) trimmed else "http://$trimmed"
                URI(withScheme).host
            } catch (_: Exception) {
                null
            }
            ?.removePrefix("[")
            ?.removeSuffix("]")
            ?.substringBefore('%')
            ?.lowercase()
            ?.trimEnd('.') ?: return false

    if (host.isEmpty()) return false
    if (
        host == "localhost" ||
            host.endsWith(".local") ||
            host.endsWith(".lan") ||
            host.endsWith(".home.arpa")
    ) {
        return true
    }

    parseIpv4(host)?.let { octets ->
        val a = octets[0]
        val b = octets[1]
        return when {
            a == 10 -> true // 10.0.0.0/8
            a == 127 -> true // 127.0.0.0/8 loopback
            a == 169 && b == 254 -> true // 169.254.0.0/16 link-local
            a == 172 && b in 16..31 -> true // 172.16.0.0/12
            a == 192 && b == 168 -> true // 192.168.0.0/16
            else -> false // Explicitly excludes 100.64.0.0/10 CGNAT and public IPv4
        }
    }

    if (':' in host) {
        if (host == "::1") return true
        val firstHextet = host.substringBefore(':').toIntOrNull(16)
        if (firstHextet != null) {
            // fc00::/7 (ULA: fc00..fdff)
            if ((firstHextet and 0xfe00) == 0xfc00) return true
            // fe80::/10 (Link-local: fe80..febf)
            if ((firstHextet and 0xffc0) == 0xfe80) return true
        }
    }

    return false
}

private fun parseIpv4(host: String): IntArray? {
    val parts = host.split('.')
    if (parts.size != 4) return null
    val octets = IntArray(4)
    for (i in 0 until 4) {
        val part = parts[i]
        if (part.isEmpty() || part.length > 3 || !part.all { it.isDigit() }) return null
        val value = part.toIntOrNull() ?: return null
        if (value !in 0..255) return null
        octets[i] = value
    }
    return octets
}
