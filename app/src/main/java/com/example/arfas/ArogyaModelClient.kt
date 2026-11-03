package com.example.arfas

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

/**
 * A hosted model reached through an OpenAI-compatible chat-completions endpoint.
 * From Meta's Model API docs (dev.meta.ai/docs/models and /muse-glimmer/deploy), read 31 Oct 2026:
 *  - Muse Spark 1.3 on the Meta Model API: base https://api.meta.ai/v1, model "muse-spark-1.3" (Meta key).
 *  - Muse Glimmer 30B (Meta open weights) hosted by Together AI: base https://api.together.xyz/v1,
 *    model "meta-models/Muse-Glimmer-30B" (Together key). Glimmer is not on the Meta Model API itself.
 * Not yet called live from this project (no keys yet).
 */
data class ArogyaModelEndpoint(val id: String, val label: String, val baseUrl: String, val model: String, val metaModel: Boolean, val keyFor: String = "meta")

object ArogyaModelEndpoints {
    /** Not a hosted endpoint: the phone's installed model (Llama 3.2 3B). */
    const val ON_PHONE = "on_phone"
    val all: List<ArogyaModelEndpoint> = listOf(
        ArogyaModelEndpoint("meta", "Muse Spark 1.3 — Meta Model API", MetaCloud.BASE, MetaCloud.SPARK, true, "meta"),
        ArogyaModelEndpoint("together_glimmer", "Muse Glimmer 30B — Together AI", MetaCloud.TOGETHER_BASE, MetaCloud.GLIMMER, true, "together")
    )
    fun byId(id: String): ArogyaModelEndpoint? = all.firstOrNull { it.id == id }
}

/** Minimal blocking client (call from a background thread). Plain JDK HTTP so it is host-testable. */
class OpenAiCompatibleClient(private val endpoint: ArogyaModelEndpoint, private val key: () -> String, private val timeoutMs: Int = 60_000) {
    class HttpError(val code: Int, msg: String) : Exception(msg)

    fun requestBody(system: String, user: String, maxTokens: Int): String {
        val b = JSONObject()
            .put("model", endpoint.model)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)))
            .put("max_tokens", maxTokens)
            .put("temperature", 0)
        // Muse Spark reasons by default; visit AI needs the fastest answer (V1 proved minimal works).
        if (ArogyaFlags.r11LowReasoning && endpoint.model.startsWith("muse-spark")) b.put("reasoning_effort", "minimal")
        return b.toString()
    }

    /**
     * Part B8: SSE streaming variant of [complete]. Each content piece goes to [onDelta]
     * as it arrives; returns the full text, or "" when the provider sent no stream
     * (the caller falls back to [complete]). A real cancellation is rethrown.
     */
    suspend fun completeStream(system: String, user: String, maxTokens: Int, onDelta: (String) -> Unit): String = withContext(Dispatchers.IO) {
        val k = key().trim()
        if (k.isBlank()) throw HttpError(0, "No API key saved for ${endpoint.label}.")
        val conn = URL(endpoint.baseUrl.trimEnd('/') + "/chat/completions").openConnection() as HttpURLConnection
        val full = StringBuilder()
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = timeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $k")
            conn.setRequestProperty("Accept", "text/event-stream")
            val body = JSONObject(requestBody(system, user, maxTokens)).put("stream", true).toString()
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.use { it.readBytes().decodeToString() }.orEmpty()
                throw HttpError(code, "HTTP $code from ${endpoint.label}: ${err.take(200)}")
            }
            conn.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (coroutineContext[Job]?.isActive == false) throw kotlinx.coroutines.CancellationException("stream cancelled")
                    SseStreamParser.contentOf(line)?.let { piece ->
                        if (piece.isNotEmpty()) {
                            full.append(piece)
                            onDelta(piece)
                        }
                    }
                }
            }
            full.toString().trim()
        } finally { conn.disconnect() }
    }

    fun complete(system: String, user: String, maxTokens: Int = 600): String {
        val k = key().trim()
        if (k.isBlank()) throw HttpError(0, "No API key saved for ${endpoint.label}.")
        val conn = URL(endpoint.baseUrl.trimEnd('/') + "/chat/completions").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = timeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $k")
            conn.outputStream.use { it.write(requestBody(system, user, maxTokens).toByteArray()) }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
            if (code !in 200..299) throw HttpError(code, "HTTP $code from ${endpoint.label}: ${body.take(200)}")
            val message = JSONObject(body).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            // content is a string, or a list of parts on some providers
            val c = message.opt("content")
            return when (c) {
                is String -> c
                is JSONArray -> (0 until c.length()).joinToString("") { c.optJSONObject(it)?.optString("text").orEmpty() }
                else -> ""
            }.trim()
        } finally { conn.disconnect() }
    }
}
