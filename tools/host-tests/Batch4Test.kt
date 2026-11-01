import com.example.arfas.*
var fails = 0; var n = 0
fun check(name: String, ok: Boolean) { n++; if (!ok) { fails++; println("FAIL $name") } }
fun main() {
    // Voice choice mappings.
    check("v-whisper-hi", ArogyaLang.whisperLang("hi") == "hi")
    check("v-whisper-en", ArogyaLang.whisperLang("en") == "en")
    check("v-whisper-auto", ArogyaLang.whisperLang("auto") == "auto")
    check("v-meta-hi", ArogyaLang.metaLanguages("hi") == listOf("Hindi"))
    check("v-meta-en", ArogyaLang.metaLanguages("en") == listOf("English"))
    check("v-meta-auto", ArogyaLang.metaLanguages("auto") == listOf("Hindi", "English"))
    // TTS voice ranking: (locale, quality, networkRequired).
    val hiIn = Triple(java.util.Locale("hi", "IN"), 300, false)
    val hi = Triple(java.util.Locale("hi"), 500, false)
    val hiNet = Triple(java.util.Locale("hi", "IN"), 500, true)
    val enIn = Triple(java.util.Locale("en", "IN"), 300, false)
    check("tts-in-country", ArogyaLang.rankVoice(listOf(hi, hiIn), "hi", false) == 1)
    check("tts-offline-first", ArogyaLang.rankVoice(listOf(hiIn, hiNet), "hi", false) == 0)
    check("tts-online", ArogyaLang.rankVoice(listOf(hiIn, hiNet), "hi", true) == 1)
    check("tts-fallback", ArogyaLang.rankVoice(listOf(hiNet), "hi", false) == 0)
    check("tts-missing", ArogyaLang.rankVoice(listOf(enIn), "hi", false) == -1)
    check("tts-quality", ArogyaLang.rankVoice(listOf(Triple(java.util.Locale("en", "IN"), 100, false), Triple(java.util.Locale("en", "IN"), 400, false)), "en", false) == 1)
    // Hindi register labels present.
    check("reg-cat", BenCategory.values().all { it.titleHi.isNotBlank() } && BenCategory.PREGNANT.titleHi == "गर्भवती महिला")
    check("reg-diary", DiaryKind.values().all { it.titleHi.isNotBlank() })
    check("reg-vital", VitalKind.values().all { it.titleHi.isNotBlank() })
    println("batch4: $n checks, $fails failures")
    if (fails > 0) kotlin.system.exitProcess(1)
}
