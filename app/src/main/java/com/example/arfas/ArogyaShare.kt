package com.example.arfas

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.os.Environment
import android.content.ContentValues
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Sharing and ANM escalation. Nothing is sent automatically: every share opens Android's own
 * chooser / dialer / SMS screen, and the worker presses Send there.
 */
object ArogyaShare {
    private const val PREFS = "arogya_share"
    private val time = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())

    fun anmPhone(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("anm_phone", "").orEmpty()
    fun setAnmPhone(context: Context, phone: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("anm_phone", phone.filter { it.isDigit() || it == '+' }.take(15)).apply()
    }
    fun anmName(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("anm_name", "").orEmpty()
    fun setAnmName(context: Context, name: String) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("anm_name", name.take(60)).apply() }

    /** Age in days from the visit's birth date, if known. */
    fun ageText(v: ArogyaVisit): String {
        val b = ArogyaWorkflow.parseDate(v.birthDate) ?: return ""
        val d = ArogyaWorkflow.parseDate(v.date) ?: return ""
        val days = java.time.temporal.ChronoUnit.DAYS.between(b, d)
        return when { days < 0 -> ""; days <= 59 -> "$days days old"; else -> "${days / 30} months old" }
    }

    /** One guide question as text: question, the guide's own sentence, the answer, and sources with page and link. */
    fun guideEntryText(e: ArogyaGuideEntry, n: Int = 0, withPassages: Boolean = true): String = buildString {
        appendLine((if (n > 0) "Q$n. " else "Q. ") + e.question + "   (" + time.format(Date(e.at)) + ")")
        if (e.warning.isNotBlank()) appendLine("Danger-sign alert shown: " + e.warning.lineSequence().first())
        if (e.quote.isNotBlank()) appendLine("The guide says: “${e.quote}”" + if (e.quoteSource.isNotBlank()) " — ${e.quoteSource}" else "")
        appendLine("Answer: " + ArogyaMarkdown.strip(e.answer))
        if (e.sources.isNotEmpty()) {
            appendLine("Sources:")
            e.sources.forEach { s ->
                appendLine("  [${s.id}] ${s.title}" + (if (s.locator.isNotBlank()) ", ${s.locator}" else ""))
                if (s.url.isNotBlank()) appendLine("      ${s.url}")
                if (withPassages) {
                    val lines = s.highlights.ifEmpty { listOf(s.excerpt.take(400)) }.filter { it.isNotBlank() }
                    lines.take(3).forEach { appendLine("      › ${it.take(400)}") }
                }
            }
        }
        if (e.provider.isNotBlank()) appendLine("(${e.provider}, ${e.elapsedMs / 1000}s. Guidance, not a diagnosis.)")
    }.trimEnd()

    /** Short referral message for the ANM: who, where, what was found, what was done. Fits one SMS screen. */
    fun anmMessage(v: ArogyaVisit, concern: String = ""): String = buildString {
        val danger = ArogyaDangerSigns.evaluate((concern + "\n" + v.notes).trim(), v)
        val signs = danger.findings.joinToString(", ") { it.sign.en }
        appendLine("ASHA referral — please advise")
        appendLine("${v.name.ifBlank { "Beneficiary" }}, ${v.village.ifBlank { "village not recorded" }}")
        appendLine(listOf(v.kind.title, ageText(v)).filter { it.isNotBlank() }.joinToString(", "))
        if (signs.isNotBlank()) appendLine("Danger sign: $signs" + if (danger.urgent) " (URGENT)" else "")
        if (concern.isNotBlank()) appendLine("Concern: ${concern.take(200)}")
        val obs = if (v.captureItems.isNotEmpty()) v.captureItems.joinToString("; ") { it.quote } else v.notes
        if (obs.isNotBlank()) appendLine("Observed: ${obs.replace(Regex("\\s+"), " ").take(300)}")
        if (v.referralNote.isNotBlank()) appendLine("Action so far: ${v.referralNote.take(200)}")
        append("Visit date ${v.date}. Sent by ASHA via ArogyaDoot.")
    }

    /** Full visit summary for sharing (WhatsApp / email / Drive): record + every guide question with sources. */
    fun visitText(v: ArogyaVisit): String = ArogyaWorkflow.report(v)

    fun shareText(context: Context, subject: String, text: String): Boolean = runCatching {
        val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, subject); putExtra(Intent.EXTRA_TEXT, text) }
        context.startActivity(Intent.createChooser(send, subject).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    }.getOrDefault(false)

    fun dial(context: Context, number: String): Boolean = runCatching {
        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + number.filter { it.isDigit() || it == '+' })).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    }.getOrDefault(false)

    fun sms(context: Context, number: String, text: String): Boolean = runCatching {
        context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + number.filter { it.isDigit() || it == '+' }))
            .putExtra("sms_body", text).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    }.getOrDefault(false)

    /** WhatsApp chat with the ANM's number, message pre-filled (worker presses Send). Falls back to the share sheet. */
    fun whatsApp(context: Context, number: String, text: String): Boolean {
        val digits = number.filter { it.isDigit() }.let { if (it.length == 10) "91$it" else it }
        val direct = runCatching {
            val i = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; setPackage("com.whatsapp"); putExtra(Intent.EXTRA_TEXT, text)
                if (digits.isNotBlank()) putExtra("jid", "$digits@s.whatsapp.net") }
            context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
        }.getOrDefault(false)
        return direct || shareText(context, "Message to ANM", text)
    }

    /** Saves the visit report as a PDF in Downloads/ArogyaDoot and opens the share sheet for it. */
    suspend fun shareVisitPdf(context: Context, v: ArogyaVisit): Boolean = runCatching {
        val name = "ArogyaDoot-${v.name.ifBlank { "visit" }.replace(Regex("[^A-Za-z0-9]+"), "_").take(30)}-${v.date}-${System.currentTimeMillis() % 100000}.pdf"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ArogyaDoot")
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("no destination")
        ArogyaVisitMedia.exportPdf(context, uri, ArogyaWorkflow.report(v))
        val send = Intent(Intent.ACTION_SEND).apply { type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        context.startActivity(Intent.createChooser(send, "Share visit PDF").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    }.getOrDefault(false)

    /** Saves the AI table as a monospace-table PDF in Downloads/ArogyaDoot and opens the share sheet for it. */
    suspend fun shareAiTablePdf(context: Context, table: String): Boolean = runCatching {
        val name = "ArogyaDoot-AI-table-${System.currentTimeMillis() % 100000}.pdf"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ArogyaDoot")
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("no destination")
        ArogyaVisitMedia.exportPdf(context, uri, table, mono = true)
        val send = Intent(Intent.ACTION_SEND).apply { type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        context.startActivity(Intent.createChooser(send, "Share AI table PDF").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    }.getOrDefault(false)
}
