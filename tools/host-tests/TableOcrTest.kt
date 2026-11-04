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
    // ── Phone test 4 Nov: the slip table had NO heading row and the names were written on two lines ──
    val noHead = listOf(
        L("PREGNANCY REGISTRATION — VILLAGE RAMPUR", 90, 70, 380),
        L("Sub-centre: Rampur SC", 65, 108, 150), L("ASHA: Sunita Devi", 235, 108, 140),
        L("Sarita", 65, 150, 45), L("Ramesh", 120, 150, 50),
        L("23", 172, 162, 16), L("9000000301", 190, 162, 85), L("H-051", 280, 162, 40), L("Rampur", 325, 162, 50), L("Pregnant", 380, 162, 60), L("26/08/2026", 445, 162, 70),
        L("Yadav", 70, 174, 40), L("Yadav", 125, 174, 40),
        L("Aadhaar No.: 0000 1111 2222", 65, 205, 220),
        L("Family consent taken: Yes", 65, 245, 170), L("Signature: ______", 235, 245, 150))
    for (seed in 1..5) {
        val p = ArogyaTableOcr.readPerson(noHead.shuffled(java.util.Random(seed.toLong())))
        check("no-heading-slip-$seed", p["name"] == "Sarita Yadav" && p["relative"] == "Ramesh Yadav" && p["age"] == "23" && p["phone"] == "9000000301" &&
            p["household"] == "H-051" && p["village"] == "Rampur" && p["category"] == "Pregnant" && p["lmp"] == "26/08/2026" && p["aadhaar"] == "0000 1111 2222", p)
    }
    val nb = ArogyaPersonCsv.toBeneficiary(ArogyaTableOcr.readPerson(noHead))
    check("no-heading-beneficiary", nb.category == BenCategory.PREGNANT && nb.lmp == "2026-08-26" && nb.aadhaar.filter(Char::isDigit) == "000011112222", nb)
    check("no-heading-all-core", ArogyaTableOcr.CORE.all { !ArogyaTableOcr.readPerson(noHead)[it].isNullOrBlank() })
    // Same slip WITH headings, names on two lines: still ONE person, not "Sarita" + "Yadav".
    val withHead = noHead.filter { !it.text.startsWith("Aadhaar") } + listOf(L("Name", 65, 128, 40), L("Husband", 120, 128, 50), L("Age", 172, 128, 16),
        L("Mobile", 190, 128, 60), L("House No.", 280, 128, 40), L("Village", 325, 128, 50), L("Category", 380, 128, 60), L("LMP", 445, 128, 40))
    val wp = ArogyaTableOcr.people(withHead)
    check("two-line-cells-one-person", wp.size == 1 && wp[0]["name"] == "Sarita Yadav" && wp[0]["relative"] == "Ramesh Yadav" && wp[0]["phone"] == "9000000301", wp)
    check("plain-text-not-a-row", ArogyaTableOcr.headerlessRow(listOf(L("Gave 30 IFA tablets", 10, 10, 200), L("Next visit soon", 10, 50, 200))) == null)

    // ── Muse Spark transcript (online "read like a person") → the phone's own readers ──
    val vis = ArogyaPhotoText.clean("```\nName: Sarita Yadav\nHusband: Ramesh Yadav\nAge: 23\nMobile: 9000000301\nHouse No.: H-051\nVillage: Rampur\nCategory: Pregnant\nLMP: 26/08/2026\nRCH ID: [unreadable]\nAadhaar: 0000 1111 2222\n```")!!
    val vp = ArogyaPhotoText.people(vis)
    check("vision-one-person", vp.size == 1 && vp[0]["name"] == "Sarita Yadav" && vp[0]["relative"] == "Ramesh Yadav" && vp[0]["phone"] == "9000000301" && vp[0]["lmp"] == "26/08/2026", vp)
    check("vision-unreadable-dropped", vp.firstOrNull()?.get("rch") == null && !vis.contains("unreadable"))
    check("vision-never-keeps-aadhaar", !vis.contains("0000 1111 2222") && vp.firstOrNull()?.get("aadhaar").isNullOrBlank())
    val many = ArogyaPhotoText.people("Name: Pooja Kumari\nHusband/Father: Sanjay Kumar\nAge: 22\n\nName: Aarav Kumar\nMother: Pooja Kumari\nAge: 2\nCategory: Child\n\nName: Meena Singh\nAge: 31")
    check("vision-register-three-people", many.size == 3 && many[1]["relative"] == "Pooja Kumari" && many[1]["category"] == "Child", many)
    check("vision-no-text", ArogyaPhotoText.clean("NO_TEXT") == null && ArogyaPhotoText.clean(null) == null)
    check("vision-card-rows", ArogyaPhotoText.cardText("Birth | BCG | 31/10/2026\nBirth | OPV-0 | 31/10/2026") == "Birth  BCG  31/10/2026\nBirth  OPV-0  31/10/2026")
    // Aadhaar is blacked out before the photo leaves the phone: the number cell, and a label cell holding it.
    val hid = ArogyaPhotoText.hiddenBoxes(noHead + listOf(L("Aadhaar", 65, 300, 60), L("0000 1111", 140, 300, 80), L("2222", 230, 300, 40)))
    check("aadhaar-boxes-hidden", hid.any { it.text.startsWith("Aadhaar No.") } && hid.any { it.text == "0000 1111" } && hid.any { it.text == "2222" } &&
        hid.none { it.text == "9000000301" || it.text == "Sarita" }, hid.map { it.text })

    // ── "Which model answered?" tag: real engine labels the app records ──
    val U = ArogyaAiUsed
    check("used-phone-then-spark", U.steps("ML Kit text · On-phone rules + On-phone model (offline): Llama-3.2-3B-Instruct-Q4_K_M · " + "Muse Spark 1.3 (Meta, online): read the photo").map { it.model } ==
        listOf("ML Kit text recognition", "Fixed rules (not AI)", "Llama 3.2 3B Instruct", "Muse Spark 1.3"), U.steps("ML Kit text · On-phone rules + On-phone model (offline): Llama · Muse Spark 1.3 (Meta, online): read the photo"))
    check("used-short-cloud-wins", U.short("ML Kit text · On-phone rules (offline): regex · Muse Spark 1.3 (Meta, online): read the photo") == "✨ Muse Spark 1.3 ☁", U.short("ML Kit text · Muse Spark"))
    check("used-short-phone", U.short("On-phone model (offline): Llama-3.2-3B-Instruct-Q4_K_M") == "✨ Llama 3.2 3B Instruct 📴")
    check("used-rules-not-ai", U.short("On-phone rules (offline): every column read") == "⚙ Fixed rules (not AI) 📴" && !U.steps("On-phone rules (offline): patterns")[0].ai)
    check("used-sop-labels", U.short("Muse Spark 1.3 (Meta) • online") == "✨ Muse Spark 1.3 ☁" && U.short("Pramaan • offline (Llama-3.2-3B-Instruct-Q4_K_M)") == "✨ Llama 3.2 3B Instruct 📴")
    check("used-meta-models", U.steps("SAM 3.1 (Meta, online)")[0].model == "SAM 3.1" && U.steps("Muse Glimmer 30B — Together AI")[0].model == "Muse Glimmer 30B" &&
        U.steps("Whisper base (offline)")[0].model.startsWith("Whisper") && U.steps("same day rules")[0].model != "SAM 3.1")

    println("TableOcrTest: $n passed, $fails failed")
    if (fails > 0) kotlin.system.exitProcess(1)
}
