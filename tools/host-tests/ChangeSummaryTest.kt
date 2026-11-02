import com.example.arfas.*
import java.time.LocalDate
var cf = 0; var cp = 0
fun c(c: Boolean, m: String) { if (c) { cp++; println("PASS $m") } else { cf++; println("FAIL $m") } }
fun main() {
    val today = LocalDate.of(2026, 11, 2)
    val F = ArogyaOfficialForms
    val pw = Beneficiary(id = "c-pw", name = "C Mother", village = "Rampur", category = BenCategory.PREGNANT, lmp = "2026-04-06")
    val other = Beneficiary(id = "c-ot", name = "Unrelated Person", village = "Sundarpur", category = BenCategory.OTHER)
    val r0 = RegistryData(beneficiaries = listOf(pw, other),
        stock = listOf(StockTxn(date = "2026-11-01", item = "IFA tablets (adult)", qty = 200, kind = "receive"),
            StockTxn(date = "2026-11-01", item = "ORS", qty = 50, kind = "receive")))
    // ANC visit: every touched record listed with old -> new
    val r1 = F.apply(r0, pw, F.MCP_ANC, 1, mapOf("ifa" to "30", "weight" to "52"), mapOf("ifa" to "asha"), emptySet(), today)
    val next = F.nextDue(F.MCP_ANC, pw, 1, today)?.toString().orEmpty()
    val s1 = ArogyaChangeSummary.diff(r0, r1, "c-pw", false, next)
    println("   $s1")
    c(s1.startsWith("Saved ✓ — Form: ") && s1.contains("ANC 2"), "visit summary names the form + column")
    c(s1.contains("MCP card: IFA +30 (total 30)"), "visit summary shows IFA old -> new")
    c(s1.contains("Stock: IFA tablets (adult) −30 (left 170)"), "visit summary shows stock old -> new")
    c(s1.contains("Diary ✓"), "visit summary confirms the diary")
    c(next.isNotBlank() && s1.contains("Next visit: "), "visit summary shows the next visit")
    c(!s1.contains("Unrelated Person"), "untouched records stay out")
    // Identical re-save: nothing changed
    val r2 = F.apply(r1, pw, F.MCP_ANC, 1, mapOf("ifa" to "30", "weight" to "52"), mapOf("ifa" to "asha"), emptySet(), today)
    c(ArogyaChangeSummary.diff(r1, r2, "c-pw", false) == "Saved ✓", "identical re-save lists nothing")
    // Give: stock + diary, no form
    val r3 = F.applyGive(r2, pw, "ORS", 4, "g9", today = today)
    val s3 = ArogyaChangeSummary.diff(r2, r3, "c-pw", false)
    c(s3.contains("Stock: ORS −4 (left 46)") && s3.contains("Diary ✓") && !s3.contains("Form:"), "give summary lists stock + diary only")
    // Receive: stock only
    val r4 = r3.copy(stock = r3.stock + StockTxn(date = "2026-11-02", item = "ORS", qty = 100, kind = "receive", note = "Voice: Received"))
    c(ArogyaChangeSummary.diff(r3, r4, "", false) == "Saved ✓ — Stock: ORS +100 (left 146)", "receive summary is stock only")
    // Diary attachment
    val r5 = ArogyaChangeSummary.attachDiary(r1, "c-pw", "2026-11-02", s1)
    c(r5.diary.any { it.beneficiaryId == "c-pw" && it.note.endsWith(s1) }, "same text goes into the diary")
    val r6 = ArogyaChangeSummary.attachDiary(r5, "c-pw", "2026-11-02", s1)
    c(r6.diary.count { it.beneficiaryId == "c-pw" } == r5.diary.count { it.beneficiaryId == "c-pw" } &&
        r6.diary.first { it.beneficiaryId == "c-pw" }.note == r5.diary.first { it.beneficiaryId == "c-pw" }.note, "attach is idempotent")
    // Hindi + dates + new people
    val sh = ArogyaChangeSummary.diff(r0, r1, "c-pw", true, next)
    c(sh.startsWith("सहेजा ✓") && sh.contains("अगली विज़िट: ") && sh.contains("डायरी ✓"), "hindi summary")
    c(ArogyaChangeSummary.fmtDay("2026-11-11", false) == "11 Nov", "english day format")
    c(ArogyaChangeSummary.fmtDay("2026-11-11", true) == "11 नव", "hindi day format")
    c(ArogyaChangeSummary.forNewPeople(listOf("Asha", "Bina"), false) == "Saved ✓ — Registered: Asha, Bina · Diary ✓", "new-people summary")
    println(if (cf == 0) "ALL $cp PASS" else "$cf FAILED, $cp passed")
    if (cf > 0) kotlin.system.exitProcess(1)
}
