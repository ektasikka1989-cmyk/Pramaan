package com.example.arfas

/**
 * Minimal SSE data-line parser for OpenAI-compatible chat streams (Meta Muse, Groq).
 * Pure Kotlin with no dependencies, so the host tests can cover it; the Android
 * provider ([GroqChatProvider]) uses it to stream cloud drafts into the answer card.
 */
object SseStreamParser {
    /** Content text carried by one SSE line, or null when the line carries none. */
    fun contentOf(line: String): String? {
        val t = line.trim()
        if (!t.startsWith("data:")) return null
        val payload = t.substringAfter("data:").trim()
        if (payload.isEmpty() || payload == "[DONE]") return null
        return deltaContent(payload)
    }

    /**
     * Extracts choices[0].delta.content from one JSON chunk (choices[0].text as a
     * fallback for text-completion streams). Returns null when the chunk carries no
     * content (role-only deltas, usage packets, error packets without content).
     */
    fun deltaContent(json: String): String? {
        val deltaIdx = json.indexOf("\"delta\"")
        val from = if (deltaIdx >= 0) deltaIdx else 0
        extractJsonString(json, "\"content\"", from)?.let { return it }
        if (deltaIdx < 0) extractJsonString(json, "\"text\"", 0)?.let { return it }
        return null
    }

    /** Value of the first string field [key] at or after [from], unescaped; null when absent. */
    fun extractJsonString(json: String, key: String, from: Int): String? {
        val k = json.indexOf(key, from.coerceAtLeast(0))
        if (k < 0) return null
        val c = json.indexOf(':', k + key.length)
        if (c < 0) return null
        var i = c + 1
        while (i < json.length && json[i].isWhitespace()) i++
        if (i >= json.length || json[i] != '"') return null
        i++
        val sb = StringBuilder()
        while (i < json.length) {
            val ch = json[i]
            if (ch == '"') return sb.toString()
            if (ch == '\\' && i + 1 < json.length) {
                when (val e = json[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'u' -> {
                        val hex = json.substring(i + 2, (i + 6).coerceAtMost(json.length))
                        val code = hex.takeIf { it.length == 4 }?.toIntOrNull(16)
                        if (code == null) return null
                        sb.append(code.toChar())
                        i += 4
                    }
                    else -> sb.append(e)
                }
                i += 2
            } else {
                sb.append(ch)
                i++
            }
        }
        return null
    }
}
