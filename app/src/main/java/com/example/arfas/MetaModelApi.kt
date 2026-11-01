package com.example.arfas

import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Meta Model API (https://api.meta.ai/v1) — the newest Meta models, used where the phone alone
 * cannot do the job:
 *  - Muse Spark 1.3     reads a photo (MCP card, register page, medicine label, MUAC tape),
 *                       writes structured visit records (JSON schema), web-search-grounded checks
 *  - SAM 3.1            finds any object from a text phrase ("MUAC tape", "ORS packet") with boxes
 *  - Muse Voice Transcribe  turns a home-visit conversation into a speaker-labelled transcript
 *  - Muse Image         draws a counselling picture card for a mother who cannot read
 * Every request goes through [PrivacyGate] first: names and phone numbers are masked and an
 * egress record (what left the phone, how big, when — never the content) is written.
 */
class MetaModelApi(
    private val apiKey: () -> String,
    private val gate: PrivacyGate,
    private val http: OkHttpClient = NetworkClient.okHttpClient.newBuilder()
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        private const val TAG = "MetaModelApi"
        const val BASE = "https://api.meta.ai/v1"
        const val SPARK = "muse-spark-1.3"
        const val SAM = "sam-3.1"
        const val VOICE = "muse-voice-transcribe-1.0"
        const val IMAGE = "muse-image-1.0"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }

    class ApiException(val code: Int, message: String) : Exception(message)

    val isConfigured: Boolean get() = apiKey().let { it.isNotBlank() && !it.startsWith("gsk_") }

    private fun request(path: String) = Request.Builder()
        .url(BASE + path)
        .addHeader("Authorization", "Bearer ${apiKey()}")

    private fun execute(req: Request, feature: String, bytesOut: Long): String {
        val started = System.currentTimeMillis()
        http.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            gate.recordEgress(feature, bytesOut, r.code, System.currentTimeMillis() - started)
            if (!r.isSuccessful) throw ApiException(r.code, "HTTP ${r.code}: ${body.take(300)}")
            return body
        }
    }

    // ── Muse Spark ───────────────────────────────────────────────────────────────

    /**
     * Chat completion with optional photo and optional JSON schema (strict structured output).
     * Returns the message content (JSON text when [schema] is given).
     */
    suspend fun spark(
        feature: String,
        system: String,
        user: String,
        imageJpeg: ByteArray? = null,
        schema: JSONObject? = null,
        schemaName: String = "result",
        effort: String = "low"
    ): String = withContext(Dispatchers.IO) {
        val safeUser = gate.redact(user)
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", safeUser))
        if (imageJpeg != null) {
            val b64 = android.util.Base64.encodeToString(imageJpeg, android.util.Base64.NO_WRAP)
            content.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64")))
        }
        val body = JSONObject()
            .put("model", SPARK)
            .put("reasoning_effort", effort)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "developer").put("content", system))
                .put(JSONObject().put("role", "user").put("content", content)))
        if (schema != null) {
            body.put("response_format", JSONObject().put("type", "json_schema")
                .put("json_schema", JSONObject().put("name", schemaName).put("schema", schema)))
        }
        val text = body.toString()
        val raw = execute(request("/chat/completions").post(text.toRequestBody(JSON)).build(), feature, text.length.toLong())
        JSONObject(raw).getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content").trim()
    }

    data class GroundedAnswer(val text: String, val sources: List<Pair<String, String>>)

    /** Muse Spark with web search: "is this guideline still current?" Returns text + (title, url). */
    suspend fun sparkWebSearch(feature: String, question: String): GroundedAnswer = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("model", SPARK)
            .put("input", gate.redact(question))
            .put("tools", JSONArray().put(JSONObject().put("type", "web_search")))
            .toString()
        val raw = execute(request("/responses").post(body.toRequestBody(JSON)).build(), feature, body.length.toLong())
        parseResponsesText(JSONObject(raw))
    }

    internal fun parseResponsesText(resp: JSONObject): GroundedAnswer {
        val sb = StringBuilder()
        val sources = ArrayList<Pair<String, String>>()
        val output = resp.optJSONArray("output") ?: JSONArray()
        for (i in 0 until output.length()) {
            val item = output.getJSONObject(i)
            if (item.optString("type") != "message") continue
            val parts = item.optJSONArray("content") ?: continue
            for (j in 0 until parts.length()) {
                val p = parts.getJSONObject(j)
                if (p.optString("type") != "output_text") continue
                sb.append(p.optString("text"))
                val anns = p.optJSONArray("annotations") ?: continue
                for (k in 0 until anns.length()) {
                    val a = anns.getJSONObject(k)
                    if (a.optString("type") == "url_citation") sources += a.optString("title") to a.optString("url")
                }
            }
        }
        val text = sb.toString().ifBlank { resp.optString("output_text") }
        return GroundedAnswer(text.trim(), sources.distinctBy { it.second })
    }

    // ── SAM 3.1 ──────────────────────────────────────────────────────────────────

    /**
     * Finds every instance of [concept] (a short noun phrase: "MUAC tape", "ORS packet") in the photo.
     * Boxes are in pixels of the sent image; the mask payload is not decoded (boxes drive the AR box).
     */
    suspend fun sam(feature: String, imageJpeg: ByteArray, concept: String): List<SamBox> = withContext(Dispatchers.IO) {
        val b64 = android.util.Base64.encodeToString(imageJpeg, android.util.Base64.NO_WRAP)
        val body = JSONObject()
            .put("model", SAM)
            .put("stream", true)
            .put("input", JSONArray().put(JSONObject()
                .put("type", "message").put("role", "user")
                .put("content", JSONArray()
                    .put(JSONObject().put("type", "input_text").put("text", concept.trim()))
                    .put(JSONObject().put("type", "input_image").put("image_url", "data:image/jpeg;base64,$b64")))))
            .toString()
        val req = request("/responses").addHeader("Accept", "text/event-stream").post(body.toRequestBody(JSON)).build()
        val started = System.currentTimeMillis()
        val text = StringBuilder()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) {
                gate.recordEgress(feature, body.length.toLong(), r.code, System.currentTimeMillis() - started)
                throw ApiException(r.code, "HTTP ${r.code}: ${r.body?.string()?.take(300)}")
            }
            val src = r.body?.source() ?: return@use
            while (!src.exhausted()) {
                val line = src.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]" || data.isEmpty()) continue
                val ev = runCatching { JSONObject(data) }.getOrNull() ?: continue
                when (ev.optString("type")) {
                    "response.output_text.delta" -> text.append(ev.optString("delta"))
                    "response.completed" -> if (text.isEmpty()) text.append(parseResponsesText(ev.optJSONObject("response") ?: JSONObject()).text)
                }
            }
            gate.recordEgress(feature, body.length.toLong(), r.code, System.currentTimeMillis() - started)
        }
        SamRecordParser.parseBoxes(text.toString())
    }

    // ── Muse Voice Transcribe ────────────────────────────────────────────────────

    data class Turn(val speaker: String, val startMs: Long, val text: String)
    data class Transcript(val text: String, val turns: List<Turn>, val durationMs: Long)

    /**
     * A recorded home visit (mono 16-bit WAV, 16 or 24 kHz, <= 10 min) -> transcript with speaker
     * labels (DIARIZATION: ASHA vs mother). Health words are passed as keyword bias.
     */
    suspend fun transcribe(
        feature: String,
        wav: File,
        languages: List<String> = listOf("Hindi", "English"),
        keywords: List<String> = HEALTH_KEYWORDS,
        diarize: Boolean = true
    ): Transcript = withContext(Dispatchers.IO) {
        val params = JSONObject()
            .put("mode", if (diarize) "DIARIZATION" else "PUSH_TO_TALK")
            .put("model", VOICE)
            .put("audioEncoding", "WAV")
            .put("keywords", JSONArray(keywords))
            .put("languageBias", JSONArray(languages))
        // Hand-built multipart (V1: the service rejects OkHttp's generated framing with
        // "Malformed multipart body" but accepts this exact byte shape — no per-part
        // Content-Length, "application/json" without charset on the params part).
        val boundary = "----ArogyaTranscribe" + java.util.UUID.randomUUID().toString().take(8)
        val out = java.io.ByteArrayOutputStream()
        fun part(headers: String, bytes: ByteArray) {
            out.write(("--$boundary\r\n$headers\r\n\r\n").toByteArray())
            out.write(bytes); out.write("\r\n".toByteArray())
        }
        part("Content-Disposition: form-data; name=\"request\"\r\nContent-Type: application/json", params.toString().toByteArray())
        part("Content-Disposition: form-data; name=\"audio\"; filename=\"${wav.name}\"\r\nContent-Type: audio/wav", wav.readBytes())
        out.write(("--$boundary--\r\n").toByteArray())
        val payload = out.toByteArray()
        val raw = execute(request("/asr/transcribe").post(payload.toRequestBody("multipart/form-data; boundary=$boundary".toMediaType())).build(), feature, wav.length())
        parseTranscript(JSONObject(raw))
    }

    internal fun parseTranscript(j: JSONObject): Transcript {
        val turns = ArrayList<Turn>()
        val arr = j.optJSONArray("turns") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val t = arr.getJSONObject(i)
            turns += Turn(t.optString("speaker", ""), t.optLong("startMs", 0L), t.optString("transcript", "").trim())
        }
        return Transcript(j.optString("transcript", turns.joinToString(" ") { it.text }).trim(), turns, j.optLong("audioDurationMs", 0L))
    }

    // ── Muse Image ───────────────────────────────────────────────────────────────

    /** A picture counselling card (e.g. "how to make ORS at home", labels in Hindi). Returns image bytes. */
    suspend fun image(feature: String, prompt: String): ByteArray = withContext(Dispatchers.IO) {
        val body = JSONObject().put("model", IMAGE).put("input", gate.redact(prompt)).toString()
        val raw = execute(request("/responses").post(body.toRequestBody(JSON)).build(), feature, body.length.toLong())
        val output = JSONObject(raw).optJSONArray("output") ?: JSONArray()
        for (i in 0 until output.length()) {
            val item = output.getJSONObject(i)
            if (item.optString("type") == "image_generation_call") {
                return@withContext android.util.Base64.decode(item.optString("result"), android.util.Base64.DEFAULT)
            }
        }
        throw ApiException(200, "Muse Image returned no image")
    }
}

/** Words Muse Voice Transcribe should not mis-hear on a home visit. */
val HEALTH_KEYWORDS = listOf(
    "ASHA", "ANM", "ANC", "PNC", "HBNC", "IFA", "ORS", "zinc", "MUAC", "Hb", "haemoglobin",
    "JSY", "MCP card", "TT", "Td", "BCG", "OPV", "pentavalent", "PHC", "CHC", "BP", "albendazole",
    "calcium", "folic acid", "anganwadi", "institutional delivery", "kangaroo care"
)

