import com.example.arfas.*
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.LocalDate

var failed = 0; var passed = 0
fun check(c: Boolean, m: String) { if (c) { passed++; println("PASS $m") } else { failed++; println("FAIL $m") } }

// SYNTHETIC data only.
val birth = LocalDate.now().minusDays(6)
fun newborn(notes: String) = ArogyaVisit(name = "Demo Baby A (synthetic)", village = "Demo village", kind = ArogyaKind.NEWBORN,
    date = LocalDate.now().toString(), birthDate = birth.toString(), notes = notes, demo = true)
val NOTES = "Baby feeding well, breastfed 8 times. Temperature 38.6 C measured with thermometer. Weight 2.6 kg on the HBNC scale. Cord is dry. Mother says baby sleeps a lot. I advised skin to skin care."

fun main() {
    // 1. Model output parsing with grounding
    val raw = """Here is the JSON:
```json
{"fields":[
 {"key":"temperature_c","value":"38.6","evidence":"Temperature 38.6 C measured with thermometer"},
 {"key":"weight_kg","value":"2.6","evidence":"Weight 2.6 kg on the HBNC scale"},
 {"key":"feeding","value":"Feeding well, breastfed 8 times","evidence":"Baby feeding well, breastfed 8 times"},
 {"key":"cord","value":"Dry","evidence":"Cord is dry"},
 {"key":"breathing","value":"Fast breathing","evidence":"baby breathing fast"},
 {"key":"weight_kg","value":"3.1","evidence":"Weight 2.6 kg on the HBNC scale"},
 {"key":"action_taken","value":"Advised skin to skin care","evidence":"I advised skin to skin care"}
]}
```"""
    val f = ArogyaFinishVisit.parseExtraction(raw, NOTES)
    f.forEach { println("   ${it.key} = ${it.value}  <- ${it.evidence}") }
    check(f.any { it.key == "temperature_c" && it.value == "38.6" }, "grounded temperature kept")
    check(f.any { it.key == "weight_kg" && it.value == "2.6" }, "grounded weight kept")
    check(f.none { it.key == "breathing" }, "field whose evidence is not in the notes is dropped")
    check(f.none { it.value == "3.1" }, "number not present in its evidence is dropped")
    check(f.any { it.key == "action_taken" }, "action taken kept")
    check(ArogyaFinishVisit.parseExtraction("not json at all", NOTES).isEmpty(), "garbage output gives no fields")
    check(ArogyaFinishVisit.extractionPrompt(NOTES).contains(NOTES) && ArogyaFinishVisit.extractionPrompt(NOTES).contains("evidence"), "prompt carries notes and asks for evidence")

    // 2. Unit conversion and plausibility
    val fNotes = "Temp 101.3 F at 10 am. Weight 2450 g."
    val conv = ArogyaFinishVisit.parseExtraction("""{"fields":[{"key":"temperature_c","value":"101.3 F","evidence":"Temp 101.3 F at 10 am"},{"key":"weight_kg","value":"2450 g","evidence":"Weight 2450 g"}]}""", fNotes)
    check(conv.any { it.key == "temperature_c" && it.value == "38.5" }, "101.3 F converted to 38.5 C")
    check(conv.any { it.key == "weight_kg" && it.value == "2.45" }, "2450 g converted to 2.45 kg")
    val bad = ArogyaFinishVisit.parseExtraction("""{"fields":[{"key":"temperature_c","value":"3.86","evidence":"Temperature 38.6 C"}]}""", "Temperature 38.6 C")
    check(bad.none { it.key == "temperature_c" }, "implausible temperature dropped")

    // 3. Offline rule fallback (no model)
    val rf = ArogyaFinishVisit.ruleFallback(NOTES)
    check(rf.any { it.key == "temperature_c" && it.value == "38.6" && it.source == "rule" }, "rule fallback reads temperature")
    check(rf.any { it.key == "weight_kg" && it.value == "2.6" }, "rule fallback reads weight")

    // 4. Plan: urgent newborn fever -> referral first, next-day follow-up
    val v = newborn(NOTES)
    val p = ArogyaFinishVisit.plan(v, f)
    println("   handover:\n" + p.handoverText.prependIndent("     "))
    check(p.urgent, "newborn 38.6 C is urgent by the danger-sign rules")
    check(p.handoverText.startsWith("URGENT"), "handover starts with URGENT")
    check(p.handoverText.contains("38.6") && p.handoverText.contains("Demo Baby A"), "handover carries verified facts")
    check(p.followUpDate == LocalDate.now().plusDays(1).toString(), "urgent follow-up is next day")
    check(p.recordText.contains("Temperature: 38.6 °C"), "record lists verified temperature")

    // 5. Plan: routine visit -> next HBNC reference date
    val calm = "Baby feeding well. Temperature 36.8 C. Weight 2.9 kg. Cord dry."
    val v2 = newborn(calm)
    val p2 = ArogyaFinishVisit.plan(v2, ArogyaFinishVisit.ruleFallback(calm))
    val expected = ArogyaWorkflow.referenceDates(v2).map { it.second }.firstOrNull { it > LocalDate.parse(v2.date) }?.toString()
    check(!p2.urgent, "routine visit not urgent")
    check(expected != null && p2.followUpDate == expected, "routine follow-up = next HBNC reference date ($expected)")
    check(p2.missing.any { it.contains("breathing", true) } || p2.missing.isNotEmpty(), "plan lists what was not recorded")

    // 6. Apply only what the ASHA approved
    val chosen = f.filter { it.key != "cord" }
    val saved = ArogyaFinishVisit.applyApproved(v, p, chosen, p.handoverText, p.followUpDate, p.followUpReason)
    check(saved.referral == ArogyaReferral.NEEDED, "urgent plan marks referral NEEDED")
    check(saved.nextDate == p.followUpDate && saved.planConfirmed, "approved follow-up date saved and confirmed")
    check(saved.notes.contains("VERIFIED BY ASHA") && saved.notes.contains("38.6"), "verified record appended to notes")
    check(!saved.notes.substringAfter("VERIFIED BY ASHA").contains("Cord"), "unticked field not written")
    check(saved.referralNote.contains("URGENT"), "handover stored in referral note")
    check(saved.notes.startsWith(NOTES), "original notes kept unchanged")
    val contacted = v.copy(referral = ArogyaReferral.CONTACTED)
    check(ArogyaFinishVisit.applyApproved(contacted, p, chosen, p.handoverText, p.followUpDate, "").referral == ArogyaReferral.CONTACTED, "existing referral progress not downgraded")

    // 7. OpenAI-compatible client: request shape and response parsing against a local server
    var seenPath = ""; var seenAuth = ""; var seenBody = ""
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { ex ->
        seenPath = ex.requestURI.path; seenAuth = ex.requestHeaders.getFirst("Authorization") ?: ""; seenBody = ex.requestBody.readBytes().decodeToString()
        val out = if (seenAuth == "Bearer bad") "{\"error\":\"no\"}" to 401 else "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"fields\\\":[]}\"}}]}" to 200
        ex.sendResponseHeaders(out.second, out.first.length.toLong()); ex.responseBody.use { it.write(out.first.toByteArray()) }
    }
    server.start()
    val ep = ArogyaModelEndpoint("test", "Test", "http://127.0.0.1:${server.address.port}/v1", "muse-spark-1.3", true)
    val client = OpenAiCompatibleClient(ep, { "k-123" })
    val content = runCatching { client.complete("sys", "user text", 300) }.getOrElse { "ERR ${it.message}" }
    check(seenPath == "/v1/chat/completions", "client posts to /v1/chat/completions (got '$seenPath')")
    check(seenAuth == "Bearer k-123", "client sends bearer key")
    check(seenBody.contains("\"model\":\"muse-spark-1.3\"") && seenBody.contains("user text"), "client sends model id and prompt")
    check(content == "{\"fields\":[]}", "client returns message content")
    val err = runCatching { OpenAiCompatibleClient(ep, { "bad" }).complete("s", "u", 10) }.exceptionOrNull()
    check(err is OpenAiCompatibleClient.HttpError && err.code == 401, "HTTP error surfaces with status code")
    server.stop(0)

    // 8. Endpoint presets: only verified ids; Meta Model API flagged as Meta
    val meta = ArogyaModelEndpoints.all.firstOrNull { it.id == "meta" }
    check(meta?.baseUrl == "https://api.meta.ai/v1" && meta.model == "muse-spark-1.3" && meta.metaModel, "Meta Model API preset matches verified base URL and model id")
    check(ArogyaModelEndpoints.all.any { it.model.contains("Glimmer", true) || it.model.contains("glimmer") }, "Muse Glimmer partner preset present")

    println(if (failed == 0) "ALL $passed PASS" else "$failed FAILED, $passed passed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
