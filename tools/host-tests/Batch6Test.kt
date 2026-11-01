import com.example.arfas.*
var fails = 0; var n = 0
fun check(name: String, ok: Boolean) { n++; if (!ok) { fails++; println("FAIL $name") } }
fun main() {
    val today = java.time.LocalDate.of(2026, 11, 1)
    fun v(id: String, referral: ArogyaReferral, date: String, completed: Boolean) =
        ArogyaVisit(id = id, referral = referral, date = date, completed = completed)
    val needed = v("a", ArogyaReferral.NEEDED, "2026-11-01", true)
    val old = v("b", ArogyaReferral.NEEDED, "2026-10-20", true)
    val contacted = v("c", ArogyaReferral.CONTACTED, "2026-11-01", true)
    val arranged = v("d", ArogyaReferral.ARRANGED, "2026-11-01", true)
    val seen = v("e", ArogyaReferral.SEEN, "2026-11-01", true)
    val none = v("f", ArogyaReferral.NONE, "2026-11-01", true)
    val draft = v("g", ArogyaReferral.NEEDED, "2026-11-01", false)
    val all = listOf(needed, old, contacted, arranged, seen, none, draft)
    check("u7-needed", ArogyaReferralFollowUp.pending(all, today, 7).map { it.id } == listOf("a"))
    check("u7-window", ArogyaReferralFollowUp.pending(all, today, 30).map { it.id }.toSet() == setOf("a", "b"))
    check("u7-empty", ArogyaReferralFollowUp.pending(listOf(none, contacted, draft), today, 7).isEmpty())
    println("batch6: $n checks, $fails failures")
    if (fails > 0) kotlin.system.exitProcess(1)
}
