import com.example.arfas.*
import java.time.LocalDate
import java.time.YearMonth

var tf = 0; var tp = 0
fun t(c: Boolean, m: String) { if (c) { tp++; println("PASS $m") } else { tf++; println("FAIL $m") } }

// Item 4: register tables, sorting, due/overdue statuses (SYNTHETIC records only).
fun main() {
    val today = LocalDate.of(2026, 11, 1)
    // 1. DueItem.status: done / window passed / due date passed / upcoming.
    val base = DueItem("b1", "N", "V", "VACCINE", "Vaccines", LocalDate.of(2026, 7, 1), LocalDate.of(2027, 3, 1), false)
    t(base.status(today) == DueStatus.DUE_NOW, "due date passed, window open -> DUE_NOW (Nisha case)")
    t(base.copy(windowEnd = LocalDate.of(2026, 10, 1)).status(today) == DueStatus.OVERDUE, "window passed -> OVERDUE")
    t(base.copy(done = true, doneDate = "2026-07-02").status(today) == DueStatus.DONE, "done -> DONE")
    t(base.copy(due = LocalDate.of(2026, 12, 1), windowEnd = LocalDate.of(2027, 1, 1)).status(today) == DueStatus.UPCOMING, "future -> UPCOMING")
    t(base.overdue(today) == false && base.copy(windowEnd = LocalDate.of(2026, 10, 1)).overdue(today), "overdue() unchanged (window-based)")
    // 2. Nisha in the sample village is listed and marked, not dropped.
    val sm = ArogyaSampleVillage.build(today)
    val imm = ArogyaRegistryLogic.immunizationDueList(sm.registry.beneficiaries, YearMonth.from(today).plusMonths(1), today)
    val nisha = imm.firstOrNull { it.name.contains("Nisha") }
    t(nisha != null && nisha.status(today) == DueStatus.DUE_NOW, "Nisha (due ${nisha?.due}) listed as DUE_NOW")
    val table = ArogyaRegisterTables.immunizationTable(sm.registry.beneficiaries, YearMonth.from(today).plusMonths(1), today, false)
    t(table.headers == listOf("Child", "Mother", "Village", "Age", "Vaccines due", "Due date", "Status"), "immunization columns exact")
    val nrow = table.rows.firstOrNull { it.cells[0].contains("Nisha") }
    t(nrow != null && nrow.cells[6] == "Due now" && nrow.status == "DUE_NOW", "Nisha row marked Due now with status")
    t(nrow != null && nrow.cells[4].contains("MR-2"), "Nisha row names the pending vaccines (MR-2 …)")
    val firstPending = table.rows.firstOrNull { it.status != "DONE" }
    t(firstPending != null && (firstPending.status == "OVERDUE" || firstPending.status == "DUE_NOW"), "overdue/due-now rows on top")
    t(table.rows.none { it.status == "DONE" && YearMonth.from(LocalDate.parse(it.cells[5])) != YearMonth.from(today).plusMonths(1) }, "done rows only from the shown month")
    // 3. dueList: old VACCINE/ANC overdues kept, ancient HBNC dropped.
    val old = Beneficiary(id = "oc", name = "Old Child", category = BenCategory.CHILD, dob = today.minusYears(2).toString(), deliveryPlace = "INSTITUTION")
    val due = ArogyaSchedule.dueList(listOf(old), emptyList(), today, 7)
    t(due.any { it.kind == "VACCINE" && it.overdue(today) }, "old vaccine overdues kept in due list")
    t(due.none { it.kind == "HBNC" }, "ancient HBNC items still dropped (60-day cutoff)")
    val preg = Beneficiary(id = "op", name = "Old Preg", category = BenCategory.PREGNANT, lmp = today.minusDays(200).toString())
    t(ArogyaSchedule.dueList(listOf(preg), emptyList(), today, 7).any { it.kind == "ANC" && it.overdue(today) }, "old ANC overdues kept")
    t(due.takeWhile { it.overdue(today) }.size == due.count { it.overdue(today) }, "due list still overdue-first")
    // 4. sortRows: dates, numbers, text, mixed columns never crash.
    val r = listOf(RegRow(listOf("b", "10", "2026-10-01")), RegRow(listOf("a", "9", "2026-09-01")), RegRow(listOf("c", "x", "n/a")))
    t(ArogyaRegisterTables.sortRows(r, 0, true).map { it.cells[0] } == listOf("a", "b", "c"), "text sort")
    t(ArogyaRegisterTables.sortRows(r, 1, true).map { it.cells[1] } == listOf("9", "10", "x"), "numeric sort with text last")
    t(ArogyaRegisterTables.sortRows(r, 2, false).map { it.cells[2] } == listOf("n/a", "2026-10-01", "2026-09-01"), "date sort desc with text first")
    // 5. Other tables on the sample village.
    val anc = ArogyaRegisterTables.ancTable(sm.registry.beneficiaries, today, false)
    t(anc.headers == listOf("Woman", "Village", "EDD", "Next ANC", "Status") && anc.rows.size == 8, "ANC table: 8 rows, exact columns")
    t(anc.rows.all { it.beneficiaryId.isNotBlank() }, "ANC rows open the person")
    val ecs = ArogyaRegisterTables.coupleTable(sm.registry.beneficiaries, false)
    t(ecs.headers == listOf("Name", "Village", "Method", "Status") && ecs.rows.isNotEmpty(), "couple table non-empty")
    val vhr = ArogyaRegisterTables.villageTable(sm.registry.beneficiaries, today, false)
    t(vhr.headers == listOf("Household", "Head", "Members", "Pregnant", "Under 2", "Status") && vhr.rows.isNotEmpty(), "village table non-empty")
    val vt = ArogyaRegisterTables.vitalTable(sm.registry.vitals, sm.registry.beneficiaries.associate { it.id to it.name }, false)
    t(vt.headers == listOf("Date", "Event", "Person", "Place", "Note") && vt.rows.isNotEmpty(), "vitals table non-empty")
    t(vt.rows.map { it.cells[0] } == vt.rows.map { it.cells[0] }.sortedDescending(), "vitals sorted newest first")
    val dt = ArogyaRegisterTables.diaryTable(sm.visits, sm.registry.diary, sm.registry.beneficiaries.associate { it.id to it.name }, false)
    t(dt.headers == listOf("Date", "Activity", "Person", "Note") && dt.rows.isNotEmpty(), "diary table merges visits + manual")
    val ft = ArogyaRegisterTables.toFormTable("T", "S", anc)
    t(ft.headers == anc.headers && ft.rows.size == anc.rows.size && ft.title == "T", "toFormTable maps headers/rows")
    println(if (tf == 0) "ALL $tp PASS" else "$tf FAILED, $tp passed")
    if (tf > 0) kotlin.system.exitProcess(1)
}
