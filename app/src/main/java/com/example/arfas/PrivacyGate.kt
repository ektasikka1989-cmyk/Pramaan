package com.example.arfas

import java.io.File
import org.json.JSONObject

/**
 * What may leave the phone. Health data of a pregnant woman is sensitive: before any Meta Model
 * API call, identifiers are masked, and every call is logged (feature, size, time, result — never
 * the content), like the egress log in Meta's own "local model asks before calling Muse Spark"
 * cookbook pattern. The log is what an ANM or a judge can open to see exactly what went online.
 */
class PrivacyGate(private val logFile: File) {

    private val knownNames = LinkedHashSet<String>()

    /** Names typed into the app for this visit (beneficiary, husband, child) are masked as well. */
    fun addKnownNames(names: Collection<String>) {
        names.map { it.trim() }.filter { it.length >= 3 }.forEach { knownNames += it }
    }

    fun redact(text: String): String {
        var out = text
        out = AADHAAR.replace(out, "[ID]")
        out = PHONE.replace(out, "[PHONE]")
        out = LONG_NUMBER_ID.replace(out, "[ID]")
        knownNames.sortedByDescending { it.length }.forEach { name ->
            out = Regex("(?i)(?<![\\p{L}])" + Regex.escape(name) + "(?![\\p{L}])").replace(out, "[NAME]")
        }
        return out
    }

    fun recordEgress(feature: String, bytes: Long, httpCode: Int, ms: Long) {
        runCatching {
            logFile.parentFile?.mkdirs()
            logFile.appendText(
                JSONObject()
                    .put("t", System.currentTimeMillis())
                    .put("feature", feature)
                    .put("bytes", bytes)
                    .put("http", httpCode)
                    .put("ms", ms)
                    .toString() + "\n"
            )
        }
    }

    data class Egress(val timeMs: Long, val feature: String, val bytes: Long, val http: Int, val ms: Long)

    fun recent(limit: Int = 50): List<Egress> = runCatching {
        logFile.readLines().takeLast(limit).mapNotNull { line ->
            runCatching {
                val j = JSONObject(line)
                Egress(j.getLong("t"), j.getString("feature"), j.getLong("bytes"), j.getInt("http"), j.getLong("ms"))
            }.getOrNull()
        }
    }.getOrDefault(emptyList())

    companion object {
        private val AADHAAR = Regex("(?<!\\d)\\d{4}[ -]?\\d{4}[ -]?\\d{4}(?!\\d)")
        private val PHONE = Regex("(?<!\\d)(?:\\+?91[ -]?)?[6-9]\\d{4}[ -]?\\d{5}(?!\\d)")
        private val LONG_NUMBER_ID = Regex("(?<!\\d)\\d{11,}(?!\\d)")
    }
}
