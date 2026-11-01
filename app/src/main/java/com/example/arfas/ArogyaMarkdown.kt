package com.example.arfas

/**
 * Display-only Markdown cleanup (§4 fix). Cloud answers arrive with raw * and **
 * which the answer card does not render; stripping keeps them readable without
 * a renderer. Stored/shared text keeps the original (ArogyaShare strips too).
 * Pure Kotlin, host-testable. Conservative: real content is never removed, only
 * formatting markers.
 */
object ArogyaMarkdown {
    fun strip(t: String): String {
        if (t.isBlank()) return t
        var s = t
        // Fenced code spans keep their content.
        s = s.replace(Regex("```(.*?)```", RegexOption.DOT_MATCHES_ALL)) { it.groupValues[1] }
        s = s.replace(Regex("`([^`\n]+)`")) { it.groupValues[1] }
        // Bold markers. A lone * between alphanumerics (2*3) is kept; underscores are
        // never touched (snake_case and drug names must survive).
        s = s.replace("**", "")
        s = s.replace(Regex("(?m)^\\s{0,3}#{1,6}\\s+"), "")
        // Bullets become • ; numbered items keep their numbers.
        s = s.replace(Regex("(?m)^([ \\t]*)[*•\\-–]\\s+")) { it.groupValues[1] + "• " }
        // A * that only wrapped a phrase ("*Nothing is…*") loses both markers.
        s = s.replace(Regex("(?m)^([ \\t•\\-–]*)\\*+(?=\\S)")) { it.groupValues[1] }
        s = s.replace(Regex("\\*+(?=[\\s).,;:!?\\]\"”']|$)"), "")
        // Links keep their text.
        s = s.replace(Regex("\\[([^\\[\\]]+)]\\((https?://[^)]+)\\)")) { it.groupValues[1] }
        return s.replace(Regex("[ \\t]{2,}"), " ").replace(Regex("\n{3,}"), "\n\n").trim()
    }
}
