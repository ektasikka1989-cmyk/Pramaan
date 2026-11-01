import com.example.arfas.*
import java.time.LocalDate

// Owner review fixes: save idempotency (keyed stock lines, first-empty column,
// Day-1 due dates), stream-abort draft handling, and question-type ordering.
var fails = 0; var n = 0
fun check(name: String, ok: Boolean) { n++; if (!ok) { fails++; println("FAIL $name") } }
fun bal(r: RegistryData) = ArogyaRegistryLogic.stockBalance(r.stock)["IFA tablets (adult)"] ?: 0

fun main() {
    val today = LocalDate.of(2026, 11, 1)
    val anc = ArogyaOfficialForms.MCP_ANC
    val preg = Beneficiary(id = "p1", name = "Sunita", category = BenCategory.PREGNANT, lmp = "2026-09-05")

    // Re-saving the same column changes stock by -30 exactly once (reviewer's 200 baseline).
    var r = RegistryData(beneficiaries = listOf(preg),
        stock = listOf(StockTxn(id = "s0", date = "2026-10-01", item = "IFA tablets (adult)", qty = 200, note = "Received")))
    r = ArogyaOfficialForms.apply(r, preg, anc, 0, mapOf("weight" to "52", "ifa" to "30"), emptyMap(), emptySet(), today)
    check("save-first-minus30", bal(r) == 170)
    r = ArogyaOfficialForms.apply(r, preg, anc, 0, mapOf("weight" to "52", "ifa" to "30"), emptyMap(), emptySet(), today)
    check("save-resave-once", bal(r) == 170 && r.stock.count { it.item == "IFA tablets (adult)" } == 2)
    // A new column adds its own -30; the MCP IFA total rises by 30 per column.
    r = ArogyaOfficialForms.apply(r, preg, anc, 1, mapOf("weight" to "54", "ifa" to "30"), emptyMap(), emptySet(), today)
    check("save-new-column", bal(r) == 140 && r.stock.count { it.item == "IFA tablets (adult)" } == 3)
    check("save-mcp-plus30", r.beneficiaries.first().mcp.ifaTablets == 60)
    // Re-saving without IFA removes that column's lines (card and stock stay consistent).
    r = ArogyaOfficialForms.apply(r, preg, anc, 1, mapOf("weight" to "54"), emptyMap(), emptySet(), today)
    check("save-blank-resave", bal(r) == 170 && r.beneficiaries.first().mcp.ifaTablets == 30)
    // Keyed lines: re-saving one column never touches another column's lines,
    // even when two forms share the column label ("Visit 1" in sick and fp).
    val sickNote = ArogyaOfficialForms.stockNote(ArogyaOfficialForms.SICK, 0)
    val fpNote = ArogyaOfficialForms.stockNote(ArogyaOfficialForms.FP, 0)
    var r2 = RegistryData(beneficiaries = listOf(preg), stock = listOf(
        StockTxn(id = "k1", date = "2026-10-20", item = "ORS", qty = -4, beneficiaryId = "p1", note = sickNote),
        StockTxn(id = "k2", date = "2026-10-20", item = "ORS", qty = -2, beneficiaryId = "p1", note = fpNote)))
    r2 = ArogyaOfficialForms.apply(r2, preg, ArogyaOfficialForms.SICK, 0, mapOf("ors" to "4"), emptyMap(), emptySet(), today)
    check("save-keyed-columns", r2.stock.count { it.item == "ORS" } == 2 && r2.stock.any { it.id == "k2" })
    // Pre-key lines migrate on re-save: same label, no key -> replaced, never doubled.
    var r3 = RegistryData(beneficiaries = listOf(preg), stock = listOf(
        StockTxn(id = "l1", date = "2026-10-20", item = "IFA tablets (adult)", qty = -30, beneficiaryId = "p1", note = "Given at home visit (ANC 1)")))
    r3 = ArogyaOfficialForms.apply(r3, preg, anc, 0, mapOf("weight" to "52", "ifa" to "30"), emptyMap(), emptySet(), today)
    check("save-legacy-migrate", r3.stock.count { it.item == "IFA tablets (adult)" } == 1 && bal(r3) == -30)
    // Note format round-trip.
    check("save-note-format", ArogyaOfficialForms.stockNote(anc, 0) == "Given at home visit (ANC 1) [mcp_anc#0]")
    check("save-key-parse", ArogyaOfficialForms.stockKey("Given at home visit (ANC 1) [mcp_anc#0]") == ("mcp_anc" to 0))
    check("save-key-legacy", ArogyaOfficialForms.stockKey("Given at home visit (ANC 1)") == null && ArogyaOfficialForms.stockKey("Received") == null)

    // The save lands in the first truly empty column (entries and MCP ANC both count).
    fun entry(col: Int, date: String) = FormEntry(beneficiaryId = "p1", formId = "mcp_anc", column = col, date = date, values = mapOf("weight" to "52"))
    check("col-gap", ArogyaOfficialForms.formFor(preg, listOf(entry(0, "2026-10-01"), entry(2, "2026-10-20")), today).second == 1)
    val rawAnc = preg.copy(mcp = McpCard(anc = mapOf(1 to "2026-10-01", 2 to "2026-10-20")))
    check("col-raw-anc", ArogyaOfficialForms.formFor(rawAnc, emptyList(), today).second == 2)
    // anc key k occupies column k-1: entry col 0 + raw ANC 3 -> first empty is column 1.
    val mixed = preg.copy(mcp = McpCard(anc = mapOf(3 to "2026-10-20")))
    check("col-mixed", ArogyaOfficialForms.formFor(mixed, listOf(entry(0, "2026-10-01")), today).second == 1)
    // Seed consistency: build() seeds once internally; every sample pregnant woman's
    // IFA card total must equal her ANC form columns' IFA and her IFA stock lines.
    val seeded = ArogyaSampleVillage.build(today).registry
    val pregOk = seeded.beneficiaries.filter { it.category == BenCategory.PREGNANT }.all { w ->
        val fromForms = seeded.forms.filter { it.beneficiaryId == w.id && it.formId == "mcp_anc" }
            .sumOf { it.values["ifa"]?.toDoubleOrNull()?.toInt() ?: 0 }
        val fromStock = -seeded.stock.filter { it.beneficiaryId == w.id && it.item == "IFA tablets (adult)" }.sumOf { it.qty }
        w.mcp.ifaTablets == fromForms && fromForms == fromStock
    }
    check("seed-consistency", pregOk)

    // Next due date: birth (or SNCU discharge) = Day 1.
    val ritu = Beneficiary(id = "b1", name = "Baby of Ritu", category = BenCategory.CHILD, dob = "2026-10-22")
    check("due-day1", ArogyaOfficialForms.nextDue(ArogyaOfficialForms.ANNEX9, ritu, 3, today) == LocalDate.of(2026, 11, 11))
    val sncu = ritu.copy(sncuDischarge = "2026-10-27")
    check("due-sncu-day1", ArogyaOfficialForms.nextDue(ArogyaOfficialForms.ANNEX9, sncu, 3, today) == LocalDate.of(2026, 11, 16))

    // Stream abort: the card clears on "checking" so the next answer never appends to rejected text.
    val empty = ArogyaDraftUi()
    val w1 = ArogyaDraftUiReduce.reduce(empty, ArogyaProgress("write", "Hel"))
    check("draft-first", w1.text == "Hel" && !w1.checking)
    val w2 = ArogyaDraftUiReduce.reduce(w1, ArogyaProgress("write", "Hello"))
    check("draft-accumulate", w2.text == "Hello" && !w2.checking)
    val c1 = ArogyaDraftUiReduce.reduce(w2, ArogyaProgress("checking"))
    check("draft-abort-clears", c1.text == "" && c1.checking)
    val w3 = ArogyaDraftUiReduce.reduce(c1, ArogyaProgress("write", "New"))
    check("draft-after-abort", w3.text == "New" && !w3.checking)
    check("draft-blank-ignored", ArogyaDraftUiReduce.reduce(w2, ArogyaProgress("write", "")) == w2)
    check("draft-search-ignored", ArogyaDraftUiReduce.reduce(w2, ArogyaProgress("search")) == w2)

    // Question type (review order): how-many/which-for-age/dose -> FACT; danger signs -> LIST.
    check("type-how-many", ArogyaQuestionType.kindOf("how many IFA tablets in pregnancy") == ArogyaQuestionType.Kind.FACT)
    check("type-which-age", ArogyaQuestionType.kindOf("which IFA tablet for school-going adolescents") == ArogyaQuestionType.Kind.FACT)
    check("type-ors-proc", ArogyaQuestionType.kindOf("ORS kaise banaye") == ArogyaQuestionType.Kind.PROCEDURE)
    check("type-danger-list", ArogyaQuestionType.kindOf("danger signs in newborn") == ArogyaQuestionType.Kind.LIST)
    val doseLine = ArogyaQuestionType.lengthLine(ArogyaQuestionType.Kind.FACT, "what is the zinc dose for a 4-month-old")
    check("type-dose-line", "give only the dose for the asked age group; do not list other age groups" in doseLine)
    val plainLine = ArogyaQuestionType.lengthLine(ArogyaQuestionType.Kind.FACT, "what is anaemia")
    check("type-plain-line", "give only the dose" !in plainLine)

    println("review: $n checks, $fails failures")
    if (fails > 0) kotlin.system.exitProcess(1)
}
