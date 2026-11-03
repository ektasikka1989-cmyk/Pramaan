package com.example.arfas

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Part B5: the fixed ANM message template, built only from the records (EN + HI).
 * AI only polishes the wording afterwards ([ArogyaVisitAi.polishAnmMessage]); every
 * number and danger sign must survive polishing or the template is shown instead.
 * A line is omitted only when there is no data — never "n/a", never truncated.
 * Pure Kotlin, host-testable. The same header powers the danger referral, the
 * low-stock refill request and the visit handover share.
 */
object ArogyaAnmTemplate {

    data class Input(
        val visit: ArogyaVisit,
        val person: Beneficiary? = null,
        val prevVisit: ArogyaVisit? = null,
        val concern: String = "",
        val ashaName: String = "", val ashaVillage: String = "", val ashaPhone: String = "",
        val anmName: String = "",
        val need: String = "",
        /** Medicines given this visit, already formatted ("ORS x4"). */
        val givenMeds: List<String> = emptyList(),
        /** Sent time; defaults to the visit date + now. Tests pass it explicitly. */
        val dateTime: String = "",
        val hindi: Boolean = false
    )

    private fun tr(hindi: Boolean, en: String, hi: String) = if (hindi) hi else en

    /** The shared To/From/Date header for the referral, refill and handover sends. */
    fun header(title: String, to: String, from: String, dateTime: String, hindi: Boolean): String {
        val t = if (hindi) "प्रति" else "To"
        val f = if (hindi) "प्रेषक" else "From"
        val d = if (hindi) "दिनांक/समय" else "Date/time"
        return "$title\n$t: $to   $f: $from   $d: $dateTime"
    }

    fun fromLine(ashaName: String, ashaVillage: String, ashaPhone: String, hindi: Boolean): String {
        val who = listOf(ashaName, ashaVillage, ashaPhone).filter { it.isNotBlank() }.joinToString(", ")
        return if (who.isBlank()) "ASHA via ArogyaDoot" else "ASHA $who"
    }

    /** Low-stock refill request: the same header plus the refill lines (balance + minimum). */
    fun refillMessage(ashaName: String, ashaVillage: String, ashaPhone: String, anmName: String,
                      dateTime: String, body: String, hindi: Boolean): String {
        val title = tr(hindi, "💊 Drug kit refill request — ArogyaDoot", "💊 दवा किट रिफिल का अनुरोध — ArogyaDoot")
        return header(title, anmName.ifBlank { tr(hindi, "ANM", "ANM") },
            fromLine(ashaName, ashaVillage, ashaPhone, hindi), dateTime, hindi) + "\n" + body
    }

    private fun ageText(dob: String, on: LocalDate): String {
        val b = runCatching { ArogyaWorkflow.parseDate(dob) }.getOrNull() ?: return ""
        val days = ChronoUnit.DAYS.between(b, on)
        if (days < 0) return ""
        return when {
            days <= 59 -> "$days days old"
            days < 365 * 2 -> "${days / 30} months old"
            else -> "${days / 365} years old"
        }
    }

    private fun descriptor(p: Beneficiary?, visit: ArogyaVisit, on: LocalDate, hindi: Boolean): String {
        if (p == null) return ""
        val lmp = runCatching { ArogyaWorkflow.parseDate(p.lmp) }.getOrNull()
        if (p.category == BenCategory.PREGNANT && lmp != null) {
            val w = ChronoUnit.WEEKS.between(lmp, on).coerceAtLeast(0)
            val edd = lmp.plusDays(280).toString()
            return if (hindi) "गर्भवती $w सप्ताह (LMP ${p.lmp}, EDD $edd)" else "pregnant $w wk (LMP ${p.lmp}, EDD $edd)"
        }
        val dob = visit.birthDate.ifBlank { p.dob }
        val b = runCatching { ArogyaWorkflow.parseDate(dob) }.getOrNull()
        if (b != null) {
            val days = ChronoUnit.DAYS.between(b, on)
            if (days in 0..28) return if (hindi) "नवजात, दिन ${days + 1}" else "newborn day ${days + 1}"
            if (days in 29..(365 * 6)) {
                val m = days / 30
                return if (m < 24) (if (hindi) "बच्चा $m माह" else "child $m m")
                else (if (hindi) "बच्चा ${days / 365} वर्ष" else "child ${days / 365} y")
            }
        }
        val del = runCatching { ArogyaWorkflow.parseDate(p.deliveryDate) }.getOrNull()
        if (del != null) {
            val n = ChronoUnit.DAYS.between(del, on).coerceAtLeast(0)
            return if (hindi) "माँ, प्रसव के $n दिन बाद" else "mother $n days after delivery"
        }
        return ""
    }

    /** Danger-sign names in the template, in the message language, for polish verification. */
    fun signNames(text: String, visit: ArogyaVisit, hindi: Boolean): List<String> =
        ArogyaDangerSigns.evaluate(text, visit).findings.map { if (hindi) it.sign.hi else it.sign.en }

    fun build(i: Input): String {
        val h = i.hindi
        val v = i.visit
        val p = i.person
        val on = runCatching { ArogyaWorkflow.parseDate(v.date) }.getOrNull() ?: LocalDate.now()
        val danger = ArogyaDangerSigns.evaluate((i.concern + "\n" + v.notes).trim(), v)
        val out = StringBuilder()
        val title = if (danger.urgent) tr(h, "🚨 URGENT referral — ArogyaDoot", "🚨 तुरंत रेफ़रल — ArogyaDoot")
        else tr(h, "📋 Advice needed — ArogyaDoot", "📋 सलाह चाहिए — ArogyaDoot")
        val dt = i.dateTime.ifBlank { "${v.date}, ${java.time.LocalTime.now().withNano(0).withSecond(0)}" }
        out.appendLine(header(title, i.anmName.ifBlank { "ANM" }, fromLine(i.ashaName, i.ashaVillage, i.ashaPhone, h), dt, h))
        // PERSON
        val name = v.name.ifBlank { p?.name.orEmpty() }.ifBlank { tr(h, "Beneficiary", "लाभार्थी") }
        val dob = v.birthDate.ifBlank { p?.dob.orEmpty() }
        val personBits = listOf(name, ageText(dob, on).ifBlank { dob }, p?.sex.orEmpty(), descriptor(p, v, on, h))
            .filter { it.isNotBlank() }
        out.appendLine(tr(h, "PERSON: ", "व्यक्ति: ") + personBits.joinToString(", "))
        // Household / Mother-Husband / Family phone
        if (p != null) {
            if (p.household.isNotBlank()) out.appendLine(tr(h, "Household: ", "घर: ") + p.household)
            if (p.relativeName.isNotBlank()) out.appendLine(tr(h, "Mother-Husband: ", "माँ-पति: ") + p.relativeName)
            if (p.phone.isNotBlank()) out.appendLine(tr(h, "Family phone: ", "परिवार का फ़ोन: ") + p.phone)
        }
        // WHY
        val level = { l: ArogyaDangerSigns.Level ->
            if (!h) " (${l.name.lowercase()})" else if (l == ArogyaDangerSigns.Level.URGENT) " (तुरंत)" else " (जाँचें)"
        }
        val whyBits = danger.findings.map { (if (h) it.sign.hi else it.sign.en) + level(it.level) } +
            (if (i.concern.isNotBlank()) listOf(tr(h, "ASHA's concern: ", "आशा की चिंता: ") + i.concern) else emptyList())
        if (whyBits.isNotEmpty()) out.appendLine(tr(h, "WHY I AM WRITING: ", "लिखने का कारण: ") + whyBits.joinToString("; "))
        // SEEN TODAY
        val seen = if (v.captureItems.isNotEmpty()) v.captureItems.joinToString("; ") { it.quote } else v.notes
        if (seen.isNotBlank()) out.appendLine(tr(h, "SEEN TODAY: ", "आज देखा: ") + seen)
        val concerns = v.answers.filter { it.value == ArogyaAnswer.CONCERN }.keys.toList()
        if (concerns.isNotEmpty()) out.appendLine(tr(h, "Checklist concern: ", "जाँच-सूची में चिंता: ") + concerns.joinToString(", "))
        val weights = p?.mcp?.weights.orEmpty()
        if (weights.size >= 2) {
            val sorted = weights.sortedBy { it.date }
            val note = ArogyaOfficialForms.weightNote(
                mapOf("weight" to ("${sorted[sorted.size - 2].kg}" to sorted[sorted.size - 2].date)),
                mapOf("weight" to "${sorted.last().kg}"))
            if (!note.isNullOrBlank()) out.appendLine((if (h) "वजन: " else "") + note)
        }
        // HISTORY
        val hist = ArrayList<String>()
        i.prevVisit?.let { pv ->
            val vals = if (pv.captureItems.isNotEmpty()) pv.captureItems.joinToString("; ") { it.quote } else pv.notes
            if (vals.isNotBlank()) hist += tr(h, "Last visit ${pv.date}: ", "पिछली विज़िट ${pv.date}: ") + vals
        }
        if (p != null) {
            if (p.birthWeightKg != null) hist += tr(h, "Birth weight ${p.birthWeightKg} kg", "जन्म का वजन ${p.birthWeightKg} किलो") +
                (if (p.birthWeightKg < 2.5) tr(h, " (LBW)", " (कम वजन)") else "")
            if (p.preterm) hist += tr(h, "Preterm", "समय से पहले जन्म")
            if (p.sncuDischarge.isNotBlank()) hist += tr(h, "SNCU discharge ${p.sncuDischarge}", "SNCU छुट्टी ${p.sncuDischarge}")
            if (p.mcp.anc.isNotEmpty()) hist += tr(h, "ANC done: ", "ANC हो गई: ") +
                p.mcp.anc.toSortedMap().map { (n, d) -> "ANC $n ($d)" }.joinToString(", ")
            if (p.mcp.tdDates.isNotEmpty()) hist += tr(h, "Td: ${p.mcp.tdDates.size} doses", "Td: ${p.mcp.tdDates.size} खुराक")
            if (p.mcp.ifaTablets > 0) hist += tr(h, "IFA: ${p.mcp.ifaTablets} tablets", "IFA: ${p.mcp.ifaTablets} गोलियाँ")
            if (p.mcp.vaccines.isNotEmpty()) hist += tr(h, "Vaccines given: ", "टीके लगे: ") +
                p.mcp.vaccines.map { (c, d) -> "$c ($d)" }.joinToString(", ")
            val due = runCatching {
                ArogyaSchedule.forBeneficiary(p, emptyList(), on)
                    .filter { it.kind == "VACCINE" && !it.done && (it.status(on) == DueStatus.OVERDUE || it.status(on) == DueStatus.DUE_NOW) }
            }.getOrDefault(emptyList())
            if (due.isNotEmpty()) hist += tr(h, "Vaccines due/overdue: ", "टीके बाकी: ") +
                due.joinToString(", ") { "${it.label} (due ${it.due})" }
        }
        if (hist.isNotEmpty()) out.appendLine(tr(h, "HISTORY: ", "पुराना हाल: ") + hist.joinToString("; "))
        // WHAT I HAVE DONE
        val done = i.givenMeds.filter { it.isNotBlank() } + (if (v.referralNote.isNotBlank()) listOf(v.referralNote) else emptyList())
        if (done.isNotEmpty()) out.appendLine(tr(h, "WHAT I HAVE DONE: ", "मैंने क्या किया: ") + done.joinToString("; "))
        // GUIDELINE
        val g = v.guideLog.maxByOrNull { it.at }
        if (g != null) {
            val s = g.sources.firstOrNull()
            val doc = if (s != null) s.title + (if (s.locator.isNotBlank()) ", ${s.locator}" else "") else ""
            val line = (if (g.quote.isNotBlank()) "\"${g.quote}\"" else "") +
                (if (doc.isNotBlank()) " — $doc" else "")
            if (line.isNotBlank()) out.appendLine(tr(h, "GUIDELINE: ", "गाइडलाइन: ") + line)
        }
        // WHAT I NEED
        val need = i.need.ifBlank {
            if (danger.urgent) tr(h, "Please visit today.", "कृपया आज ही आएँ।") else tr(h, "Please advise.", "कृपया सलाह दें।")
        }
        out.appendLine(tr(h, "WHAT I NEED FROM YOU: ", "आपसे क्या चाहिए: ") + need)
        // Next visit
        val next = listOf(v.nextDate, v.nextReason).filter { it.isNotBlank() }.joinToString(" — ")
        if (next.isNotBlank()) out.appendLine(tr(h, "Next visit planned: ", "अगली विज़िट: ") + next)
        return out.toString().trimEnd()
    }

    /**
     * The cloud never sees identifiers: [values] (names, villages, phones, household) become
     * numbered [Pn] tokens before the polish prompt, restored afterwards. Returns the
     * tokenized text and the values in token order for [detokenize].
     */
    fun tokenize(text: String, values: List<String>): Pair<String, List<String>> {
        var out = text
        val used = ArrayList<String>()
        values.filter { it.isNotBlank() && it.length >= 2 }.distinct().sortedByDescending { it.length }.forEach { v ->
            val tok = "[P${used.size + 1}]"
            val replaced = out.replace(Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(v) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE), tok)
            if (replaced != out) { out = replaced; used += v }
        }
        return out to used
    }

    fun detokenize(text: String, used: List<String>): String {
        var out = text
        used.forEachIndexed { i, v -> out = out.replace("[P${i + 1}]", v) }
        return out
    }

    /**
     * Polish check: every number and danger-sign name in [template] must survive in
     * [polished], and every [tokens] placeholder must be kept exactly. Null = pass,
     * otherwise the reason the template must be shown instead.
     */
    fun verifyPolished(template: String, polished: String, signs: List<String>, tokens: List<String> = emptyList()): String? {
        val missingNumbers = ArogyaAiExtras.numbers(template) - ArogyaAiExtras.numbers(polished)
        if (missingNumbers.isNotEmpty()) return "dropped numbers: ${missingNumbers.sorted().joinToString(", ")}"
        val low = polished.lowercase(java.util.Locale.ROOT)
        val missingSigns = signs.filter { it.isNotBlank() && it.lowercase(java.util.Locale.ROOT) !in low }
        if (missingSigns.isNotEmpty()) return "dropped danger sign: ${missingSigns.joinToString(", ")}"
        val missingTokens = tokens.filter { it !in polished }
        if (missingTokens.isNotEmpty()) return "dropped placeholder: ${missingTokens.joinToString(", ")}"
        return null
    }
}
