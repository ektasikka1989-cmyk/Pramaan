import com.example.arfas.*
var fails = 0; var n = 0
fun check(name: String, ok: Boolean) { n++; if (!ok) { fails++; println("FAIL $name") } }
fun main() {
    // Part B6: cut-output detection.
    check("json-whole", ArogyaExtractSplit.jsonComplete("""{"a":1,"b":[{"c":"x"}]}"""))
    check("json-cut", !ArogyaExtractSplit.jsonComplete("""{"a":1,"b":[{"c":"x"},"""))
    check("json-none", !ArogyaExtractSplit.jsonComplete("no braces here"))
    check("json-fence", ArogyaExtractSplit.jsonComplete("```json\n{\"a\":1}\n```"))
    check("cut-yes", ArogyaExtractSplit.lastLineCut("NAME: X\nVILLA"))
    check("cut-no", !ArogyaExtractSplit.lastLineCut("NAME: X\nVILLAGE: Y\n"))
    // Halves cover every line.
    val long = (1..40).joinToString("\n") { "line number $it with words" }
    val (h1, h2) = ArogyaExtractSplit.halves(long)
    check("halves", h1.lines().size == 20 && h2.lines().size == 20 && (h1 + "\n" + h2) == long)
    check("halves-short", ArogyaExtractSplit.halves("tiny").second.isBlank())
    // Register page with 8 people: 8 blocks, 8 grounded names.
    val names = listOf("Meera Devi", "Geeta Devi", "Rekha Devi", "Usha Rani", "Pooja Kumari", "Anita Devi", "Sunita Devi", "Kamla Devi")
    val page = names.mapIndexed { i, nm ->
        "Name: $nm\nHead: Head $i\nAge: 2$i\nVillage: Rampur\nPhone: 981110000$i"
    }.joinToString("\n\n")
    val blocks = ArogyaExtractSplit.splitBlocks(page)
    check("blocks-8", blocks.size == 8)
    var kept = 0
    blocks.forEachIndexed { i, b ->
        val out = "NAME: ${names[i]}\nVILLAGE: Rampur\nPHONE: 981110000$i\nAGE: 2$i"
        val m = ArogyaPersonExtract.ground(ArogyaPersonExtract.parse(out), b)
        if (m["name"] == names[i] && m["phone"] == "981110000$i") kept++
    }
    check("blocks-names-8", kept == 8)
    // Full MCP immunization page (p.36): every antigen row extracted.
    val antigens = listOf("OPV-0", "Hep B", "BCG", "OPV-1", "Penta-1", "Rota-1", "PCV-1", "IPV-1",
        "OPV-2", "Penta-2", "Rota-2", "OPV-3", "Penta-3", "Rota-3", "PCV-2", "IPV-2",
        "MR-1", "JE-1", "Vitamin A-1", "PCV booster", "DPT Booster-1", "Vitamin A-2",
        "MR-2", "JE-2", "OPV Booster", "DPT Booster-2", "Td", "Td")
    val dates = antigens.mapIndexed { i, _ -> "${(i % 28) + 1}/0${(i % 9) + 1}/2026" }
    val ocr = antigens.mapIndexed { i, a -> "$a ${dates[i]}" }.joinToString("\n")
    val json = "{\"vaccines\":[" + antigens.mapIndexed { i, a -> "{\"name\":\"$a\",\"date\":\"${dates[i]}\"}" }.joinToString(",") + "]}"
    val vacc = ArogyaAiExtras.parseCard(json, ocr).filter { it.key == "vaccine" }
    check("imm-28", vacc.size == 28)
    // Hindi voice receipt with 4 items: 4 grounded lines.
    val catalog = listOf("ors", "zinc tablets", "ifa tablets (adult)", "calcium")
    val heard = "100 ओआरएस मिला, 60 जिंक मिला, 45 आईएफए मिला और 20 कैल्शियम मिला"
    val lines = ArogyaVoiceStock.ground(ArogyaVoiceStock.parseRules(heard, catalog), heard, catalog)
    check("voice-4", lines.size == 4)
    check("voice-items", lines.map { it.item }.toSet() == catalog.toSet() && lines.sumOf { it.qty } == 225)
    // Merges.
    check("merge-card", ArogyaExtractSplit.mergeCard(
        listOf(ArogyaCardField("vaccine", "Vaccine", "BCG", "e")),
        listOf(ArogyaCardField("vaccine", "Vaccine", "BCG", "e"), ArogyaCardField("vaccine", "Vaccine", "OPV-0", "e"))).size == 2)
    check("merge-person", ArogyaExtractSplit.mergePerson(mapOf("name" to "A"), mapOf("name" to "B", "age" to "3")) == mapOf("name" to "A", "age" to "3"))
    check("merge-voice", ArogyaExtractSplit.mergeVoice(
        listOf(VoiceStockLine("ors", 100, "receive", "e")),
        listOf(VoiceStockLine("ors", 100, "receive", "e"), VoiceStockLine("ors", 60, "receive", "e"))).size == 2)
    check("merge-med", ArogyaExtractSplit.mergeMed(MedScan("CROCIN", "", "", 0), MedScan("", "B12", "08/27", 10)) == MedScan("CROCIN", "B12", "08/27", 10))
    // Extraction prompts carry the full input (no .take truncation).
    val big = "x".repeat(5000)
    check("prompt-full", ArogyaAiExtras.cardPrompt(big).contains(big) && ArogyaPersonExtract.prompt(big).contains(big) && ArogyaMedScan.prompt(big).contains(big))
    println("extractsplit: $n checks, $fails failures")
    if (fails > 0) kotlin.system.exitProcess(1)
}
