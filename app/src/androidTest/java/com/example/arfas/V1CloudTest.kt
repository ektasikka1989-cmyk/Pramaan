package com.example.arfas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * V1 live Meta test (Cloud first, online). Drives the same real functions the UI uses.
 * Run with internet on; the script toggles airplane mode for the fallback twin.
 */
@RunWith(AndroidJUnit4::class)
class V1CloudTest {
    private val EN = "Baby of Ritu, day fourteen visit. Baby weight two point nine kilograms. Temperature thirty-six point eight. Baby is feeding well, no fever, cord is clean and dry."
    private val HI = "रीतू का बच्चा, चौदहवें दिन की विज़िट। वज़न दो किलो नौ सौ ग्राम। बुखार नहीं है, बच्चा अच्छा दूध पी रहा है, नाल सूखी है।"
    private val HING = "Baby of Ritu, day fourteen visit. Weight two point nine kilo, bukhar nahi hai, baby feed achhe se le raha hai."
    private val NOTE = "Baby of Ritu, day 14 visit. Weight 2.9 kg, temperature 36.8. Feeding well, no fever, cord clean and dry."

    @Test
    fun v1() = runBlocking {
        val c = ArogyaSelfTest.ctx()
        val prevPolicy = ArogyaSelfTest.policy(c)
        ArogyaSelfTest.setPolicy(c, AnswerPolicy.CLOUD_FIRST)
        val checks = ArrayList<ArogyaSelfTest.Check>()
        val answers = JSONArray()
        fun add(area: String, name: String, ms: Long, ok: Boolean, detail: String) {
            checks += ArogyaSelfTest.Check(area, name, ok, ms, detail)
            ArogyaSelfTest.mark("${if (ok) "PASS" else "FAIL"} $area/$name ${ms}ms ${detail.take(160)}")
        }
        try {
            val ai = ArogyaSelfTest.newAi(c)
            val kbReady = runCatching { ai.warmUp(); true }.getOrDefault(false)
            add("setup", "warm-up", 0, kbReady, if (kbReady) "ok" else "warmUp returned false")

            // 1. Connection test — the same call as "Save and test connection".
            run {
                val t = System.currentTimeMillis()
                val choice = ArogyaCloud.choice(c)
                val detail = runCatching {
                    val ep = choice.endpoint ?: error("no cloud endpoint chosen")
                    val key = choice.key; check(key.isNotBlank()) { "no key saved for ${ep.label}" }
                    check(ArogyaCloud.online(c)) { "no internet right now" }
                    val reply = OpenAiCompatibleClient(ep, { key }, 30_000).complete("You are a health assistant.", "Name one ORS ingredient in one sentence.", 900)
                    check(reply.isNotBlank()) { "empty reply" }
                    "endpoint=${ep.id} reply=${reply.trim().take(60)}"
                }
                add("cloud", "connection test", System.currentTimeMillis() - t, detail.isSuccess, detail.getOrElse { it.message ?: "failed" })
            }

            // 1b. Raw-shape probe: what does the service actually return, and does reasoning effort matter?
            run {
                val t = System.currentTimeMillis()
                val choice = ArogyaCloud.choice(c)
                val detail = runCatching {
                    val ep = choice.endpoint ?: error("no endpoint"); val key = choice.key
                    check(key.isNotBlank()) { "no key" }
                    fun post(bodyJson: String): String {
                        val conn = java.net.URL(ep.baseUrl.trimEnd('/') + "/chat/completions").openConnection() as java.net.HttpURLConnection
                        try {
                            conn.requestMethod = "POST"; conn.connectTimeout = 15_000; conn.readTimeout = 60_000; conn.doOutput = true
                            conn.setRequestProperty("Content-Type", "application/json")
                            conn.setRequestProperty("Authorization", "Bearer $key")
                            conn.outputStream.use { it.write(bodyJson.toByteArray()) }
                            val code = conn.responseCode
                            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
                            return "http=$code " + body.take(500)
                        } finally { conn.disconnect() }
                    }
                    fun chatBody(max: Int, effort: String?): String {
                        val msgs = org.json.JSONArray()
                            .put(org.json.JSONObject().put("role", "system").put("content", "You are a health assistant."))
                            .put(org.json.JSONObject().put("role", "user").put("content", "Name one ORS ingredient in one sentence."))
                        val b = org.json.JSONObject().put("model", ep.model).put("messages", msgs).put("max_tokens", max).put("temperature", 0)
                        if (effort != null) b.put("reasoning_effort", effort)
                        return b.toString()
                    }
                    buildString {
                        append("plain@900="); append(post(chatBody(900, null)).take(400))
                        append(" | minimal@300="); append(post(chatBody(300, "minimal")).take(400))
                        append(" | none@300="); append(post(chatBody(300, "none")).take(400))
                    }
                }
                add("cloud", "raw chat probe", System.currentTimeMillis() - t, true, detail.getOrElse { it.message ?: "failed" })
            }

            // 2. Spark form filling on the typed note.
            run {
                val t = System.currentTimeMillis()
                val ritu = ArogyaSampleVillage.build().registry.beneficiaries.first { it.name.startsWith("Baby of Ritu") }
                val detail = runCatching {
                    val (filled, ext) = ai.fillForm(ritu, ArogyaOfficialForms.ANNEX9, 3, NOTE)
                    check(filled.isNotEmpty()) { "no fields filled" }
                    check(ext.modelLabel.isNotBlank()) { "no model label" }
                    "fields=${filled.size} [${filled.keys.joinToString(",")}] label=${ext.modelLabel} meta=${ext.metaModel}"
                }
                add("cloud", "form filling (typed note)", System.currentTimeMillis() - t, detail.isSuccess, detail.getOrElse { it.message ?: "failed" })
            }

            // 3. Ask SOP through the real path; Cloud first must answer online with a source row.
            run {
                val t = System.currentTimeMillis()
                val detail = runCatching {
                    val r = ai.run(ArogyaAiRequest(ArogyaAiTask.EXPLAIN, ArogyaVisit(name = "SelfTest", kind = ArogyaKind.FOLLOW_UP),
                        question = "How should a mother prepare ORS at home for a child with diarrhoea?", afterSafetyPrompt = true))
                    answers.put(JSONObject().put("q", "ORS").put("provider", r.provider).put("online", r.online)
                        .put("chars", r.text.length).put("sources", JSONArray(r.sources.map { it.title + " | " + it.locator })).put("text", r.text))
                    check(r.online) { "answered by ${r.provider}, not online" }
                    check(r.text.isNotBlank()) { "empty answer" }
                    check(r.sources.isNotEmpty()) { "no source row" }
                    "provider=${r.provider} chars=${r.text.length} src=${r.sources.first().title.take(50)}"
                }
                add("cloud", "Ask SOP online", System.currentTimeMillis() - t, detail.isSuccess, detail.getOrElse { it.message ?: "failed" })
            }

            // 4. Speech-to-text: synthetic voice (Android TTS) → Whisper + Muse Transcribe.
            val rec = AndroWhisperRecognizer(c)
            val whisperReady = runCatching { rec.ensureInitialized() }.getOrDefault(false)
            add("voice", "whisper ready (${rec.activeModel.ifBlank { "none" }})", 0, whisperReady, if (whisperReady) "ok" else "WHISPER_MODEL_MISSING")
            listOf(Triple("en", EN, Locale("en", "IN")), Triple("hi", HI, Locale("hi", "IN")), Triple("hinglish", HING, Locale("en", "IN"))).forEach { (tag, line, loc) ->
                val synth = runCatching { ArogyaSelfTest.synthSpeech(c, line, loc, tag) }
                add("voice", "synthetic voice $tag", 0, synth.isSuccess, synth.fold({ "synthetic voice (Android TTS ${it.engine}) lang=${it.langStatus} ${it.pcm16k.size / 16}ms" }, { it.message ?: "failed" }))
                val pcm = synth.getOrNull()?.pcm16k ?: return@forEach
                val wt = System.currentTimeMillis()
                val wtext = if (whisperReady) runCatching {
                    val s = rec.newSession(maxSeconds = 60, streaming = false); s.append(pcm); s.finish()
                } else Result.failure(IllegalStateException("whisper not ready"))
                add("voice", "whisper $tag", System.currentTimeMillis() - wt, wtext.getOrDefault("").isNotBlank(),
                    "synthetic voice → " + wtext.getOrElse { it.message ?: "failed" }.take(220))
                val mt = System.currentTimeMillis()
                val mtext = runCatching { ArogyaCloud.transcribe(c, pcm) ?: error("transcribe returned null (no key/signal?)") }
                add("voice", "muse transcribe $tag", System.currentTimeMillis() - mt, mtext.getOrDefault("").isNotBlank(),
                    "synthetic voice → " + mtext.getOrElse { it.message ?: "failed" }.take(220))
            }

            // 4b. Transcribe multipart shape probe: which variant does the service accept?
            run {
                val t = System.currentTimeMillis()
                val detail = runCatching {
                    val synth = ArogyaSelfTest.synthSpeech(c, "Testing one two three.", Locale("en", "IN"), "probe")
                    val key = GroqKeyStore(c).getKey()
                    check(key.isNotBlank()) { "no key" }
                    val http = okhttp3.OkHttpClient.Builder()
                        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                        .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS).build()
                    val jsonMedia = "application/json; charset=utf-8".toMediaType()
                    fun post(body: okhttp3.RequestBody): String {
                        val req = okhttp3.Request.Builder().url(MetaModelApi.BASE + "/asr/transcribe")
                            .addHeader("Authorization", "Bearer $key").post(body).build()
                        http.newCall(req).execute().use { r ->
                            val b = r.body?.string().orEmpty()
                            return "HTTP${r.code} ${b.take(300)}"
                        }
                    }
                    val params = org.json.JSONObject().put("mode", "PUSH_TO_TALK").put("model", MetaModelApi.VOICE)
                        .put("audioEncoding", "WAV").put("languageBias", org.json.JSONArray(listOf("Hindi", "English"))).toString()
                    val audioBytes = synth.wavFile.readBytes()
                    buildString {
                        append("json-only="); append(runCatching {
                            post(okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
                                .addFormDataPart("request", null, params.toRequestBody(jsonMedia)).build())
                        }.getOrElse { it.message?.take(150).orEmpty() })
                        append(" | audio-only="); append(runCatching {
                            post(okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
                                .addFormDataPart("audio", synth.wavFile.name, audioBytes.toRequestBody("audio/wav".toMediaType())).build())
                        }.getOrElse { it.message?.take(150).orEmpty() })
                        append(" | related="); append(runCatching {
                            post(okhttp3.MultipartBody.Builder().setType("multipart/related".toMediaType())
                                .addFormDataPart("request", null, params.toRequestBody(jsonMedia))
                                .addFormDataPart("audio", synth.wavFile.name, audioBytes.toRequestBody("audio/wav".toMediaType())).build())
                        }.getOrElse { it.message?.take(150).orEmpty() })
                        append(" | octet="); append(runCatching {
                            post(okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
                                .addFormDataPart("request", "request.json", params.toRequestBody(jsonMedia))
                                .addFormDataPart("audio", synth.wavFile.name, audioBytes.toRequestBody("application/octet-stream".toMediaType())).build())
                        }.getOrElse { it.message?.take(150).orEmpty() })
                        append(" | textfields="); append(runCatching {
                            post(okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
                                .addFormDataPart("model", MetaModelApi.VOICE)
                                .addFormDataPart("audio", synth.wavFile.name, audioBytes.toRequestBody("audio/wav".toMediaType())).build())
                        }.getOrElse { it.message?.take(150).orEmpty() })
                        append(" | handbuilt="); append(runCatching {
                            val boundary = "----ArogyaProbeBoundary7d067a1c"
                            val out = java.io.ByteArrayOutputStream()
                            fun part(headers: String, bytes: ByteArray) {
                                out.write(("--" + boundary + "\r\n" + headers + "\r\n\r\n").toByteArray())
                                out.write(bytes); out.write("\r\n".toByteArray())
                            }
                            part("Content-Disposition: form-data; name=\"request\"\r\nContent-Type: application/json", params.toByteArray())
                            part("Content-Disposition: form-data; name=\"audio\"; filename=\"" + synth.wavFile.name + "\"\r\nContent-Type: audio/wav", audioBytes)
                            out.write(("--" + boundary + "--\r\n").toByteArray())
                            val conn = java.net.URL(MetaModelApi.BASE + "/asr/transcribe").openConnection() as java.net.HttpURLConnection
                            try {
                                conn.requestMethod = "POST"; conn.connectTimeout = 15_000; conn.readTimeout = 60_000; conn.doOutput = true
                                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary)
                                conn.setRequestProperty("Authorization", "Bearer $key")
                                val payload = out.toByteArray()
                                conn.setFixedLengthStreamingMode(payload.size)
                                conn.outputStream.use { it.write(payload) }
                                val code = conn.responseCode
                                val b = (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
                                "HTTP$code sent=${payload.size}B ${b.take(300)}"
                            } finally { conn.disconnect() }
                        }.getOrElse { it.message?.take(200).orEmpty() })
                    }
                }
                add("voice", "transcribe shape probe", System.currentTimeMillis() - t, true, detail.getOrElse { it.message ?: "failed" })
            }

            // 5. SAM 3.1 on a synthetic labelled image (6 drawn pills).
            run {
                val t = System.currentTimeMillis()
                val detail = runCatching {
                    val bmp = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
                    val cv = Canvas(bmp); cv.drawColor(Color.WHITE)
                    val paint = Paint().apply { color = Color.rgb(120, 20, 20); isAntiAlias = true }
                    val spots = listOf(90f to 110f, 230f to 90f, 370f to 120f, 130f to 280f, 300f to 270f, 400f to 360f)
                    spots.forEach { (x, y) -> cv.drawRoundRect(x, y, x + 90, y + 44, 22f, 22f, paint) }
                    val jpeg = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
                    val boxes = MetaOnline.api(c).sam("arogya_selftest_sam", jpeg, "pill")
                    val n = ArogyaSamCount.count(boxes.map { it.confidence })
                    "synthetic image (6 drawn pills) accepted, count=$n boxes=${boxes.size} jpeg=${jpeg.size}B"
                }
                add("cloud", "SAM 3.1 count", System.currentTimeMillis() - t, detail.isSuccess, detail.getOrElse { it.message ?: "failed" })
            }

            // 6. Glimmer only when a Together key exists.
            run {
                val t = System.currentTimeMillis()
                val ep = ArogyaModelEndpoints.byId("together_glimmer")
                val key = if (ep == null) "" else ArogyaCloud.keyFor(c, ep)
                if (ep == null || key.isBlank() || !ArogyaCloud.online(c)) {
                    add("cloud", "glimmer", 0, true, "skipped (no Together key/signal/endpoint)")
                } else {
                    val detail = runCatching {
                        val reply = OpenAiCompatibleClient(ep, { key }, 30_000).complete("Reply with one word.", "Say OK.", 300)
                        check(reply.isNotBlank()) { "empty reply" }
                        "reply=${reply.trim().take(60)}"
                    }
                    add("cloud", "glimmer", System.currentTimeMillis() - t, detail.isSuccess, detail.getOrElse { it.message ?: "failed" })
                }
            }
        } finally {
            ArogyaSelfTest.setPolicy(c, prevPolicy)
        }
        val fails = checks.filter { !it.ok }
        ArogyaSelfTest.writeResults(c, "v1",
            JSONObject().put("mode", "cloud-first/online").put("checks", ArogyaSelfTest.checksJson(checks)).put("answers", answers),
            ArogyaSelfTest.mdReport("V1 live Meta test (Cloud first, online)",
                mapOf("policy" to "CLOUD_FIRST (restored to $prevPolicy after)", "voice input" to "synthetic voice (Android TTS), NOT the microphone",
                    "sam image" to "synthetic (6 drawn pills)", "records" to "sample village only (demo=true)"), checks))
        assertTrue("V1 failures: " + fails.joinToString("; ") { it.area + "/" + it.name + ": " + it.detail.take(120) }, fails.isEmpty())
    }
}
