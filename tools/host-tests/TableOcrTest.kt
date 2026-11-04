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
    check("no-header-no-people", ArogyaTableOcr.people(listOf(L("Paracetamol 500 mg", 10, 10), L("Batch AB12", 10, 50))).isEmpty())
    println("TableOcrTest: $n passed, $fails failed")
    if (fails > 0) kotlin.system.exitProcess(1)
}
