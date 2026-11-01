package com.example.arfas

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * V1 fallback twin: airplane mode ON, policy stays Cloud first — the phone must take over.
 * The script enables airplane mode before this class and restores it after.
 */
@RunWith(AndroidJUnit4::class)
class V1FallbackTest {
    @Test
    fun fallback() = runBlocking {
        val c = ArogyaSelfTest.ctx()
        val prevPolicy = ArogyaSelfTest.policy(c)
        ArogyaSelfTest.setPolicy(c, AnswerPolicy.CLOUD_FIRST)
        val checks = ArrayList<ArogyaSelfTest.Check>()
        fun add(area: String, name: String, ms: Long, ok: Boolean, detail: String) {
            checks += ArogyaSelfTest.Check(area, name, ok, ms, detail)
            ArogyaSelfTest.mark("${if (ok) "PASS" else "FAIL"} $area/$name ${ms}ms ${detail.take(160)}")
        }
        try {
            add("setup", "no internet", 0, !ArogyaCloud.online(c), if (ArogyaCloud.online(c)) "internet reachable — airplane mode not on?" else "airplane confirmed")
            val ai = ArogyaSelfTest.newAi(c)
            runCatching { ai.warmUp() }
            run {
                val t = System.currentTimeMillis()
                val detail = runCatching {
                    val r = ai.run(ArogyaAiRequest(ArogyaAiTask.EXPLAIN, ArogyaVisit(name = "SelfTest", kind = ArogyaKind.FOLLOW_UP),
                        question = "How should a mother prepare ORS at home for a child with diarrhoea?", afterSafetyPrompt = true))
                    check(!r.online) { "claims online with no signal: ${r.provider}" }
                    check(r.text.isNotBlank()) { "empty answer" }
                    "provider=${r.provider} chars=${r.text.length} src=${r.sources.size}"
                }
                add("fallback", "Ask SOP answered on phone", System.currentTimeMillis() - t, detail.isSuccess, detail.getOrElse { it.message ?: "failed" })
            }
            run {
                val t = System.currentTimeMillis()
                val detail = runCatching {
                    val synth = ArogyaSelfTest.synthSpeech(c, "Baby weight two point nine kilograms, no fever.", Locale("en", "IN"), "fb_en")
                    val rec = AndroWhisperRecognizer(c)
                    check(rec.ensureInitialized()) { "WHISPER_MODEL_MISSING" }
                    val s = rec.newSession(maxSeconds = 60, streaming = false); s.append(synth.pcm16k)
                    val text = s.finish()
                    check(text.isNotBlank()) { "whisper returned blank" }
                    "synthetic voice → ${text.take(140)}"
                }
                add("fallback", "whisper offline", System.currentTimeMillis() - t, detail.isSuccess, detail.getOrElse { it.message ?: "failed" })
            }
        } finally {
            ArogyaSelfTest.setPolicy(c, prevPolicy)
        }
        val fails = checks.filter { !it.ok }
        ArogyaSelfTest.writeResults(c, "v1_fallback",
            JSONObject().put("mode", "cloud-first/airplane").put("checks", ArogyaSelfTest.checksJson(checks)),
            ArogyaSelfTest.mdReport("V1 fallback (Cloud first, airplane mode)", mapOf("policy" to "CLOUD_FIRST (restored after)"), checks))
        assertTrue("fallback failures: " + fails.joinToString("; ") { it.area + "/" + it.name + ": " + it.detail.take(120) }, fails.isEmpty())
    }
}
