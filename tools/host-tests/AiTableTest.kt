import com.example.arfas.*

// AI table: engines per mode, unavailable states, PDF text (pure logic).
var af = 0; var ap = 0
fun a(c: Boolean, m: String) { if (c) { ap++; println("PASS $m") } else { af++; println("FAIL $m") } }

fun main() {
    val full = AiTableStatus(cloudFirst = true, online = true, llama = "Llama-3.2-3B-Q4", llamaReady = true,
        cloudText = "Muse Spark 1.3 (Meta)", cloudTextKey = true, metaKey = true, visionOk = true)
    // 1. Ten rows, one icon each, real engine names.
    val rows = ArogyaAiTable.rows(full, false)
    a(rows.size == 10, "ten feature rows")
    a(rows.all { it.icon.isNotBlank() && it.feature.isNotBlank() && it.does.isNotBlank() && it.safety.isNotBlank() }, "icon + texts everywhere")
    a(rows[1].phone == "Llama-3.2-3B-Q4", "phone box names the loaded file: '${rows[1].phone}'")
    a(rows[1].cloud == "Muse Spark 1.3 (Meta)", "cloud box names the configured endpoint: '${rows[1].cloud}'")
    a(rows[0].phone == "Whisper (on-device)" && rows[0].cloud == "Muse Voice Transcribe 1.0", "speech engines exact")
    // 2. Active column follows the mode.
    a(rows.filterIndexed { i, _ -> i != 9 && i != 4 }.all { it.active == 1 } && rows[9].active == 0 && rows[4].active == 0, "cloud first + ready -> cloud active except phone-only read-aloud + card photo")
    val local = ArogyaAiTable.rows(full.copy(cloudFirst = false), false)
    a(local.filterIndexed { i, _ -> i != 9 && i != 3 }.all { it.active == 0 } && local[9].active == 0, "local first -> phone active except read-aloud + cloud-only check-online")
    a(local[3].active == 1, "cloud-only row highlights cloud in either mode")
    // 3. Unavailable states, shown not hidden.
    val noKeys = ArogyaAiTable.rows(full.copy(cloudTextKey = false, metaKey = false), false)
    a(!noKeys[1].cloudOk && noKeys[1].cloud.contains("key not saved"), "cloud text without key: '${noKeys[1].cloud}'")
    a(!noKeys[0].cloudOk && noKeys[0].cloud.contains("Meta key not saved"), "voice without Meta key: '${noKeys[0].cloud}'")
    a(!noKeys[6].cloudOk && noKeys[6].active == 0 && noKeys[6].phoneOk, "SAM without key falls back to the phone counter")
    val noVision = ArogyaAiTable.rows(full.copy(metaKey = false, visionOk = false), false)
    a(!noVision[6].phoneOk && noVision[6].phone.contains("models not installed") && noVision[6].active == -1, "count with neither engine: '${noVision[6].phone}'")
    a(noKeys[1].active == 0, "cloud down -> phone highlighted as the active column")
    val off = ArogyaAiTable.rows(full.copy(online = false), false)
    a(!off[1].cloudOk && off[1].cloud.contains("(offline)"), "cloud text offline: '${off[1].cloud}'")
    a(off[0].phoneOk && off[1].phoneOk, "phone column stays available offline")
    val noLlama = ArogyaAiTable.rows(full.copy(llama = "", llamaReady = false, cloudFirst = false), false)
    a(!noLlama[1].phoneOk && noLlama[1].phone.contains("not loaded") && noLlama[1].active == -1, "phone model missing: '${noLlama[1].phone}'")
    val loading = ArogyaAiTable.rows(full.copy(llama = "Llama-3.2-3B-Q4", llamaReady = false, cloudFirst = false), false)
    a(!loading[1].phoneOk && loading[1].phone.contains("loading"), "phone model loading: '${loading[1].phone}'")
    // 4. Glimmer is never named without a Together key.
    val glimmerHidden = ArogyaAiTable.rows(full.copy(cloudText = "", cloudTextKey = false), false)
    a(glimmerHidden.none { it.cloud.contains("Glimmer") } && glimmerHidden[1].cloud.contains("No cloud text model"), "Glimmer hidden: '${glimmerHidden[1].cloud}'")
    val glimmerShown = ArogyaAiTable.rows(full.copy(cloudText = "Muse Glimmer 30B (Together AI)"), false)
    a(glimmerShown[1].cloud == "Muse Glimmer 30B (Together AI)", "Glimmer shown with Together key")
    // 5. Count row has both engines; read-aloud is phone-only; check-online is cloud-only.
    a(rows[6].phone == "MobileSAM + DINOv2 (estimate)" && rows[6].cloud == "SAM 3.1", "count row engines: '${rows[6].phone}' / '${rows[6].cloud}'")
    a(rows[9].cloud == "—" && rows[9].active == 0, "read-aloud is phone-only, phone active")
    a(rows[4].cloud == "—" && !rows[4].cloudOk && rows[4].active == 0 && rows[4].safety.contains("never go online"), "card photo is phone-only, names never online")
    a(rows[3].phone == "—" && !rows[3].phoneOk && rows[3].cloud == "Muse Spark 1.3 (Meta) + official GoI websites (allowlist)" && rows[3].cloudOk,
        "check-online row: '${rows[3].phone}' / '${rows[3].cloud}'")
    a(!off[3].cloudOk && off[3].cloud.contains("(offline)") && off[3].active == -1, "check-online offline: '${off[3].cloud}'")
    a(!noKeys[3].cloudOk && noKeys[3].cloud.contains("key not saved"), "check-online without key: '${noKeys[3].cloud}'")
    // 6. Hindi complete.
    val hi = ArogyaAiTable.rows(full, true)
    a(hi.all { it.feature.isNotBlank() && it.does.isNotBlank() && it.safety.isNotBlank() }, "Hindi texts everywhere")
    a(hi[0].phone == "Whisper (on-device)", "product names stay English in Hindi mode")
    // 7. PDF text shape.
    val pdf = ArogyaAiTable.pdfText(rows, full, 68, "Guide v2", false)
    a(pdf.lines().any { it.contains("Feature") && it.contains("Phone (offline)") && it.contains("Meta cloud (online)") && it.contains("Safety check") }, "PDF header has all 4 columns")
    a(rows.all { pdf.contains(it.feature) }, "PDF names all 10 features")
    a(pdf.contains("▶") && pdf.contains("Guide v2") && pdf.contains("68 official guides"), "PDF marks active + guide count")
    a(pdf.contains("Fixed government rules, not AI") && pdf.contains("Privacy:") && pdf.contains("Built with Muse Code."), "PDF rules + privacy + footer")
    val pdfOff = ArogyaAiTable.pdfText(ArogyaAiTable.rows(full.copy(online = false), false), full.copy(online = false), 68, "Guide v2", false)
    a(pdfOff.contains("[unavailable]") && pdfOff.contains("offline"), "PDF shows unavailable + offline state")
    val pdfHi = ArogyaAiTable.pdfText(hi, full, 68, "Guide v2", true)
    a(pdfHi.contains("सुरक्षा जाँच") && pdfHi.contains("Muse Code से बनाया गया।"), "Hindi PDF complete")
    println("AiTableTest: $ap passed, $af failed")
    if (af > 0) kotlin.system.exitProcess(1)
}
