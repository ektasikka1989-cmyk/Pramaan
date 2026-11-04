import com.example.arfas.*
var fails = 0; var n = 0
fun check(name: String, ok: Boolean) { n++; if (!ok) { fails++; println("FAIL $name") } }

/** Builds a minimal WAV: pcm16 or float32, mono/stereo, at [rate] Hz, [frames] frames of a 440 Hz tone. */
fun synthWav(rate: Int, channels: Int, float32: Boolean, frames: Int, withFact: Boolean = false): ByteArray {
    val bps = if (float32) 4 else 2
    val data = ByteArray(frames * channels * bps)
    for (f in 0 until frames) {
        val v = kotlin.math.sin(2 * Math.PI * 440 * f / rate).toFloat()
        for (ch in 0 until channels) {
            val o = (f * channels + ch) * bps
            if (float32) {
                val b = java.lang.Float.floatToIntBits(v)
                data[o] = (b and 0xFF).toByte(); data[o + 1] = (b shr 8 and 0xFF).toByte()
                data[o + 2] = (b shr 16 and 0xFF).toByte(); data[o + 3] = (b shr 24 and 0xFF).toByte()
            } else {
                val s = (v * 30000).toInt()
                data[o] = (s and 0xFF).toByte(); data[o + 1] = (s shr 8 and 0xFF).toByte()
            }
        }
    }
    fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), (v shr 8 and 0xFF).toByte())
    fun le32(v: Int) = byteArrayOf((v and 0xFF).toByte(), (v shr 8 and 0xFF).toByte(), (v shr 16 and 0xFF).toByte(), (v shr 24 and 0xFF).toByte())
    val out = ArrayList<Byte>()
    fun put(s: String) = s.forEach { out += it.code.toByte() }
    fun putB(a: ByteArray) = a.forEach { out += it }
    val factLen = if (withFact) 12 else 0
    put("RIFF"); putB(le32(36 + data.size + factLen)); put("WAVE"); put("fmt "); putB(le32(16))
    putB(le16(if (float32) 3 else 1)); putB(le16(channels)); putB(le32(rate)); putB(le32(rate * channels * bps))
    putB(le16(channels * bps)); putB(le16(bps * 8))
    if (withFact) { put("fact"); putB(le32(4)); putB(le32(frames)) }
    put("data"); putB(le32(data.size)); putB(data)
    return out.toByteArray()
}

fun main() {
    // Script shape (demo UI pass): 16 scenes, 3-6 sentences per language, unique ids, one hero.
    check("s-count", ArogyaDemoScript.scenes.size == 12)
    check("s-ids", ArogyaDemoScript.scenes.map { it.id }.toSet().size == 12)
    check("s-titles-en", ArogyaDemoScript.scenes.all { it.title.isNotBlank() })
    check("s-titles-hi", ArogyaDemoScript.scenes.all { it.hi.isNotBlank() })
    val allScenes = ArogyaDemoScript.workflows.flatMap { it.scenes }
    check("s-nar-en", allScenes.all { it.narration.size in 3..6 && it.narration.all { s -> s.endsWith('.') } })
    check("s-nar-hi", allScenes.all { it.narrationHi.size in 3..6 && it.narrationHi.all { s -> s.endsWith('।') } })
    check("s-q-en", ArogyaDemoScript.QUESTION_EN == "What should be applied on the baby's cord stump?")
    check("s-q-hi", ArogyaDemoScript.QUESTION_HI == "बच्चे की नाल पर क्या लगाना चाहिए?")
    check("s-follow", ArogyaDemoScript.FOLLOWUP_EN.isNotBlank() && ArogyaDemoScript.FOLLOWUP_HI.isNotBlank())
    check("s-note-nums", listOf("2650", "38.4").all { ArogyaDemoScript.NOTE_EN.contains(it) && ArogyaDemoScript.NOTE_HI.contains(it) })
    check("s-assets", ArogyaDemoScript.VOICE_EN.isNotBlank() && ArogyaDemoScript.VOICE_HI.isNotBlank())
    val scriptAll = ArogyaDemoScript.scenes.flatMap { listOf(it.title, it.hi) + it.narration + it.narrationHi }.joinToString("\n") +
        "\n" + ArogyaDemoScript.NOTE_EN + "\n" + ArogyaDemoScript.NOTE_HI
    check("s-hero-one", !scriptAll.contains("ritu", ignoreCase = true) && !scriptAll.contains("रितु"))
    check("s-hero-neha", scriptAll.contains("Neha") && scriptAll.contains("नेहा"))
    // WAV decoder.
    val mono8k = ArogyaWav.decode(synthWav(8000, 1, false, 8000))
    check("w-mono-rate", mono8k != null && mono8k.size == 16000)
    check("w-mono-range", mono8k!!.all { it in -1f..1f } && mono8k.any { kotlin.math.abs(it) > 0.5f })
    val stereo = ArogyaWav.decode(synthWav(44100, 2, false, 44100))
    check("w-stereo", stereo != null && stereo.size == 16000)
    val f32 = ArogyaWav.decode(synthWav(16000, 1, true, 16000))
    check("w-f32", f32 != null && f32.size == 16000 && f32.any { kotlin.math.abs(it) > 0.5f })
    val fact = ArogyaWav.decode(synthWav(16000, 1, false, 16000, withFact = true))
    check("w-fact-chunk", fact != null && fact.size == 16000)
    check("w-bad-magic", ArogyaWav.decode("NOPE".toByteArray() + ByteArray(60)) == null)
    check("w-short", ArogyaWav.decode(ByteArray(20)) == null)
    check("w-truncated", ArogyaWav.decode(synthWav(8000, 1, false, 8000).copyOf(100)) == null)
    println("batch5: $n checks, $fails failures")
    if (fails > 0) kotlin.system.exitProcess(1)
}
