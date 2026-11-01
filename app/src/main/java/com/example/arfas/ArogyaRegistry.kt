package com.example.arfas

import java.security.SecureRandom
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject

/*
 * ASHA's local registers, offline. Schedules follow the ASHA guide in the installed KB:
 *  - HBNC: days 3, 7, 14, 21, 28, 42 (birth = day 1); home delivery adds day 1 (ASHA Facilitator handbook p.32)
 *  - SNCU/NBSU/NRC discharge: day of discharge = day 1, same day pattern from discharge (HBYC handbook p.16)
 *  - LBW / preterm / SNCU: quarterly follow-up at completed 3, 6, 9, 12 months (SNCU & LBW follow-up guidelines p.6)
 *  - HBYC: completed 3, 6, 9, 12, 15 months (HBYC handbook p.19)
 *  - ANC: ≤12 w, 14–26 w, 28–34 w, 36 w–term (Integrated ASHA Module Part B p.182)
 *  - Immunization contacts: birth, 6/10/14 weeks, 9 months, 16–24 months, 5–6 years (Immunization Handbook p.33, p.39)
 * Dates are reminders for the ASHA; she and her ANM decide.
 */

enum class BenCategory(val title: String, val titleHi: String = title) { ELIGIBLE_COUPLE("Eligible couple", "योग्य दंपति"), PREGNANT("Pregnant woman", "गर्भवती महिला"), MOTHER("Mother (postnatal)", "माँ (प्रसव बाद)"), CHILD("Child 0–6 years", "बच्चा 0–6 साल"), OTHER("Other", "अन्य") }
enum class DiaryKind(val title: String, val titleHi: String = title) { HOME_VISIT("Home visit", "घर विज़िट"), VHND("VHND / immunization session", "VHND / टीकाकरण सत्र"), VHSNC_MEETING("VHSNC / MAS meeting", "VHSNC / MAS बैठक"), PHC_MEETING("Monthly meeting at PHC", "PHC पर मासिक बैठक"), ESCORT("Escort to facility", "अस्पताल साथ जाना"), SURVEY("Household survey / line listing", "घर सर्वे / सूची"), OTHER("Other", "अन्य") }
enum class VitalKind(val title: String, val titleHi: String = title) { BIRTH("Birth", "जन्म"), STILLBIRTH("Stillbirth", "मृत जन्म"), MATERNAL_DEATH("Maternal death", "मातृ मृत्यु"), INFANT_DEATH("Infant death (<1 y)", "शिशु मृत्यु (<1 साल)"), CHILD_DEATH("Child death (1–5 y)", "बाल मृत्यु (1–5 साल)"), OTHER_DEATH("Other death", "अन्य मृत्यु"),
    FEVER("Fever case", "बुखार"), DIARRHOEA("Diarrhoea case", "दस्त"), TB_SUSPECT("TB suspect", "TB संदिग्ध"), AEFI("AEFI (after immunization)", "AEFI (टीके के बाद)"), OTHER("Other surveillance", "अन्य निगरानी") }

data class WeightEntry(val date: String, val kg: Double)
/** The MCP card fields the ASHA tracks. Vaccine codes: see [ArogyaSchedule.VACCINE_CONTACTS]. ANC: 1..4 → date. */
data class AncDetail(val weightKg: String = "", val bp: String = "", val hb: String = "")
data class McpCard(val vaccines: Map<String, String> = emptyMap(), val anc: Map<Int, String> = emptyMap(), val weights: List<WeightEntry> = emptyList(),
    val tdDates: List<String> = emptyList(), val ifaTablets: Int = 0, val ancDetails: Map<Int, AncDetail> = emptyMap())

data class Beneficiary(
    val id: String = java.util.UUID.randomUUID().toString(), val name: String = "", val relativeName: String = "", val village: String = "", val phone: String = "",
    /** Household from the ASHA's line listing (house number / family head). */
    val household: String = "",
    /** Age in years (adults), as on the RI forms. */
    val age: String = "",
    val sex: String = "F", val category: BenCategory = BenCategory.OTHER, val dob: String = "", val lmp: String = "", val deliveryDate: String = "",
    val deliveryPlace: String = "", val birthWeightKg: Double? = null, val preterm: Boolean = false, val sncuDischarge: String = "", val motherId: String = "",
    val rchId: String = "", val familyPlanning: String = "", val notes: String = "", val active: Boolean = true, val mcp: McpCard = McpCard(),
    val demo: Boolean = false, val createdAt: Long = System.currentTimeMillis(), val updatedAt: Long = System.currentTimeMillis()
)
data class DiaryEntry(val id: String = java.util.UUID.randomUUID().toString(), val date: String = LocalDate.now().toString(), val kind: DiaryKind = DiaryKind.HOME_VISIT,
    val beneficiaryId: String = "", val note: String = "")
/** qty > 0 = received into the kit, qty < 0 = given out. */
data class StockTxn(val id: String = java.util.UUID.randomUUID().toString(), val date: String = LocalDate.now().toString(), val item: String = "", val qty: Int = 0,
    val beneficiaryId: String = "", val note: String = "")
data class VitalEvent(val id: String = java.util.UUID.randomUUID().toString(), val date: String = LocalDate.now().toString(), val kind: VitalKind = VitalKind.OTHER,
    val beneficiaryId: String = "", val place: String = "", val note: String = "")
data class RegistryData(val beneficiaries: List<Beneficiary> = emptyList(), val diary: List<DiaryEntry> = emptyList(), val stock: List<StockTxn> = emptyList(),
    val vitals: List<VitalEvent> = emptyList(), val forms: List<FormEntry> = emptyList())

data class DueItem(val beneficiaryId: String, val name: String, val village: String, val kind: String, val label: String, val due: LocalDate, val windowEnd: LocalDate,
    val done: Boolean, val doneDate: String = "") {
    fun overdue(today: LocalDate) = !done && windowEnd < today
    /** Register status: done, window passed (overdue), due date passed but window open (due now), or upcoming. */
    fun status(today: LocalDate): DueStatus = when {
        done -> DueStatus.DONE
        windowEnd < today -> DueStatus.OVERDUE
        due <= today -> DueStatus.DUE_NOW
        else -> DueStatus.UPCOMING
    }
}

enum class DueStatus { DONE, OVERDUE, DUE_NOW, UPCOMING }

private fun d(s: String): LocalDate? = runCatching { if (s.isBlank()) null else LocalDate.parse(s) }.getOrNull()

object ArogyaRegistryLogic {
    val DRUG_KIT = listOf("ORS", "Zinc tablets", "IFA tablets (adult)", "IFA syrup (child)", "Paracetamol", "Albendazole", "Condoms", "Oral contraceptive pills",
        "Emergency contraceptive pill", "Sanitary napkins", "Pregnancy test kit", "Cotrimoxazole", "Chloroquine / ACT", "Rapid diagnostic test (malaria)", "Cotton / bandage")

    fun isHighRisk(b: Beneficiary) = (b.birthWeightKg?.let { it < 2.5 } ?: false) || b.preterm || b.sncuDischarge.isNotBlank()
    fun edd(b: Beneficiary): LocalDate? = d(b.lmp)?.plusDays(280)
    fun ageDays(b: Beneficiary, today: LocalDate): Long? = d(b.dob)?.let { ChronoUnit.DAYS.between(it, today) }

    /** RCH register group for display: eligible couple, pregnant, infant 0–1, child 1–6, other. */
    fun rchGroup(b: Beneficiary, today: LocalDate): String {
        if (b.category == BenCategory.PREGNANT && b.deliveryDate.isBlank()) return "Pregnant women"
        val age = ageDays(b, today)
        if (age != null && age >= 0 && b.category == BenCategory.CHILD) return if (age < 365) "Infants (0–1 year)" else if (age < 365 * 6) "Children (1–6 years)" else "Other"
        return when (b.category) { BenCategory.ELIGIBLE_COUPLE -> "Eligible couples"; BenCategory.MOTHER -> "Mothers (postnatal)"; else -> "Other" }
    }

    fun stockBalance(stock: List<StockTxn>, upTo: LocalDate? = null): Map<String, Int> =
        stock.filter { upTo == null || (d(it.date)?.let { x -> !x.isAfter(upTo) } ?: true) }.groupBy { it.item }.mapValues { (_, t) -> t.sumOf { it.qty } }

    data class MonthlyReport(val month: YearMonth, val homeVisits: Int, val scheduledDone: Map<String, Int>, val diary: Map<DiaryKind, Int>, val received: Map<String, Int>,
        val distributed: Map<String, Int>, val closing: Map<String, Int>, val vitals: Map<VitalKind, Int>, val newRegistrations: Int, val text: String)

    fun monthlyReport(data: RegistryData, visits: List<ArogyaVisit>, month: YearMonth): MonthlyReport {
        fun inMonth(s: String) = d(s)?.let { YearMonth.from(it) == month } ?: false
        val homeVisits = visits.count { it.completed && inMonth(it.date) }
        val end = month.atEndOfMonth()
        val byPerson = visits.filter { it.completed }.groupBy { it.personId }
        val done = data.beneficiaries.flatMap { b -> ArogyaSchedule.forBeneficiary(b, byPerson[b.id].orEmpty(), end) }.filter { it.done && inMonth(it.doneDate) }
            .groupingBy { it.kind }.eachCount()
        val diary = data.diary.filter { inMonth(it.date) }.groupingBy { it.kind }.eachCount()
        val monthStock = data.stock.filter { inMonth(it.date) }
        val received = monthStock.filter { it.qty > 0 }.groupBy { it.item }.mapValues { (_, t) -> t.sumOf { it.qty } }
        val distributed = monthStock.filter { it.qty < 0 }.groupBy { it.item }.mapValues { (_, t) -> -t.sumOf { it.qty } }
        val closing = stockBalance(data.stock, end)
        val vitals = data.vitals.filter { inMonth(it.date) }.groupingBy { it.kind }.eachCount()
        val newReg = data.beneficiaries.count { b -> java.time.Instant.ofEpochMilli(b.createdAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate().let { YearMonth.from(it) == month } }
        val text = buildString {
            appendLine("ASHA MONTHLY SUMMARY — $month (prepared offline in ArogyaDoot; verify before submitting)")
            appendLine("Home visits: $homeVisits")
            if (done.isNotEmpty()) appendLine("Scheduled contacts completed: " + done.entries.joinToString(", ") { "${it.key} ${it.value}" })
            if (diary.isNotEmpty()) appendLine("Activity diary: " + diary.entries.joinToString(", ") { "${it.key.title} ${it.value}" })
            appendLine("New registrations: $newReg")
            if (received.isNotEmpty()) appendLine("Drug kit received: " + received.entries.joinToString(", ") { "${it.key} ${it.value}" })
            if (distributed.isNotEmpty()) appendLine("Drug kit distributed: " + distributed.entries.joinToString(", ") { "${it.key} ${it.value}" })
            if (closing.isNotEmpty()) appendLine("Closing stock: " + closing.entries.joinToString(", ") { "${it.key} ${it.value}" })
            if (vitals.isNotEmpty()) appendLine("Vital events / surveillance: " + vitals.entries.joinToString(", ") { "${it.key.title} ${it.value}" })
        }.trim()
        return MonthlyReport(month, homeVisits, done, diary, received, distributed, closing, vitals, newReg, text)
    }

    // ── Line listing / Village Health Register (updated every 6 months / monthly) ──
    data class VillageRegister(val households: Int, val people: Int, val byGroup: Map<String, Int>, val highRisk: Int)
    fun byHousehold(bens: List<Beneficiary>): Map<String, List<Beneficiary>> =
        bens.filter { it.active }.groupBy { it.household.ifBlank { "(no household) " + it.name } }
    fun villageRegister(bens: List<Beneficiary>, today: LocalDate = LocalDate.now()): VillageRegister {
        val active = bens.filter { it.active }
        return VillageRegister(byHousehold(active).size, active.size, active.groupingBy { rchGroup(it, today) }.eachCount(), active.count { isHighRisk(it) })
    }

    // ── Monthly lists the ASHA prepares (national incentive list items 4c–4e) ──
    /** Children whose vaccine contact is due in [month] or is still pending from before (all pending, overdue first). */
    fun immunizationDueList(bens: List<Beneficiary>, month: YearMonth, today: LocalDate = LocalDate.now()): List<DueItem> =
        bens.filter { it.active && it.category == BenCategory.CHILD }.flatMap { b -> ArogyaSchedule.forBeneficiary(b, emptyList(), today).filter { it.kind == "VACCINE" } }
            .filter { !it.done && !it.due.isAfter(month.atEndOfMonth()) }
            .sortedWith(compareBy<DueItem>({ it.status(today) != DueStatus.OVERDUE }, { it.status(today) != DueStatus.DUE_NOW }, { it.due }, { it.name }))
    /** Pregnant women with EDD and next ANC due. */
    fun ancList(bens: List<Beneficiary>, today: LocalDate = LocalDate.now()): List<Pair<Beneficiary, String>> =
        bens.filter { it.active && it.category == BenCategory.PREGNANT && it.deliveryDate.isBlank() && it.lmp.isNotBlank() }.map { b ->
            val next = ArogyaSchedule.forBeneficiary(b, emptyList(), today).firstOrNull { it.kind == "ANC" && !it.done }
            b to ("EDD ${edd(b)}" + (next?.let { " · next ${it.label} by ${it.windowEnd}" + if (it.overdue(today)) " (OVERDUE)" else "" } ?: " · all 4 ANC recorded"))
        }.sortedBy { edd(it.first) }
    fun eligibleCouples(bens: List<Beneficiary>) = bens.filter { it.active && it.category == BenCategory.ELIGIBLE_COUPLE }.sortedBy { it.name.lowercase() }

    // ── Incentive estimate (national list 2025 in the guide: "ASHA Incentives under NHM"; state rates may differ) ──
    data class IncentiveLine(val item: String, val count: Int, val rate: Int) { val amount get() = count * rate }
    data class IncentiveEstimate(val month: YearMonth, val lines: List<IncentiveLine>) { val total get() = lines.sumOf { it.amount } }
    fun incentiveEstimate(data: RegistryData, visits: List<ArogyaVisit>, month: YearMonth): IncentiveEstimate {
        fun inMonth(s: String) = d(s)?.let { YearMonth.from(it) == month } ?: false
        val end = month.atEndOfMonth()
        val byPerson = visits.filter { it.completed }.groupBy { it.personId }
        var hbnc = 0; var hbyc = 0; var lbw = 0; var fullImm = 0
        data.beneficiaries.filter { it.active && it.category == BenCategory.CHILD }.forEach { b ->
            val items = ArogyaSchedule.forBeneficiary(b, byPerson[b.id].orEmpty(), end)
            val h = items.filter { it.kind == "HBNC" }
            if (h.isNotEmpty() && h.all { it.done } && inMonth(h.maxOf { it.doneDate })) hbnc++
            items.filter { it.kind == "HBYC" && it.done && inMonth(it.doneDate) }.forEach { if (it.label.contains("LBW")) lbw++ else hbyc++ }
            val dob = d(b.dob)
            val firstYear = items.filter { it.kind == "VACCINE" }.take(5)
            if (dob != null && firstYear.size == 5 && firstYear.all { it.done } && inMonth(firstYear.maxOf { it.doneDate }) &&
                (d(firstYear.maxOf { it.doneDate })?.isBefore(dob.plusYears(1)) == true)) fullImm++
        }
        val diary = data.diary.filter { inMonth(it.date) }
        return IncentiveEstimate(month, listOf(
            IncentiveLine("HBNC visits completed (6/7 visits)", hbnc, 250),
            IncentiveLine("HBYC home visit", hbyc, 50),
            IncentiveLine("LBW / SNCU quarterly follow-up", lbw, 50),
            IncentiveLine("Full immunization under 1 year", fullImm, 100),
            IncentiveLine("VHND session mobilised", diary.count { it.kind == DiaryKind.VHND }, 200),
            IncentiveLine("VHSNC / MAS monthly meeting", diary.count { it.kind == DiaryKind.VHSNC_MEETING }.coerceAtMost(1), 150),
            IncentiveLine("Monthly meeting at PHC", diary.count { it.kind == DiaryKind.PHC_MEETING }.coerceAtMost(1), 150)
        ))
    }

    private fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
    private fun row(vararg v: String) = v.joinToString(",") { q(it) }

    fun rchCsv(bens: List<Beneficiary>, today: LocalDate = LocalDate.now()): String = buildString {
        appendLine("id,name,relative,household,village,phone,sex,group,dob,lmp,edd,delivery_date,delivery_place,birth_weight_kg,high_risk,rch_id,anc_dates,vaccines")
        bens.forEach { b -> appendLine(row(b.id, b.name, b.relativeName, b.household, b.village, b.phone, b.sex, rchGroup(b, today), b.dob, b.lmp, edd(b)?.toString().orEmpty(),
            b.deliveryDate, b.deliveryPlace, b.birthWeightKg?.toString().orEmpty(), if (isHighRisk(b)) "yes" else "", b.rchId,
            b.mcp.anc.entries.sortedBy { it.key }.joinToString(" ") { "ANC${it.key}:${it.value}" }, b.mcp.vaccines.entries.joinToString(" ") { "${it.key}:${it.value}" })) }
    }.trimEnd()
    fun diaryCsv(e: List<DiaryEntry>, names: Map<String, String>) = buildString {
        appendLine("date,activity,beneficiary,note"); e.sortedBy { it.date }.forEach { appendLine(row(it.date, it.kind.title, names[it.beneficiaryId].orEmpty(), it.note)) } }.trimEnd()
    fun stockCsv(t: List<StockTxn>, names: Map<String, String>) = buildString {
        appendLine("date,item,received,given,beneficiary,note"); t.sortedBy { it.date }.forEach { appendLine(row(it.date, it.item, if (it.qty > 0) "${it.qty}" else "", if (it.qty < 0) "${-it.qty}" else "", names[it.beneficiaryId].orEmpty(), it.note)) } }.trimEnd()
    fun vitalsCsv(v: List<VitalEvent>, names: Map<String, String>) = buildString {
        appendLine("date,event,beneficiary,place,note"); v.sortedBy { it.date }.forEach { appendLine(row(it.date, it.kind.title, names[it.beneficiaryId].orEmpty(), it.place, it.note)) } }.trimEnd()
    fun visitsCsv(v: List<ArogyaVisit>) = buildString {
        appendLine("date,name,village,type,completed,referral,next_date"); v.sortedBy { it.date }.forEach { appendLine(row(it.date, it.name, it.village, it.kind.title, if (it.completed) "yes" else "draft", it.referral.name, it.nextDate)) } }.trimEnd()
}

object ArogyaSchedule {
    /** Immunization contacts: label, codes, due offset, window end (from DOB). */
    data class Contact(val label: String, val codes: List<String>, val due: (LocalDate) -> LocalDate, val end: (LocalDate) -> LocalDate)
    val VACCINE_CONTACTS = listOf(
        Contact("Birth doses (BCG, OPV-0, Hep B-0)", listOf("BCG", "OPV0", "HEPB0"), { it }, { it.plusDays(14) }),
        Contact("6 weeks (OPV-1, Penta-1, RVV-1, fIPV-1, PCV-1)", listOf("OPV1", "PENTA1", "RVV1", "FIPV1", "PCV1"), { it.plusDays(42) }, { it.plusDays(69) }),
        Contact("10 weeks (OPV-2, Penta-2, RVV-2)", listOf("OPV2", "PENTA2", "RVV2"), { it.plusDays(70) }, { it.plusDays(97) }),
        Contact("14 weeks (OPV-3, Penta-3, RVV-3, fIPV-2, PCV-2)", listOf("OPV3", "PENTA3", "RVV3", "FIPV2", "PCV2"), { it.plusDays(98) }, { it.plusMonths(9).minusDays(1) }),
        Contact("9 months (MR-1, Vitamin A, PCV booster)", listOf("MR1", "VITA1", "PCVB"), { it.plusMonths(9) }, { it.plusMonths(12) }),
        Contact("16–24 months (MR-2, DPT booster-1, OPV booster)", listOf("MR2", "DPTB1", "OPVB"), { it.plusMonths(16) }, { it.plusMonths(24) }),
        Contact("5–6 years (DPT booster-2)", listOf("DPTB2"), { it.plusYears(5) }, { it.plusYears(6) })
    )
    val VACCINE_NAMES = mapOf("BCG" to "BCG", "OPV0" to "OPV-0", "HEPB0" to "Hep B birth dose", "OPV1" to "OPV-1", "PENTA1" to "Penta-1", "RVV1" to "Rotavirus-1", "FIPV1" to "fIPV-1",
        "PCV1" to "PCV-1", "OPV2" to "OPV-2", "PENTA2" to "Penta-2", "RVV2" to "Rotavirus-2", "OPV3" to "OPV-3", "PENTA3" to "Penta-3", "RVV3" to "Rotavirus-3", "FIPV2" to "fIPV-2",
        "PCV2" to "PCV-2", "MR1" to "MR-1", "VITA1" to "Vitamin A-1", "PCVB" to "PCV booster", "MR2" to "MR-2", "DPTB1" to "DPT booster-1", "OPVB" to "OPV booster", "DPTB2" to "DPT booster-2")

    private val HBNC_DAYS = listOf(3L, 7L, 14L, 21L, 28L, 42L)

    /** Visit-based items are done by a completed visit (same person) dated inside the item's window. */
    private fun visitItems(b: Beneficiary, kind: String, slots: List<Pair<String, LocalDate>>, lastWindow: Long, visits: List<ArogyaVisit>): List<DueItem> {
        val used = HashSet<String>()
        return slots.mapIndexed { i, (label, due) ->
            val end = slots.getOrNull(i + 1)?.second?.minusDays(1) ?: due.plusDays(lastWindow)
            val v = visits.filter { it.completed && it.id !in used }.firstOrNull { x -> d(x.date)?.let { !it.isBefore(due) && !it.isAfter(end) } == true }
            v?.let { used += it.id }
            DueItem(b.id, b.name, b.village, kind, label, due, end, v != null, v?.date.orEmpty())
        }
    }

    fun forBeneficiary(b: Beneficiary, visits: List<ArogyaVisit>, today: LocalDate): List<DueItem> {
        if (!b.active) return emptyList()
        val mine = visits.filter { it.personId == b.id }
        val out = ArrayList<DueItem>()
        val dob = d(b.dob)
        if (b.category == BenCategory.CHILD && dob != null && ChronoUnit.DAYS.between(dob, today) < 365L * 7) {
            val discharge = d(b.sncuDischarge)
            val base = discharge ?: dob
            val days = (if (discharge == null && b.deliveryPlace.equals("HOME", true)) listOf(1L) else emptyList()) + HBNC_DAYS
            val from = if (discharge != null) "discharge" else "birth"
            out += visitItems(b, "HBNC", days.map { "HBNC day $it ($from = day 1)" to base.plusDays(it - 1) }, 6, mine)
            val risk = ArogyaRegistryLogic.isHighRisk(b)
            out += visitItems(b, "HBYC", listOf(3L, 6L, 9L, 12L, 15L).map { m ->
                (if (risk && m <= 12) "HBYC + LBW/SNCU follow-up, month $m" else "HBYC month $m") to dob.plusMonths(m) }, 30, mine)
            VACCINE_CONTACTS.forEach { c ->
                val given = c.codes.mapNotNull { b.mcp.vaccines[it]?.takeIf { s -> s.isNotBlank() } }
                val done = given.size == c.codes.size
                out += DueItem(b.id, b.name, b.village, "VACCINE", "Vaccines — ${c.label}", c.due(dob), c.end(dob), done, if (done) given.maxOrNull().orEmpty() else "")
            }
        }
        val lmp = d(b.lmp)
        if (b.category == BenCategory.PREGNANT && lmp != null && b.deliveryDate.isBlank()) {
            val edd = lmp.plusDays(280)
            listOf(Triple(1, lmp.plusWeeks(6), lmp.plusWeeks(12)), Triple(2, lmp.plusWeeks(14), lmp.plusWeeks(26)),
                Triple(3, lmp.plusWeeks(28), lmp.plusWeeks(34)), Triple(4, lmp.plusWeeks(36), edd)).forEach { (n, due, end) ->
                val date = b.mcp.anc[n].orEmpty()
                out += DueItem(b.id, b.name, b.village, "ANC", "ANC $n", due, end, date.isNotBlank(), date)
            }
        }
        return out
    }

    /**
     * Pending items due within [days] from today, overdue first. Visit items (HBNC/HBYC) drop
     * off 60 days past their window (a missed newborn visit years ago is not actionable);
     * VACCINE and ANC items never drop — catch-up vaccination and ANC are always actionable.
     */
    fun dueList(bens: List<Beneficiary>, visits: List<ArogyaVisit>, today: LocalDate, days: Long = 7): List<DueItem> {
        val byPerson = visits.filter { it.completed }.groupBy { it.personId }
        val horizon = today.plusDays(days)
        return bens.asSequence().filter { it.active }.flatMap { b -> forBeneficiary(b, byPerson[b.id].orEmpty(), today).asSequence() }
            .filter { !it.done && !it.due.isAfter(horizon) && (it.kind == "VACCINE" || it.kind == "ANC" || !it.windowEnd.isBefore(today.minusDays(60))) }
            .sortedWith(compareBy<DueItem>({ !it.overdue(today) }, { it.due }, { it.name })).toList()
    }
}

/** One row of a register table: display cells, a stable key, and the person it opens (if any). */
data class RegRow(val cells: List<String>, val key: String = "", val beneficiaryId: String = "", val status: String = "")
data class RegTable(val headers: List<String>, val rows: List<RegRow>)

/**
 * Every register and list as a table (item 4): due list, immunization due list, ANC list,
 * eligible couples, village health register, vital events, diary. Pure logic; the UI renders
 * [RegTable] with the shared ArogyaGrid and converts it to [FormTable] for PDF/share/print.
 */
object ArogyaRegisterTables {
    fun statusText(status: DueStatus, hindi: Boolean): String = when (status) {
        DueStatus.DONE -> if (hindi) "हो गया" else "Done"
        DueStatus.OVERDUE -> if (hindi) "समय निकल गया" else "Overdue"
        DueStatus.DUE_NOW -> if (hindi) "अभी बाकी" else "Due now"
        DueStatus.UPCOMING -> if (hindi) "आने वाला" else "Due"
    }

    /** Mother's name: same-household mother delivered on the child's DOB, else the recorded relative. */
    fun motherOf(child: Beneficiary, bens: List<Beneficiary>): String {
        if (child.dob.isNotBlank() && child.household.isNotBlank()) {
            bens.firstOrNull { it.category == BenCategory.MOTHER && it.household == child.household && it.deliveryDate == child.dob }?.let { return it.name }
        }
        return child.relativeName
    }

    fun ageText(b: Beneficiary, today: LocalDate): String {
        val days = ArogyaRegistryLogic.ageDays(b, today) ?: return ""
        if (days < 0) return ""
        if (days < 60) return "${days}d"
        if (days < 730) return "${days / 30}m"
        return "${days / 365}y"
    }

    fun dueTable(items: List<DueItem>, today: LocalDate, hindi: Boolean): RegTable {
        val h = if (hindi) listOf("नाम", "गाँव", "क्या", "तारीख़", "स्थिति") else listOf("Name", "Village", "What", "Due", "Status")
        return RegTable(h, items.map { it ->
            val st = it.status(today)
            val due = when {
                st == DueStatus.OVERDUE -> (if (hindi) "अवधि ख़त्म " else "window ended ") + it.windowEnd
                st == DueStatus.DUE_NOW -> (if (hindi) "अभी (तक " else "now (until ") + it.windowEnd + ")"
                it.done -> it.doneDate
                else -> it.due.toString()
            }
            RegRow(listOf(it.name, it.village, it.label, due, statusText(st, hindi)),
                it.beneficiaryId + it.label + it.due, it.beneficiaryId, st.name)
        })
    }

    /** Immunization due register: every pending contact (overdue first) + contacts done in [month]. */
    fun immunizationTable(bens: List<Beneficiary>, month: YearMonth, today: LocalDate, hindi: Boolean): RegTable {
        val h = if (hindi) listOf("बच्चा", "माँ", "गाँव", "उम्र", "बाकी टीके", "तारीख़", "स्थिति")
        else listOf("Child", "Mother", "Village", "Age", "Vaccines due", "Due date", "Status")
        val rows = ArrayList<RegRow>()
        bens.filter { it.active && it.category == BenCategory.CHILD }.forEach { b ->
            val items = ArogyaSchedule.forBeneficiary(b, emptyList(), today).filter { it.kind == "VACCINE" }
            items.forEach { item ->
                val st = item.status(today)
                val inMonth = item.done && runCatching { YearMonth.from(LocalDate.parse(item.doneDate)) == month }.getOrDefault(false)
                if (!item.done && item.due.isAfter(month.atEndOfMonth())) return@forEach
                if (item.done && !inMonth) return@forEach
                val contact = ArogyaSchedule.VACCINE_CONTACTS.firstOrNull { item.label.endsWith(it.label) }
                val pending = contact?.codes
                    ?.filter { b.mcp.vaccines[it].isNullOrBlank() }
                    ?.map { ArogyaSchedule.VACCINE_NAMES[it] ?: it }.orEmpty()
                rows += RegRow(
                    listOf(b.name, motherOf(b, bens), b.village, ageText(b, today),
                        if (item.done) "—" else pending.joinToString(", ").ifBlank { item.label.removePrefix("Vaccines — ") },
                        if (item.done) item.doneDate else item.due.toString(), statusText(st, hindi)),
                    b.id + item.label, b.id, st.name)
            }
        }
        fun rank(s: String) = when (s) { "OVERDUE" -> 0; "DUE_NOW" -> 1; "UPCOMING" -> 2; else -> 3 }
        return RegTable(h, rows.sortedWith(compareBy({ rank(it.status) }, { it.cells[5] }, { it.cells[0] })))
    }

    fun ancTable(bens: List<Beneficiary>, today: LocalDate, hindi: Boolean): RegTable {
        val h = if (hindi) listOf("महिला", "गाँव", "प्रसव तिथि", "अगली ANC", "स्थिति") else listOf("Woman", "Village", "EDD", "Next ANC", "Status")
        val rows = bens.filter { it.active && it.category == BenCategory.PREGNANT && it.deliveryDate.isBlank() && it.lmp.isNotBlank() }.map { b ->
            val next = ArogyaSchedule.forBeneficiary(b, emptyList(), today).firstOrNull { it.kind == "ANC" && !it.done }
            val st = next?.status(today) ?: DueStatus.DONE
            RegRow(listOf(b.name, b.village, ArogyaRegistryLogic.edd(b)?.toString().orEmpty(),
                next?.let { "${it.label} by ${it.windowEnd}" } ?: if (hindi) "चारों हो गईं" else "all 4 recorded",
                statusText(st, hindi)), b.id, b.id, st.name)
        }.sortedWith(compareBy({ it.cells[2] }, { it.cells[0] }))
        return RegTable(h, rows)
    }

    fun coupleTable(bens: List<Beneficiary>, hindi: Boolean): RegTable {
        val h = if (hindi) listOf("नाम", "गाँव", "साधन", "स्थिति") else listOf("Name", "Village", "Method", "Status")
        val rows = ArogyaRegistryLogic.eligibleCouples(bens).map { b ->
            val st = if (b.familyPlanning.isBlank()) DueStatus.DUE_NOW else DueStatus.DONE
            RegRow(listOf(b.name, b.village, b.familyPlanning.ifBlank { if (hindi) "दर्ज नहीं" else "not recorded" },
                if (b.familyPlanning.isBlank()) (if (hindi) "समझाएँ" else "Counsel") else statusText(st, hindi)), b.id, b.id, st.name)
        }
        return RegTable(h, rows)
    }

    fun villageTable(bens: List<Beneficiary>, today: LocalDate, hindi: Boolean): RegTable {
        val h = if (hindi) listOf("परिवार", "मुखिया", "सदस्य", "गर्भवती", "2 साल से छोटे", "स्थिति")
        else listOf("Household", "Head", "Members", "Pregnant", "Under 2", "Status")
        val rows = ArogyaRegistryLogic.byHousehold(bens.filter { it.active }).entries.sortedBy { it.key }.map { (hh, people) ->
            val head = people.firstOrNull { it.category != BenCategory.CHILD }?.name ?: people.firstOrNull()?.name.orEmpty()
            val preg = people.count { it.category == BenCategory.PREGNANT && it.deliveryDate.isBlank() }
            val under2 = people.count { it.category == BenCategory.CHILD && (ArogyaRegistryLogic.ageDays(it, today) ?: 9999) < 730 }
            val risk = people.any { ArogyaRegistryLogic.isHighRisk(it) }
            RegRow(listOf(hh, head, people.size.toString(), preg.toString(), under2.toString(),
                if (risk) (if (hindi) "जोखिम" else "High risk") else "OK"),
                hh, people.firstOrNull { it.category != BenCategory.CHILD }?.id ?: people.firstOrNull()?.id.orEmpty(),
                if (risk) "HIGH_RISK" else "OK")
        }
        return RegTable(h, rows)
    }

    fun vitalTable(vitals: List<VitalEvent>, names: Map<String, String>, hindi: Boolean): RegTable {
        val h = if (hindi) listOf("तारीख़", "घटना", "व्यक्ति", "जगह", "नोट") else listOf("Date", "Event", "Person", "Place", "Note")
        val rows = vitals.sortedByDescending { it.date }.map { v ->
            RegRow(listOf(v.date, if (hindi) v.kind.titleHi else v.kind.title, names[v.beneficiaryId].orEmpty(), v.place, v.note),
                v.id, v.beneficiaryId)
        }
        return RegTable(h, rows)
    }

    fun diaryTable(visits: List<ArogyaVisit>, diary: List<DiaryEntry>, names: Map<String, String>, hindi: Boolean): RegTable {
        val h = if (hindi) listOf("तारीख़", "काम", "व्यक्ति", "नोट") else listOf("Date", "Activity", "Person", "Note")
        val auto = visits.filter { it.completed }.map {
            RegRow(listOf(it.date, (if (hindi) "घर विज़िट (" else "Home visit (") + it.kind.title + ")", names[it.personId] ?: it.name, ""), "v" + it.id, it.personId)
        }
        val manual = diary.map {
            RegRow(listOf(it.date, if (hindi) it.kind.titleHi else it.kind.title, names[it.beneficiaryId].orEmpty(), it.note), it.id, it.beneficiaryId)
        }
        return RegTable(h, (auto + manual).sortedByDescending { it.cells[0] })
    }

    /** Tap-a-header sorting: dates and numbers compare as values, everything else as text. */
    fun sortRows(rows: List<RegRow>, col: Int, asc: Boolean): List<RegRow> {
        fun rank(v: String): Pair<Int, Comparable<*>> {
            runCatching { LocalDate.parse(v) }.getOrNull()?.let { return 0 to it }
            v.toDoubleOrNull()?.let { return 1 to it }
            return 2 to v.lowercase()
        }
        val sorted = rows.sortedWith(Comparator { a, b ->
            val x = rank(a.cells.getOrElse(col) { "" }); val y = rank(b.cells.getOrElse(col) { "" })
            if (x.first != y.first) x.first.compareTo(y.first)
            else @Suppress("UNCHECKED_CAST") (x.second as Comparable<Any>).compareTo(y.second as Any)
        })
        return if (asc) sorted else sorted.reversed()
    }

    fun toFormTable(title: String, source: String, table: RegTable, note: String = ""): FormTable =
        FormTable(title, source, table.headers, table.rows.map { it.cells }, note)
}

object ArogyaRegistryCodec {
    fun encode(r: RegistryData): String = JSONObject().put("version", 1)
        .put("beneficiaries", JSONArray().also { a -> r.beneficiaries.forEach { a.put(ben(it)) } })
        .put("diary", JSONArray().also { a -> r.diary.forEach { a.put(JSONObject().put("id", it.id).put("date", it.date).put("kind", it.kind.name).put("ben", it.beneficiaryId).put("note", it.note)) } })
        .put("stock", JSONArray().also { a -> r.stock.forEach { a.put(JSONObject().put("id", it.id).put("date", it.date).put("item", it.item).put("qty", it.qty).put("ben", it.beneficiaryId).put("note", it.note)) } })
        .put("vitals", JSONArray().also { a -> r.vitals.forEach { a.put(JSONObject().put("id", it.id).put("date", it.date).put("kind", it.kind.name).put("ben", it.beneficiaryId).put("place", it.place).put("note", it.note)) } })
        .put("forms", JSONArray().also { a -> r.forms.forEach { a.put(JSONObject().put("id", it.id).put("ben", it.beneficiaryId).put("form", it.formId).put("col", it.column).put("date", it.date)
            .put("values", JSONObject(it.values)).put("sources", JSONObject(it.sources))) } })
        .toString()

    private fun ben(b: Beneficiary) = JSONObject().put("id", b.id).put("name", b.name).put("relative", b.relativeName).put("household", b.household).put("age", b.age).put("village", b.village).put("phone", b.phone)
        .put("sex", b.sex).put("category", b.category.name).put("dob", b.dob).put("lmp", b.lmp).put("deliveryDate", b.deliveryDate).put("deliveryPlace", b.deliveryPlace)
        .put("birthWeightKg", b.birthWeightKg ?: JSONObject.NULL).put("preterm", b.preterm).put("sncuDischarge", b.sncuDischarge).put("motherId", b.motherId)
        .put("rchId", b.rchId).put("familyPlanning", b.familyPlanning).put("notes", b.notes).put("active", b.active).put("demo", b.demo)
        .put("createdAt", b.createdAt).put("updatedAt", b.updatedAt)
        .put("mcp", JSONObject().put("vaccines", JSONObject(b.mcp.vaccines)).put("anc", JSONObject(b.mcp.anc.mapKeys { it.key.toString() }))
            .put("weights", JSONArray().also { a -> b.mcp.weights.forEach { a.put(JSONObject().put("date", it.date).put("kg", it.kg)) } })
            .put("td", JSONArray(b.mcp.tdDates)).put("ifa", b.mcp.ifaTablets)
            .put("ancDetails", JSONObject().also { o -> b.mcp.ancDetails.forEach { (k, v) -> o.put(k.toString(), JSONObject().put("w", v.weightKg).put("bp", v.bp).put("hb", v.hb)) } }))

    private fun strMap(o: JSONObject?) = o?.keys()?.asSequence()?.associateWith { o.getString(it) } ?: emptyMap()

    fun decode(json: String): RegistryData {
        val o = JSONObject(json)
        require(o.optInt("version", 0) == 1) { "Unsupported registry version" }
        fun arr(k: String) = o.optJSONArray(k) ?: JSONArray()
        val bens = arr("beneficiaries").let { a -> (0 until a.length()).map { i -> val j = a.getJSONObject(i); val m = j.optJSONObject("mcp") ?: JSONObject()
            Beneficiary(j.getString("id"), j.optString("name"), j.optString("relative"), j.optString("village"), j.optString("phone"), j.optString("household"), j.optString("age"), j.optString("sex", "F"),
                runCatching { BenCategory.valueOf(j.optString("category")) }.getOrDefault(BenCategory.OTHER), j.optString("dob"), j.optString("lmp"),
                j.optString("deliveryDate"), j.optString("deliveryPlace"), if (j.isNull("birthWeightKg")) null else j.optDouble("birthWeightKg"),
                j.optBoolean("preterm"), j.optString("sncuDischarge"), j.optString("motherId"), j.optString("rchId"), j.optString("familyPlanning"), j.optString("notes"),
                j.optBoolean("active", true),
                McpCard(strMap(m.optJSONObject("vaccines")), strMap(m.optJSONObject("anc")).mapKeys { it.key.toInt() },
                    (m.optJSONArray("weights") ?: JSONArray()).let { w -> (0 until w.length()).map { WeightEntry(w.getJSONObject(it).getString("date"), w.getJSONObject(it).getDouble("kg")) } },
                    (m.optJSONArray("td") ?: JSONArray()).let { t -> (0 until t.length()).map { t.getString(it) } }, m.optInt("ifa", 0),
                    (m.optJSONObject("ancDetails") ?: JSONObject()).let { ad -> ad.keys().asSequence().associate { k -> val x = ad.getJSONObject(k); k.toInt() to AncDetail(x.optString("w"), x.optString("bp"), x.optString("hb")) } }),
                j.optBoolean("demo"), j.optLong("createdAt"), j.optLong("updatedAt")) } }
        val diary = arr("diary").let { a -> (0 until a.length()).map { i -> val j = a.getJSONObject(i)
            DiaryEntry(j.getString("id"), j.getString("date"), runCatching { DiaryKind.valueOf(j.getString("kind")) }.getOrDefault(DiaryKind.OTHER), j.optString("ben"), j.optString("note")) } }
        val stock = arr("stock").let { a -> (0 until a.length()).map { i -> val j = a.getJSONObject(i)
            StockTxn(j.getString("id"), j.getString("date"), j.getString("item"), j.getInt("qty"), j.optString("ben"), j.optString("note")) } }
        val vitals = arr("vitals").let { a -> (0 until a.length()).map { i -> val j = a.getJSONObject(i)
            VitalEvent(j.getString("id"), j.getString("date"), runCatching { VitalKind.valueOf(j.getString("kind")) }.getOrDefault(VitalKind.OTHER), j.optString("ben"), j.optString("place"), j.optString("note")) } }
        val forms = arr("forms").let { a -> (0 until a.length()).map { i -> val j = a.getJSONObject(i)
            FormEntry(j.getString("id"), j.optString("ben"), j.optString("form"), j.optInt("col"), j.optString("date"), strMap(j.optJSONObject("values")), strMap(j.optJSONObject("sources"))) } }
        return RegistryData(bens, diary, stock, vitals, forms)
    }
}

/**
 * Password-protected backup file (the phone's own key cannot leave the phone, so a backup that can be
 * restored on a new phone needs a password). PBKDF2-HMAC-SHA256 + AES-256-GCM.
 * Layout: "ARGB" | version 1 | iterations (int) | salt 16 | iv 12 | ciphertext+tag
 */
object ArogyaBackupCrypto {
    private val MAGIC = "ARGB".toByteArray()
    private fun key(password: String, salt: ByteArray, iterations: Int) =
        SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(password.toCharArray(), salt, iterations, 256)).encoded, "AES")

    fun encrypt(plain: ByteArray, password: String, iterations: Int = 150_000): ByteArray {
        require(password.length >= 8) { "Backup password must be at least 8 characters." }
        val rnd = SecureRandom(); val salt = ByteArray(16).also { rnd.nextBytes(it) }; val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key(password, salt, iterations), GCMParameterSpec(128, iv))
        val head = java.nio.ByteBuffer.allocate(4 + 1 + 4).put(MAGIC).put(1).putInt(iterations).array()
        return head + salt + iv + c.doFinal(plain)
    }

    fun decrypt(data: ByteArray, password: String): ByteArray {
        require(data.size > 9 + 16 + 12 + 16 && data.copyOfRange(0, 4).contentEquals(MAGIC) && data[4].toInt() == 1) { "Not an ArogyaDoot backup file." }
        val iterations = java.nio.ByteBuffer.wrap(data, 5, 4).int
        require(iterations in 1000..2_000_000) { "Damaged backup file." }
        val salt = data.copyOfRange(9, 25); val iv = data.copyOfRange(25, 37)
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, key(password, salt, iterations), GCMParameterSpec(128, iv))
        return try { c.doFinal(data.copyOfRange(37, data.size)) } catch (e: Exception) { throw IllegalArgumentException("Wrong password or damaged backup file.") }
    }
}

/**
 * A synthetic village of 50 people (all marked demo) for testing and demonstration: households,
 * pregnant women at different weeks, newborns mid-HBNC (some visits done, one missed), infants with
 * partial vaccines, a low-birth-weight SNCU baby, children, eligible couples, diary, drug kit and events.
 * Every name is fictional.
 */
object ArogyaSampleVillage {
    data class Sample(val registry: RegistryData, val visits: List<ArogyaVisit>)

    fun build(today: LocalDate = LocalDate.now()): Sample {
        val bens = ArrayList<Beneficiary>(); val visits = ArrayList<ArogyaVisit>()
        val villages = listOf("Demo Gaon", "Demo Tola")
        var h = 0
        fun house(head: String) = "H-%02d %s".format(++h, head)
        fun id(n: Int) = "demo-b-%02d".format(n)
        var n = 0
        fun add(b: Beneficiary): Beneficiary { val x = b.copy(id = id(++n), demo = true, createdAt = today.minusDays(40).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()); bens += x; return x }
        fun doneVisit(b: Beneficiary, daysAgo: Long, note: String) {
            visits += ArogyaVisit(id = "demo-v-${b.id}-$daysAgo", personId = b.id, name = b.name, village = b.village, kind = ArogyaKind.NEWBORN,
                date = today.minusDays(daysAgo).toString(), birthDate = b.dob, homeBirth = b.deliveryPlace == "HOME", notes = note,
                completed = true, reviewed = true, notesReviewedForDanger = true, demo = true)
        }
        // 8 pregnant women at different weeks
        val weeks = listOf(8, 13, 18, 24, 30, 35, 37, 39)
        val womenNames = listOf("Sunita", "Rekha", "Pooja", "Meena", "Kavita", "Anita", "Geeta", "Lalita")
        weeks.forEachIndexed { i, w ->
            val lmp = today.minusWeeks(w.toLong())
            val anc = buildMap { if (w >= 10 && i != 1) put(1, lmp.plusWeeks(10).toString()); if (w >= 20 && i != 4) put(2, lmp.plusWeeks(20).toString()); if (w >= 32) put(3, lmp.plusWeeks(31).toString()) }
            add(Beneficiary(name = "${womenNames[i]} Devi (demo)", relativeName = "Husband ${i + 1}", age = "${21 + i * 2}", household = house("${womenNames[i]}'s family"), village = villages[i % 2],
                category = BenCategory.PREGNANT, sex = "F", lmp = lmp.toString(), phone = "9000000%03d".format(i), mcp = McpCard(anc = anc, ifaTablets = if (w > 14) 60 else 0,
                    tdDates = listOfNotNull(anc[1], if (w >= 20) lmp.plusWeeks(18).toString() else null),
                    ancDetails = anc.keys.associateWith { k -> AncDetail("%.1f".format(java.util.Locale.ROOT, 48.0 + i + k * 2.5), if (i == 6 && k == 3) "142/94" else "110/70", if (i == 2) "9.6" else "11.2") })))
        }
        // 6 newborns (with their mothers in the same household)
        data class NB(val days: Long, val name: String, val home: Boolean, val kg: Double, val sncu: Long?, val doneDays: List<Long>)
        val nbs = listOf(NB(2, "Baby of Sapna", false, 3.0, null, emptyList()), NB(6, "Baby of Neha", true, 2.8, null, listOf(1, 3)),
            NB(10, "Baby of Ritu", false, 2.9, null, listOf(3, 7)), NB(15, "Baby of Komal", false, 2.1, 5, listOf(3, 7)),
            NB(24, "Baby of Priya", false, 3.2, null, listOf(3, 14, 21)), NB(38, "Baby of Asha", false, 2.7, null, listOf(3, 7, 14, 21, 28)))
        nbs.forEach { nb ->
            val hh = house(nb.name.removePrefix("Baby of ") + "'s family")
            val dob = today.minusDays(nb.days)
            add(Beneficiary(name = nb.name.removePrefix("Baby of ") + " (mother, demo)", household = hh, village = villages[0], category = BenCategory.MOTHER, sex = "F",
                deliveryDate = dob.toString(), familyPlanning = "Counselled"))
            val b = add(Beneficiary(name = nb.name + " (demo)", household = hh, village = villages[0], category = BenCategory.CHILD, sex = if (nb.days % 2L == 0L) "F" else "M",
                dob = dob.toString(), deliveryPlace = if (nb.home) "HOME" else "INSTITUTION", birthWeightKg = nb.kg, preterm = nb.kg < 2.5,
                sncuDischarge = nb.sncu?.let { dob.plusDays(it).toString() }.orEmpty(),
                mcp = McpCard(vaccines = if (nb.home) emptyMap() else mapOf("BCG" to dob.toString(), "OPV0" to dob.toString(), "HEPB0" to dob.toString()),
                    weights = listOf(WeightEntry(dob.toString(), nb.kg)))))
            val base = nb.sncu?.let { dob.plusDays(it) } ?: dob
            nb.doneDays.forEach { d -> val date = base.plusDays(d - 1); if (!date.isAfter(today)) doneVisit(b, java.time.temporal.ChronoUnit.DAYS.between(date, today), "Baby feeding well. Temperature 36.8 C. Weight %.2f kg. Cord dry.".format(java.util.Locale.ROOT, nb.kg + 0.02 * d)) }
        }
        // 10 infants 2–12 months with partial vaccines
        (0 until 10).forEach { i ->
            val months = 2L + i
            val dob = today.minusMonths(months).minusDays(3)
            val vac = HashMap<String, String>()
            ArogyaSchedule.VACCINE_CONTACTS.forEachIndexed { ci, c -> if (!c.due(dob).isAfter(today.minusDays(7)) && !(i == 3 && ci >= 2) && !(i == 7 && ci == 4)) c.codes.forEach { vac[it] = c.due(dob).toString() } }
            val b = add(Beneficiary(name = "Infant ${listOf("Aarav", "Diya", "Kabir", "Isha", "Vivaan", "Anaya", "Reyansh", "Myra", "Arjun", "Sara")[i]} (demo)",
                relativeName = "Mother ${i + 1}", household = house("Family ${i + 1}"), village = villages[i % 2], category = BenCategory.CHILD, sex = if (i % 2 == 0) "M" else "F",
                dob = dob.toString(), deliveryPlace = "INSTITUTION", birthWeightKg = if (i == 5) 2.3 else 2.9, mcp = McpCard(vaccines = vac)))
            if (months >= 3 && i % 3 != 0) visits += ArogyaVisit(id = "demo-v-${b.id}-hbyc3", personId = b.id, name = b.name, village = b.village, kind = ArogyaKind.CHILD,
                date = dob.plusMonths(3).plusDays(2).toString(), birthDate = b.dob, notes = "HBYC month 3: weight checked, feeding counselled.", completed = true, reviewed = true, notesReviewedForDanger = true, demo = true)
        }
        // 8 children 1–5 years (share households with infants)
        (0 until 8).forEach { i ->
            val dob = today.minusMonths(14L + i * 6)
            add(Beneficiary(name = "Child ${listOf("Rohan", "Nisha", "Aman", "Pinki", "Sonu", "Tara", "Golu", "Mini")[i]} (demo)", household = "H-%02d Family %d".format(15 + i % 10, i % 10 + 1),
                village = villages[i % 2], category = BenCategory.CHILD, sex = if (i % 2 == 0) "M" else "F", dob = dob.toString(), deliveryPlace = "INSTITUTION",
                mcp = McpCard(vaccines = ArogyaSchedule.VACCINE_CONTACTS.filter { !it.due(dob).isAfter(today.minusMonths(1)) && it.label.contains("16–24").not() || i % 2 == 0 }
                    .filter { !it.due(dob).isAfter(today) }.flatMap { c -> c.codes.map { it to c.due(dob).toString() } }.toMap())))
        }
        // 10 eligible couples
        (0 until 10).forEach { i ->
            add(Beneficiary(name = "${listOf("Rani", "Seema", "Kiran", "Usha", "Nirmala", "Shanti", "Babli", "Mamta", "Suman", "Radha")[i]} (EC, demo)",
                relativeName = "Husband EC${i + 1}", household = house("EC family ${i + 1}"), village = villages[i % 2], category = BenCategory.ELIGIBLE_COUPLE, sex = "F",
                familyPlanning = listOf("Condom", "OCP", "None", "IUCD", "Injectable (Antara)", "None", "Condom", "OCP", "Sterilisation", "None")[i]))
        }
        // 2 others
        add(Beneficiary(name = "Ramesh (demo) — cough >2 weeks", household = "H-03 Pooja's family", village = villages[0], category = BenCategory.OTHER, sex = "M", notes = "TB suspect, sputum test advised"))
        add(Beneficiary(name = "Kamla (demo) — elderly", household = "H-05 Kavita's family", village = villages[0], category = BenCategory.OTHER, sex = "F"))
        // Two unfinished visits to continue
        bens.filter { it.category == BenCategory.CHILD && it.dob == today.minusDays(2).toString() }.forEach { b ->
            visits += ArogyaVisit(id = "demo-draft-${b.id}", personId = b.id, name = b.name, village = b.village, kind = ArogyaKind.NEWBORN, date = today.toString(), birthDate = b.dob, demo = true)
        }
        bens.firstOrNull { it.name.startsWith("Baby of Komal") }?.let { b ->
            visits += ArogyaVisit(id = "demo-draft-${b.id}", personId = b.id, name = b.name, village = b.village, kind = ArogyaKind.NEWBORN, date = today.toString(), birthDate = b.dob,
                notes = "Baby looks less active today. Temperature 38.4 C. Feeding poorly since morning.", demo = true)
        }
        val ids = bens.map { it.id }
        val diary = listOf(
            DiaryEntry("demo-d1", today.minusMonths(2).toString(), DiaryKind.SURVEY, "", "Household line listing updated (demo)"),
            DiaryEntry("demo-d2", today.withDayOfMonth(minOf(5, today.dayOfMonth)).toString(), DiaryKind.VHND, "", "VHND at Anganwadi (demo)"),
            DiaryEntry("demo-d3", today.minusMonths(1).withDayOfMonth(10).toString(), DiaryKind.PHC_MEETING, "", "Monthly PHC meeting (demo)"),
            DiaryEntry("demo-d4", today.withDayOfMonth(minOf(3, today.dayOfMonth)).toString(), DiaryKind.VHSNC_MEETING, "", "VHSNC meeting (demo)"),
            DiaryEntry("demo-d5", today.minusDays(4).toString(), DiaryKind.ESCORT, ids[3], "Escorted to PHC for ANC (demo)"))
        val stock = listOf(
            StockTxn("demo-s1", today.minusMonths(13).toString(), "ORS", 50, "", "Received from sub-centre"), StockTxn("demo-s2", today.minusMonths(13).toString(), "IFA tablets (adult)", 500, "", "Received"),
            StockTxn("demo-s3", today.minusMonths(13).toString(), "Zinc tablets", 60, "", "Received"), StockTxn("demo-s4", today.minusMonths(13).toString(), "Condoms", 100, "", "Received"),
            StockTxn("demo-s5", today.minusMonths(13).toString(), "Sanitary napkins", 40, "", "Received"), StockTxn("demo-s10", today.minusMonths(13).toString(), "IFA syrup (child)", 10, "", "Received"))
        val vitals = nbs.mapIndexed { i, nb -> VitalEvent("demo-e$i", today.minusDays(nb.days).toString(), VitalKind.BIRTH, "", if (nb.home) "Home" else "PHC", nb.name + " (demo)") } + listOf(
            VitalEvent("demo-e10", today.minusDays(10).toString(), VitalKind.DIARRHOEA, bens.firstOrNull { it.category == BenCategory.CHILD && ArogyaOfficialForms.formFor(it, emptyList(), today).first.id == "sick" }?.id.orEmpty(), "Home", "ORS + zinc given (demo)"),
            VitalEvent("demo-e11", today.minusDays(8).toString(), VitalKind.AEFI, ids[25], "Home", "Mild fever after Penta, resolved (demo)"),
            VitalEvent("demo-e12", today.minusDays(5).toString(), VitalKind.TB_SUSPECT, ids.last(), "Home", "Referred for sputum test (demo)"))
        val (reg, allVisits) = ArogyaOfficialForms.seedDemo(RegistryData(bens, diary, stock, vitals), visits, today)
        return Sample(reg, allVisits)
    }
}


/** A filled official form: title, source document, columns, rows. Rendered on screen and as PDF. */
data class FormTable(val title: String, val source: String, val headers: List<String>, val rows: List<List<String>>, val note: String = "")

/**
 * Official formats, filled from the registers. Layouts follow:
 *  - RI Form 3 House-to-House Survey and RI Form 4 Pregnant Women listing (Immunization Handbook for Health Workers 2018, Unit 4)
 *  - Annexure 9 Home Visit Form — Examination of Mother and Newborn, and Annexure 5 Drug Kit Stock Card (Handbook for ASHA Facilitators)
 *  - Mother and Child Protection Card (MoHFW / MWCD, revised 2018) section order
 */
object ArogyaForms {
    private fun dd(s: String) = runCatching { LocalDate.parse(s).format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yy")) }.getOrDefault(s)

    fun riForm3(bens: List<Beneficiary>, today: LocalDate): FormTable {
        val rows = ArogyaRegistryLogic.byHousehold(bens).entries.sortedBy { it.key }.map { (h, people) ->
            fun child(min: Long, max: Long) = people.any { b -> b.category == BenCategory.CHILD && ArogyaRegistryLogic.ageDays(b, today)?.let { it in min until max } == true }
            val head = people.firstOrNull { it.category != BenCategory.CHILD }
            listOf(h.substringBefore(" ").ifBlank { h }, h.substringAfter(" ", h), head?.relativeName.orEmpty(), people.size.toString(),
                if (people.any { it.category == BenCategory.PREGNANT && it.deliveryDate.isBlank() }) "Yes" else "No",
                if (child(0, 31)) "Yes" else "No", if (child(31, 365)) "Yes" else "No", if (child(365, 730)) "Yes" else "No")
        }
        return FormTable("House to House Survey Form — RI Form 3", "Immunization Handbook for Health Workers (2018), Unit 4",
            listOf("A House No.", "B Head of household", "C Father's / husband's name", "D Total members", "E Pregnant woman", "F Newborn (<1 month)", "G Child 1 month–1 year", "H Child 1–2 years"), rows,
            "D counts people entered in the app for this household. Signature of ASHA / verified by ASHA Facilitator / verified by ANM on paper.")
    }

    fun riForm4(bens: List<Beneficiary>, today: LocalDate): FormTable {
        val rows = bens.filter { it.active && it.category == BenCategory.PREGNANT && it.deliveryDate.isBlank() }.sortedBy { it.household }.map { b ->
            listOf(b.household.substringBefore(" "), b.name, b.age, b.relativeName, b.phone, "Yes", "${ArogyaRegistryLogic.edd(b)?.toString()?.let(::dd).orEmpty()} / ${dd(b.lmp)}",
                b.mcp.tdDates.getOrNull(0)?.let(::dd).orEmpty(), b.mcp.tdDates.getOrNull(1)?.let(::dd).orEmpty(), b.mcp.tdDates.getOrNull(2)?.let(::dd).orEmpty(),
                b.mcp.anc[1]?.let(::dd).orEmpty(), b.mcp.anc[2]?.let(::dd).orEmpty(), b.mcp.anc[3]?.let(::dd).orEmpty(), b.mcp.anc[4]?.let(::dd).orEmpty())
        }
        return FormTable("Pregnant Women Survey Listing — RI Form 4", "Immunization Handbook for Health Workers (2018), Unit 4",
            listOf("House No.", "Name of the pregnant woman", "Age", "Husband's name", "Mobile", "MCP card", "EDD / LMP", "TT/Td-1", "TT/Td-2", "TT-Booster", "1st ANC", "2nd ANC", "3rd ANC", "4th ANC"), rows)
    }

    /** Annexure 9: the visit day columns are filled from completed visits; measurements from the visit notes. */
    fun homeVisitForm(child: Beneficiary, visits: List<ArogyaVisit>): FormTable {
        val days = listOf(1L, 3L, 7L, 14L, 21L, 28L, 42L)
        val base = runCatching { LocalDate.parse(child.sncuDischarge.ifBlank { child.dob }) }.getOrNull()
        val items = ArogyaSchedule.forBeneficiary(child, visits, LocalDate.now()).filter { it.kind == "HBNC" }
        val byDay = days.associateWith { d -> base?.let { b -> items.firstOrNull { it.due == b.plusDays(d - 1) && it.done } } }
        val visitByDate = visits.filter { it.personId == child.id && it.completed }.associateBy { it.date }
        fun cell(d: Long, f: (ArogyaVisit) -> String) = byDay[d]?.let { visitByDate[it.doneDate] }?.let(f).orEmpty()
        fun measure(v: ArogyaVisit, key: String) = ArogyaFinishVisit.ruleFallback(v.notes).firstOrNull { it.key == key }?.value.orEmpty()
        fun yn(v: ArogyaVisit, rx: Regex) = if (rx.containsMatchIn(v.notes)) "Yes" else "No"
        val rows = listOf(
            listOf("Date of ASHA's visit") + days.map { d -> byDay[d]?.doneDate?.let(::dd) ?: if (d == 1L && child.deliveryPlace != "HOME") "n/a" else "" },
            listOf("Weight (day 7, 14, 21, 28, 42) kg") + days.map { d -> if (d < 7) "" else cell(d) { measure(it, "weight_kg") } },
            listOf("Temperature (°C)") + days.map { d -> cell(d) { measure(it, "temperature_c") } },
            listOf("Baby fed properly (7–8 times / 24 h)") + days.map { d -> cell(d) { yn(it, Regex("(?i)feeding well|breastfed|feeds well")) } },
            listOf("Feeding less / stopped") + days.map { d -> cell(d) { yn(it, Regex("(?i)feeding poorly|not feeding|feeding less|stopped feeding")) } },
            listOf("Cold to touch or temp >37.2 °C (99 °F)") + days.map { d -> cell(d) { v -> measure(v, "temperature_c").toDoubleOrNull()?.let { if (it > 37.2) "Yes" else "No" } ?: "" } },
            listOf("Pus on umbilicus") + days.map { d -> cell(d) { yn(it, Regex("(?i)pus on (the )?(umbilicus|cord)|cord (is )?(red|pus)")) } },
            listOf("Yellowness in eyes or skin (jaundice)") + days.map { d -> cell(d) { yn(it, Regex("(?i)jaundice|yellow")) } },
            listOf("All limbs limp / cry weak") + days.map { d -> cell(d) { yn(it, Regex("(?i)limp|weak cry|cry weak|less active|lethargic")) } },
            listOf("Chest indrawing") + days.map { d -> cell(d) { yn(it, Regex("(?i)chest indrawing")) } })
        return FormTable("Home Visit Form — Examination of Mother and Newborn (Annexure 9) · ${child.name}", "Handbook for ASHA Facilitators, Annexure 9",
            listOf("Ask / Examine", "Day 1", "Day 3", "Day 7", "Day 14", "Day 21", "Day 28", "Day 42"), rows,
            "Filled from the ASHA's completed visits in the app (blank = not recorded). Mother's examination rows are on the paper form." +
                if (child.sncuDischarge.isNotBlank()) " Days counted from SNCU discharge (${dd(child.sncuDischarge)})." else "")
    }

    /** Annexure 5: for each refill date, the balance left before the refill and the refill given. */
    fun drugKitCard(stock: List<StockTxn>): FormTable {
        val refills = stock.filter { it.qty > 0 }.map { it.date }.distinct().sorted().take(6)
        val items = (ArogyaRegistryLogic.DRUG_KIT + stock.map { it.item }).distinct().filter { i -> stock.any { it.item == i } }
        val rows = items.mapIndexed { i, item ->
            listOf("${i + 1}", item) + refills.flatMap { date ->
                val before = stock.filter { it.item == item && it.date < date }.sumOf { it.qty }
                val given = stock.filter { it.item == item && it.date == date && it.qty > 0 }.sumOf { it.qty }
                listOf("$before", if (given > 0) "$given" else "")
            } + listOf("${stock.filter { it.item == item }.sumOf { it.qty }}")
        }
        return FormTable("Drug Kit Stock Card (Annexure 5)", "Handbook for ASHA Facilitators, Annexure 5",
            listOf("S. No.", "Name of drug") + refills.flatMap { listOf("Balance ${dd(it)}", "Refill given ${dd(it)}") } + listOf("Balance now"), rows,
            "Balance: what was left in the kit at the time of refill. Refill: what was put into the kit. Card is updated by the person providing the refill.")
    }

    /** MCP card in the official section order; each section is a list of "label: value" lines. */
    fun mcpCard(b: Beneficiary, all: List<Beneficiary>, visits: List<ArogyaVisit>, today: LocalDate): List<Pair<String, List<String>>> {
        val out = ArrayList<Pair<String, List<String>>>()
        val mother = if (b.category == BenCategory.CHILD) all.firstOrNull { it.household == b.household && it.household.isNotBlank() && it.category != BenCategory.CHILD } else b
        out += "1. Mother and family identification" to listOf("Mother's name: ${mother?.name ?: b.relativeName}", "Husband's / father's name: ${mother?.relativeName ?: ""}",
            "Age: ${mother?.age.orEmpty()}", "Address: ${b.household}, ${b.village}", "Mobile: ${mother?.phone ?: b.phone}", "MCTS/RCH ID: ${b.rchId.ifBlank { "—" }}") +
            (if (b.category == BenCategory.PREGNANT) listOf("Date of LMP: ${dd(b.lmp)}", "Expected date of delivery: ${ArogyaRegistryLogic.edd(b)?.toString()?.let(::dd).orEmpty()}") else emptyList())
        out += "2. Institutional identification and referral" to listOf("ASHA / ANM / AWW: see Setup", "Ambulance toll-free: 108 / 102",
            "High risk: " + if (ArogyaRegistryLogic.isHighRisk(b) || b.mcp.ancDetails.values.any { it.bp.substringBefore('/').toIntOrNull()?.let { s -> s >= 140 } == true || (it.hb.toDoubleOrNull() ?: 99.0) < 10.0 }) "YES" else "No")
        if (b.category == BenCategory.PREGNANT) {
            val lmp = runCatching { LocalDate.parse(b.lmp) }.getOrNull()
            out += "3. Pregnancy record and antenatal care" to (1..4).map { n ->
                val date = b.mcp.anc[n]; val d = b.mcp.ancDetails[n]
                val pog = if (date != null && lmp != null) java.time.temporal.ChronoUnit.WEEKS.between(lmp, LocalDate.parse(date)).toString() else ""
                "ANC $n — date: ${date?.let(::dd) ?: "—"} · POG (weeks): ${pog.ifBlank { "—" }} · weight: ${d?.weightKg?.ifBlank { null } ?: "—"} kg · BP: ${d?.bp?.ifBlank { null } ?: "—"} · Hb: ${d?.hb?.ifBlank { null } ?: "—"}"
            }
            out += "4. Care during pregnancy (Td, IFA, calcium)" to listOf("TT/Td-1: ${b.mcp.tdDates.getOrNull(0)?.let(::dd) ?: "—"}", "TT/Td-2: ${b.mcp.tdDates.getOrNull(1)?.let(::dd) ?: "—"}", "Iron tablets given: ${b.mcp.ifaTablets} (at least 180)")
        }
        if (b.category == BenCategory.CHILD) {
            out += "6. Delivery and birth record" to listOf("Date of delivery: ${dd(b.dob)}", "Term / preterm: ${if (b.preterm) "Preterm" else "Term"}", "Place of delivery: ${if (b.deliveryPlace == "HOME") "Home" else "Institution"}",
                "Sex of baby: ${b.sex}", "Weight of baby: ${b.birthWeightKg?.let { "$it kg" } ?: "—"}" + if ((b.birthWeightKg ?: 9.0) < 2.5) " (LBW: extra visits)" else "")
            out += "7. Child details" to listOf("Child's name: ${b.name}", "Date of birth: ${dd(b.dob)}", "Birth weight: ${b.birthWeightKg ?: "—"}", "MCTS/RCH ID (child): ${b.rchId.ifBlank { "—" }}")
            val hb = ArogyaSchedule.forBeneficiary(b, visits, today).filter { it.kind == "HBNC" }
            out += "8–9. Postnatal care (home visits)" to hb.map { "${it.label}: " + if (it.done) "done ${dd(it.doneDate)}" else if (it.overdue(today)) "missed" else "due ${dd(it.due.toString())}" }
            val dob = runCatching { LocalDate.parse(b.dob) }.getOrNull()
            val ages = listOf("Birth", "1½ months", "2½ months", "3½ months", "9 months", "16–24 months", "5–6 years")
            out += "10. Child immunization (date given / next date)" to ArogyaSchedule.VACCINE_CONTACTS.zip(ages).flatMap { (c, age) ->
                c.codes.map { code -> "${ArogyaSchedule.VACCINE_NAMES[code] ?: code} ($age): " + (b.mcp.vaccines[code]?.let { "given ${dd(it)}" } ?: dob?.let { "next ${dd(c.due(it).toString())}" } ?: "—") }
            }
            out += "16. Growth monitoring (weight for age)" to b.mcp.weights.sortedBy { it.date }.map { "${dd(it.date)}: ${it.kg} kg" }.ifEmpty { listOf("No weights recorded") }
        }
        return out
    }
}
