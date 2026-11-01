package com.example.arfas

import android.util.Log

/**
 * Retrieved-document sanitizer ported from AndroLLM's PromptInjectionDetector.
 * It is used only for selected-KB evidence and therefore has zero effect when KB is off.
 */
object AndroPromptInjectionDetector {
    private const val TAG = "PromptInjectionDetector"

    private val INJECTION_PATTERNS = listOf(
        Regex("""\b(create|register|add|invent|make)\s+(a\s+)?new\s+tool\b""", RegexOption.IGNORE_CASE),
        Regex("""\bnew tool\s*:""", RegexOption.IGNORE_CASE),
        Regex("""\btool\s+name\s*:\s*[\"']?\w+[\"']?""", RegexOption.IGNORE_CASE),
        Regex("""\bbypass.*validation\b""", RegexOption.IGNORE_CASE),
        Regex("""\bskip.*validation\b""", RegexOption.IGNORE_CASE),
        Regex("""\bignore.*validation\b""", RegexOption.IGNORE_CASE),
        Regex("""\bdisable.*validation\b""", RegexOption.IGNORE_CASE),
        Regex("""\bdo not validate\b""", RegexOption.IGNORE_CASE),
        Regex("""\bwithout validation\b""", RegexOption.IGNORE_CASE),
        Regex("""\bmodify.*tool\s*list\b""", RegexOption.IGNORE_CASE),
        Regex("""\badd.*to.*registry\b""", RegexOption.IGNORE_CASE),
        Regex("""\bavailable tools.*are\b""", RegexOption.IGNORE_CASE),
        Regex("""\bignore\s+previous\s+instructions\b""", RegexOption.IGNORE_CASE),
        Regex("""\bdisregard\s+previous\b""", RegexOption.IGNORE_CASE),
        Regex("""\bpretend\s+you\s+are\b""", RegexOption.IGNORE_CASE),
        Regex("""\byou\s+are\s+now\b""", RegexOption.IGNORE_CASE),
        Regex("""\bsystem\s*:\s*""", RegexOption.IGNORE_CASE),
        Regex("""\bdeveloper\s*:\s*""", RegexOption.IGNORE_CASE),
        Regex("""\[SYSTEM\]""", RegexOption.IGNORE_CASE),
        Regex("""\[INST\]""", RegexOption.IGNORE_CASE),
        Regex("""<\s*tool_call\b""", RegexOption.IGNORE_CASE),
        Regex("""\{\s*\"name\"\s*:\s*\".*?\"\s*,\s*\"arguments\"\s*:""", RegexOption.IGNORE_CASE)
    )

    private val TOOL_INVENTION_KEYWORDS = listOf(
        "create_tool", "new_tool", "register_tool", "add_tool", "invent_tool"
    )

    fun isInjectionAttempt(text: String): Boolean {
        if (text.isBlank()) return false
        val lower = text.lowercase()
        if (TOOL_INVENTION_KEYWORDS.any { it in lower }) return true
        return INJECTION_PATTERNS.any { it.containsMatchIn(text) }
    }

    /**
     * Same retrieved-document neutralization pattern used by AndroLLM, adapted only
     * from Timber to Android Log so it can live in FixSight's source-only package.
     */
    fun sanitizeRetrievedDocument(content: String): String {
        if (content.isBlank()) return content
        var sanitized = content
        sanitized = sanitized.replace(
            Regex(
                """<\s*tool_call\b[^>]*>.*?<\s*/\s*tool_call\s*>""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            ),
            "[removed tool call]"
        )
        sanitized = sanitized.replace(
            Regex("""\{\s*\"name\"\s*:\s*\"[^\"]*\"\s*,\s*\"arguments\"\s*:\s*\{[^}]*\}\s*\}"""),
            "[removed tool syntax]"
        )
        sanitized = sanitized.replace(
            Regex("""\b(ignore previous instructions|disregard.*|bypass validation)[^\n]*""", RegexOption.IGNORE_CASE),
            "[removed injection]"
        )
        if (sanitized.length != content.length) {
            Log.w(TAG, "sanitized retrieved document (${content.length - sanitized.length} chars removed)")
        }
        return sanitized
    }
}
