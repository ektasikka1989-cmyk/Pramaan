import com.example.arfas.*
import java.time.LocalDate
fun main() {
    val t = LocalDate.parse("2026-10-31"); var ok = 0; var n = 0
    fun c(name: String, b: Boolean) { n++; if (b) ok++ else println("FAIL $name") }
    c("newborn", ArogyaGuided.kindFor(Beneficiary(category = BenCategory.CHILD, dob = "2026-10-20"), t) == GuidedKind.NEWBORN)
    c("child", ArogyaGuided.kindFor(Beneficiary(category = BenCategory.CHILD, dob = "2026-01-01"), t) == GuidedKind.CHILD)
    c("pregnant", ArogyaGuided.kindFor(Beneficiary(category = BenCategory.PREGNANT, lmp = "2026-06-01"), t) == GuidedKind.PREGNANT)
    c("couple", ArogyaGuided.kindFor(Beneficiary(category = BenCategory.ELIGIBLE_COUPLE), t) == GuidedKind.COUPLE)
    println(if (ok == n) "ALL $n PASS" else "$ok/$n")
    if (ok != n) kotlin.system.exitProcess(1)
}
