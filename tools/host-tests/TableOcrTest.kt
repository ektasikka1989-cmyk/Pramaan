import com.example.arfas.*

private var n = 0; private var fails = 0
private fun check(name: String, ok: Boolean, info: Any? = null) { if (ok) { n++; println("PASS $name") } else { fails++; println("FAIL $name ${info ?: ""}") } }

fun main() {
    fun L(t: String, x: Int, y: Int, w: Int = 120, h: Int = 24) = ArogyaTableOcr.Line(t, x, y, x + w, y + h)
    // ML Kit order is column by column; positions tell the real rows. Row 2 has no mobile.
    val lines = listOf(
        L("VILLAGE HEALTH REGISTER - RAMPUR", 40, 10, 500),
        L("S.No", 20, 60, 50), L("Name", 90, 60), L("Husband/Father", 240, 61, 160), L("Age", 420, 59, 50), L("Category", 500, 60), L("House No.", 640, 60), L("Mobile", 780, 62),
        L("1", 20, 100, 20), L("2", 20, 140, 20), L("3", 20, 181, 20),
        L("Kavita Yadav", 90, 101), L("Seema Devi", 90, 139), L("Rohan", 90, 180),
        L("Ramesh Yadav", 240, 100), L("Suresh Kumar", 240, 141), L("Mamta Devi", 240, 182),
        L("23", 420, 99, 40), L("27", 420, 140, 40), L("1", 420, 181, 20),
        L("Pregnant", 500, 100), L("Eligible couple", 500, 141, 150), L("Child", 500, 180),
        L("H-051", 640, 101), L("H-052", 640, 140), L("H-018", 640, 181),
        L("9000000101", 780, 100), L("9000000118", 780, 181))
    val rows = ArogyaTableOcr.rows(lines)
    check("rows-grouped", rows.size == 5, rows.map { r -> r.map { it.text } })
    val people = ArogyaTableOcr.people(lines.shuffled(java.util.Random(7)))
    check("three-people", people.size == 3, people)
    val k = people.firstOrNull { it["name"] == "Kavita Yadav" }
    check("kavita-fields", k != null && k["relative"] == "Ramesh Yadav" && k["age"] == "23" && k["household"] == "H-051" && k["phone"] == "9000000101" && k["category"] == "Pregnant", k)
    val s = people.firstOrNull { it["name"] == "Seema Devi" }
    check("empty-cell-stays-empty", s != null && s["phone"] == null && s["category"] == "Eligible couple", s)
    val b = ArogyaPersonCsv.toBeneficiary(k ?: emptyMap())
    check("to-beneficiary", b.category == BenCategory.PREGNANT && b.relativeName == "Ramesh Yadav" && b.phone == "9000000101")
    check("header-keys", ArogyaTableOcr.headerKey("Husband/Father") == "relative" && ArogyaTableOcr.headerKey("House No.") == "household" && ArogyaTableOcr.headerKey("S.No") == "")
    check("hindi-header", ArogyaTableOcr.headerKey("नाम") == "name" && ArogyaTableOcr.headerKey("उम्र") == "age")
    val withFooter = lines + listOf(L("Family consent taken: Yes", 90, 240, 300), L("Signature: ______", 420, 241, 200))
    check("footer-not-a-person", ArogyaTableOcr.people(withFooter).size == 3)
    // What happened on the phone: ML Kit joined each printed row into ONE line. Rebuilt from its words → cells → people.
    fun W(t: String, x: Int, y: Int) = ArogyaTableOcr.Line(t, x, y, x + t.length * 16, y + 30)
    fun rowWords(y: Int, vararg cells: Pair<String, Int>) = cells.flatMap { (txt, x0) -> var x = x0; txt.split(" ").map { w -> W(w, x, y).also { x += w.length * 16 + 10 } } }
    val headerWords = rowWords(340, "Name" to 160, "Husband" to 455, "Age" to 738, "Mobile" to 826, "House No." to 1070, "Village" to 1256, "Category" to 1431, "LMP" to 1626)
    val dataWords = rowWords(445, "Sarita Yadav" to 163, "Ramesh Yadav" to 456, "23" to 740, "9000000301" to 827, "H-051" to 1071, "Rampur" to 1257, "Pregnant" to 1432, "26/08/2026" to 1627)
    val headerCells = ArogyaTableOcr.cells(headerWords)
    check("merged-line-split-to-cells", headerCells.map { it.text } == listOf("Name", "Husband", "Age", "Mobile", "House No.", "Village", "Category", "LMP"), headerCells.map { it.text })
    val fromMerged = ArogyaTableOcr.people(headerCells + ArogyaTableOcr.cells(dataWords))
    val sm = fromMerged.firstOrNull().orEmpty()
    check("merged-line-all-fields", fromMerged.size == 1 && sm["name"] == "Sarita Yadav" && sm["relative"] == "Ramesh Yadav" && sm["age"] == "23" && sm["phone"] == "9000000301" &&
        sm["household"] == "H-051" && sm["village"] == "Rampur" && sm["category"] == "Pregnant" && sm["lmp"] == "26/08/2026", sm)
    check("names-stay-whole", ArogyaTableOcr.cells(rowWords(0, "Sarita Yadav" to 0)).map { it.text } == listOf("Sarita Yadav"))
    // Any wording of a heading (no fixed list of exact words).
    val hk = mapOf("Contact details" to "phone", "Phone No." to "phone", "Mobile Number" to "phone", "Husband's Name" to "relative", "Name of beneficiary" to "name",
        "Aadhaar No." to "aadhaar", "Aadhar Number" to "aadhaar", "Village name" to "village", "Family consent taken" to "", "Remarks" to "", "Blood Group" to "")
    hk.forEach { (h, k) -> check("heading '$h' -> '$k'", ArogyaTableOcr.headerKey(h) == k, ArogyaTableOcr.headerKey(h)) }
    // A column the app has no field for is kept in notes, never dropped; serial numbers are not notes.
    val withExtra = listOf(L("S.No", 20, 60, 50), L("Name of beneficiary", 90, 60, 200), L("Contact details", 320, 60, 160), L("Blood Group", 520, 60),
        L("1", 20, 100, 20), L("Kavita Devi", 90, 100, 160), L("9000000555", 320, 100, 160), L("B+", 520, 100, 40))
    val pe = ArogyaTableOcr.people(withExtra).firstOrNull().orEmpty()
    check("unknown-column-to-notes", pe["name"] == "Kavita Devi" && pe["phone"] == "9000000555" && pe["notes"] == "Blood Group: B+", pe)
    // A slip that is not a table: "Label: value" lines, any wording; unknown labels kept in notes.
    val kvSlip = listOf(L("Name: Sarita Yadav", 40, 40, 300), L("Husband's name: Ramesh Yadav", 40, 90, 400), L("Contact: 9000000301", 40, 140, 300),
        L("Village: Rampur", 40, 190, 250), L("Blood group: B+", 40, 240, 250), L("Aadhaar No.: 0000 1111 2222", 40, 290, 350))
    val kp = ArogyaTableOcr.readPerson(kvSlip)
    check("label-value-slip", kp["name"] == "Sarita Yadav" && kp["relative"] == "Ramesh Yadav" && kp["phone"] == "9000000301" && kp["village"] == "Rampur" &&
        kp["notes"] == "Blood group: B+" && kp["aadhaar"] == "0000 1111 2222", kp)
    val kb = ArogyaPersonCsv.toBeneficiary(kp)
    check("aadhaar-kept-and-masked", kb.aadhaar == "000011112222" && ArogyaPersonExtract.masked(kb.aadhaar) == "XXXX XXXX 2222" && kb.notes == "Blood group: B+", kb)
    // Aadhaar survives saving and reloading the encrypted registry.
    val reg = ArogyaRegistryCodec.decode(ArogyaRegistryCodec.encode(RegistryData(listOf(kb.copy(id = "t1")), emptyList(), emptyList(), emptyList(), emptyList(), ArogyaRegistryLogic.DRUG_KIT_DEFAULTS)))
    check("aadhaar-round-trip", reg.beneficiaries.first().aadhaar == "000011112222" && reg.beneficiaries.first().notes == "Blood group: B+")
    // The slip's footer never becomes a field.
    check("footer-not-household", ArogyaTableOcr.keyValues(listOf(L("Family consent taken: Yes", 40, 40, 300))).first.isEmpty())
    // CSV import: headings in other wordings are matched too; the rest are kept as notes by the import screen.
    val csvMap = ArogyaPersonCsv.matchHeaders(listOf("Beneficiary Name", "Husband's name", "Contact details", "Aadhaar No.", "Blood Group"))
    check("csv-flexible-headings", csvMap[0] == "name" && csvMap[1] == "relative" && csvMap[2] == "phone" && csvMap[3] == "aadhaar" && csvMap[4] == "", csvMap)
    // Voice stock: a spoken item that is not in the kit is reported, never dropped silently.
    val kit = ArogyaRegistryLogic.DRUG_KIT
    val vs = ArogyaVoiceStock.ground(ArogyaVoiceStock.parseRules("received 100 ORS and 5 thermometers", kit), "received 100 ORS and 5 thermometers", kit)
    check("voice-stock-unmatched-reported", vs.any { it.item == "ORS" && it.qty == 100 } && ArogyaVoiceStock.unmatched("received 100 ORS and 5 thermometers", kit, vs).any { it.contains("thermometer") },
        ArogyaVoiceStock.unmatched("received 100 ORS and 5 thermometers", kit, vs))
    check("voice-stock-all-understood", ArogyaVoiceStock.unmatched("received 100 ORS", kit, vs).isEmpty())
    // Voice note: rows the note talks about but that stay empty are listed for the ASHA to check.
    val heard = ArogyaOfficialForms.mentionedFields(ArogyaOfficialForms.MCP_ANC, 0, ArogyaDemoScript.NOTE_W1_EN).map { it.id }
    check("voice-note-mentioned-rows", listOf("weight", "bp", "hb", "ifa", "calcium").all { it in heard }, heard)
    check("no-header-no-people", ArogyaTableOcr.people(listOf(L("Paracetamol 500 mg", 10, 10), L("Batch AB12", 10, 50))).isEmpty())
    println("TableOcrTest: $n passed, $fails failed")
    if (fails > 0) kotlin.system.exitProcess(1)
}
