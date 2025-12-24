package com.example.tiktokcleaner

fun trimTikTokUrl(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return trimmed

    val uri = runCatching { java.net.URI(trimmed) }.getOrNull() ?: return trimmed
    val host = uri.host ?: return trimmed
    if (!host.contains("tiktok.com")) return trimmed

    var s = trimmed
    var index = s.indexOf("&_t")
    if (index == -1) {
        index = s.indexOf("?_t")
    }
    if (index == -1) return trimmed

    s = s.substring(0, index)
    while (s.endsWith("?") || s.endsWith("&")) {
        s = s.dropLast(1)
    }
    return s
}