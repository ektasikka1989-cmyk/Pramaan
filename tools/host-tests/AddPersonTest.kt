import com.example.arfas.*

// Add-a-person: CSV headers/dates, duplicates, grounding, Aadhaar masking (pure logic).
var af = 0; var ap = 0
fun a(c: Boolean, m: String) { if (c) { ap++; println("PASS $m") } else { af++; println("FAIL $m") } }

fun main() {
    // 1. CSV parse: quotes, commas, CRLF.
    val rows = ArogyaPersonCsv.parse("a,b\r\n\"x,y\",z\r\n")
    a(rows == listOf(listOf("a", "b"), listOf("x,y", "z")), "quote-aware parse: $rows")
    a(ArogyaPersonCsv.parse("a,\"b\"\"c\"").first() == listOf("a", "b\"c"), "escaped quotes")
    // 2. Header matching EN + HI, case/space-insensitive.
    val m = ArogyaPersonCsv.matchHeaders(listOf("Full Name", "पति का नाम", "LMP", "गाँव", "Phone No", "???"))
    a(m[0] == "name" && m[1] == "relative" && m[2] == "lmp" && m[3] == "village" && m[4] == "phone" && m[5] == "", "synonym match: $m")
    val dup = ArogyaPersonCsv.matchHeaders(listOf("Name", "नाम"))
    a(dup[0] == "name" && dup[1] == "", "first header wins duplicates")
    // 3. Dates.
    a(ArogyaPersonCsv.parseDate("10/08/2026") == "2026-08-10", "dd/MM/yyyy")
    a(ArogyaPersonCsv.parseDate("1-7-2026") == "2026-07-01", "d-M-yyyy")
    a(ArogyaPersonCsv.parseDate("2026-11-02") == "2026-11-02", "ISO kept")
    a(ArogyaPersonCsv.parseDate("32/01/2026") == null && ArogyaPersonCsv.parseDate("soon") == null, "bad dates rejected")
    // 4. Row errors + beneficiary mapping.
    a(ArogyaPersonCsv.rowErrors(mapOf("dob" to "xx", "category" to "??")) == listOf("missing name", "bad dob: xx", "unknown category: ??"), "preview errors")
    val b = ArogyaPersonCsv.toBeneficiary(mapOf("category" to "गर्भवती महिला", "name" to " Meera ", "lmp" to "10/08/2026", "sex" to "F"))
    a(b.category == BenCategory.PREGNANT && b.name == "Meera" && b.lmp == "2026-08-10", "Hindi category + trim + date: $b")
    // 5. Template shape.
    val t = ArogyaPersonCsv.parse(ArogyaPersonCsv.template())
    a(t.size == 11 && t[0].size == 16 && t.drop(1).all { it[1].isNotBlank() }, "template header + 10 named rows")
    // 6. Duplicates.
    val old = Beneficiary(id = "1", name = "Meera Devi", relativeName = "Ramesh", village = "Rampur", phone = "9811100001")
    a(ArogyaPersonDup.find(Beneficiary(name = "meera  devi", relativeName = "Ramesh ", village = "rampur"), listOf(old)) == old, "name+relative+village match")
    a(ArogyaPersonDup.find(Beneficiary(name = "Other", phone = "+91 98111 00001"), listOf(old)) == old, "phone match ignores formatting")
    a(ArogyaPersonDup.find(Beneficiary(name = "Meera Devi", relativeName = "Other", village = "Rampur"), listOf(old)) == null, "different relative -> no match")
    a(ArogyaPersonDup.find(Beneficiary(name = ""), listOf(old)) == null, "blank name never matches")
    val merged = ArogyaPersonDup.merge(Beneficiary(name = "Meera", phone = ""), Beneficiary(name = "Meera Devi", phone = "98111", village = "Rampur"))
    a(merged.name == "Meera" && merged.phone == "98111" && merged.village == "Rampur", "merge fills blanks only: ${merged.name}/${merged.phone}/${merged.village}")
    // 7. Grounding keeps only source text.
    val ocr = "Name: Meera Devi\nVillage: Rampur\nPhone 9811100001\nLMP 10/08/2026"
    val g = ArogyaPersonExtract.ground(mapOf("name" to "Meera Devi", "village" to "Rampur", "phone" to "9811100001",
        "lmp" to "10/08/2026", "age" to "99", "rch" to "XYZ"), ocr)
    a(g.keys == setOf("name", "village", "phone", "lmp"), "invented age/RCH dropped: $g")
    // 8. Aadhaar masking.
    a(ArogyaPersonExtract.hasAadhaar("id 2345 6789 0123 ok"), "12-digit pattern detected")
    a(!ArogyaPersonExtract.hasAadhaar("phone 9811100001"), "10-digit phone not flagged")
    a(ArogyaPersonExtract.maskAadhaar("A 2345 6789 0123 B") == "A [Aadhaar removed] B", "Aadhaar masked")
    // 9. KEY: value parse.
    val p = ArogyaPersonExtract.parse("NAME: Meera Devi\nPHONE: 98111\nBOGUS: x")
    a(p == mapOf("name" to "Meera Devi", "phone" to "98111"), "extraction parse: $p")
    println("AddPersonTest: $ap passed, $af failed")
    if (af > 0) kotlin.system.exitProcess(1)
}
