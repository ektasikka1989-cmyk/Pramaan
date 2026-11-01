package com.example.arfas

import org.json.JSONObject

/**
 * Package-adapted port of AndroLLM engine/core/NativeToolCallScanner.kt.
 *
 * The engine never renders native tool-call blocks as assistant text. Complete
 * calls can be extracted by a tool layer; incomplete/malformed blocks are simply
 * hidden from user-visible output.
 */
data class NativeToolCall(
    val name: String,
    val argumentsJson: String
)

object NativeToolCallScanner {
    private val OPEN_MARKERS = listOf("<|tool_call_start|>", "<|tool_call|>", "<|tool_call>")
    private val CLOSE_MARKERS = listOf("<|tool_call_end|>", "<tool_call|>", "<|tool_call|>", "<|tool_call>")

    fun scan(raw: String): List<NativeToolCall> {
        if (raw.isBlank()) return emptyList()
        val calls = mutableListOf<NativeToolCall>()
        var index = 0
        while (index < raw.length) {
            val open = findMarker(raw, index, OPEN_MARKERS) ?: break
            val close = findMarker(raw, open.end, CLOSE_MARKERS) ?: break
            val body = raw.substring(open.end, close.start).trim()
            parseBlock(body)?.let(calls::add)
            index = close.end
        }
        return calls
    }

    /** Exact AndroLLM display rule: drop tool block plus payload, including an unfinished tail. */
    fun strip(raw: String): String {
        if (raw.isBlank()) return raw
        val cleaned = StringBuilder()
        var index = 0
        var inBlock = false
        while (index < raw.length) {
            if (!inBlock) {
                val open = findMarker(raw, index, OPEN_MARKERS)
                if (open != null) {
                    cleaned.append(raw, index, open.start)
                    inBlock = true
                    index = open.end
                } else {
                    cleaned.append(raw, index, raw.length)
                    break
                }
            } else {
                val close = findMarker(raw, index, CLOSE_MARKERS)
                if (close != null) {
                    inBlock = false
                    index = close.end
                } else {
                    // Unterminated tool call: never expose its partial payload.
                    break
                }
            }
        }
        return cleaned.toString().trim()
    }

    fun containsMarker(raw: String): Boolean =
        OPEN_MARKERS.any { raw.contains(it) } || CLOSE_MARKERS.any { raw.contains(it) }

    private fun parseBlock(body: String): NativeToolCall? {
        if (body.isBlank()) return null
        val trimmed = body.trim()
        if (trimmed.startsWith("{")) {
            val obj = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null
            val name = when {
                obj.has("function_name") -> obj.optString("function_name")
                else -> obj.optString("name")
            }.trim()
            if (name.isBlank()) return null
            val args = obj.opt("arguments")
            val argsJson = when (args) {
                is JSONObject -> args.toString()
                is String -> args.takeIf { runCatching { JSONObject(it) }.isSuccess } ?: "{}"
                else -> "{}"
            }
            return NativeToolCall(name, argsJson)
        }

        val body2 = trimmed.removePrefix("call:").trim()
        val openBrace = body2.indexOfFirst { it == '{' || it == '(' }
        if (openBrace < 0) return body2.takeIf { it.isNotBlank() }?.let { NativeToolCall(it, "{}") }
        val name = body2.substring(0, openBrace).trim()
        if (name.isBlank()) return null
        val closeBrace = if (body2[openBrace] == '{') body2.lastIndexOf('}') else body2.lastIndexOf(')')
        val argsJson = if (closeBrace > openBrace && body2[openBrace] == '{') {
            body2.substring(openBrace, closeBrace + 1).takeIf { runCatching { JSONObject(it) }.isSuccess } ?: "{}"
        } else "{}"
        return NativeToolCall(name, argsJson)
    }

    private data class MarkerMatch(val start: Int, val end: Int)

    private fun findMarker(text: String, from: Int, markers: List<String>): MarkerMatch? {
        var best: MarkerMatch? = null
        for (marker in markers) {
            val idx = text.indexOf(marker, from)
            if (idx >= 0 && (best == null || idx < best.start)) {
                best = MarkerMatch(idx, idx + marker.length)
            }
        }
        return best
    }
}
