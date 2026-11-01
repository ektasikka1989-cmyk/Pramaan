package com.example.arfas

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Local-only PDF renderer and Android Sharesheet bridge. No network or third-party SDK. */
object SessionReportExporter {
    private const val TAG = "SessionReportExporter"
    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 42f
    private const val BODY_W = PAGE_W - (MARGIN * 2)

    fun savePdf(context: Context, report: RCAReport, includeTranscript: Boolean): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.w(TAG, "PDF_SAVE_UNSUPPORTED sdk=${Build.VERSION.SDK_INT} requires=29")
            return null
        }
        val fileName = safeFileName(report)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ArogyaDoot")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                val document = render(report, includeTranscript)
                try { document.writeTo(out) } finally { document.close() }
            } ?: throw IllegalStateException("Unable to open PDF output")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            Log.i(TAG, "SESSION_PDF_SAVED uri=$uri transcript=$includeTranscript")
            uri
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            Log.e(TAG, "SESSION_PDF_SAVE_FAILED ${t.javaClass.simpleName}: ${t.message}", t)
            null
        }
    }

    fun sharePdf(context: Context, report: RCAReport, includeTranscript: Boolean): Boolean {
        val uri = savePdf(context, report, includeTranscript) ?: return false
        return sharePdfUri(context, uri)
    }

    fun sharePdfUri(context: Context, uri: Uri): Boolean {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "ArogyaDoot Session Report")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return runCatching {
            context.startActivity(Intent.createChooser(intent, "Share ArogyaDoot Session Report"))
            true
        }.getOrDefault(false)
    }

    fun shareSummaryText(context: Context, report: RCAReport): Boolean {
        val text = buildString {
            appendLine("ArogyaDoot Session Summary")
            appendLine()
            appendLine("Goal: ${report.goal.ifBlank { report.problemStatement }}")
            appendLine("Outcome: ${report.outcome}")
            if (report.resolution.isNotBlank()) appendLine("Resolution: ${report.resolution}")
            if (report.rootCause.isNotBlank()) appendLine("RCA: ${report.rootCause}")
            if (report.recommendedFollowUp.isNotBlank()) appendLine("Next step: ${report.recommendedFollowUp}")
            appendLine("Turns: ${report.totalTurns}")
        }.trim()
        return runCatching {
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }, "Share ArogyaDoot Summary"))
            true
        }.getOrDefault(false)
    }

    private fun render(report: RCAReport, includeTranscript: Boolean): PdfDocument {
        val doc = PdfDocument()
        val renderer = PdfRenderer(doc)
        renderer.title("ArogyaDoot Session Report")
        renderer.line("Session ID: ${report.sessionId.ifBlank { "—" }}")
        renderer.line("Date: ${formatDate(report.startedAtMs)}")
        renderer.line("Duration: ${formatDuration(report.sessionDurationMs)}   •   ${report.totalTurns} turns")
        renderer.space(10f)

        renderer.section("Goal")
        renderer.paragraph(report.goal.ifBlank { report.problemStatement })
        renderer.section("Outcome")
        renderer.paragraph(report.outcome)
        renderer.line("Status: ${report.sessionStatus.ifBlank { "Completed" }}")
        if (report.resolution.isNotBlank()) {
            renderer.section("Resolution Achieved")
            renderer.paragraph(report.resolution)
        }

        renderer.section("Investigation Timeline")
        report.steps.forEach { s ->
            renderer.subhead("Turn ${s.stepNumber} • ${s.answeredByLabel}")
            renderer.paragraph("User: ${s.description}")
            renderer.paragraph("Summary: ${s.summary.ifBlank { s.result }}")
            if (s.source.isNotBlank()) renderer.line("Evidence: ${s.source}")
        }

        if (report.confirmedFacts.isNotEmpty()) {
            renderer.section("Key Findings")
            report.confirmedFacts.forEach { renderer.bullet(it) }
        }
        if (report.visualFindings.isNotEmpty()) {
            renderer.section("Visual / AR Evidence")
            report.visualFindings.forEach { renderer.bullet(it) }
        }
        if (report.rootCause.isNotBlank()) {
            renderer.section("Root Cause Analysis")
            renderer.paragraph(report.rootCause)
            if (report.rootCauseConfidence.isNotBlank()) renderer.line("Confidence: ${report.rootCauseConfidence}")
        }
        if (report.recommendedFollowUp.isNotBlank()) {
            renderer.section("Recommended Next Steps")
            renderer.paragraph(report.recommendedFollowUp)
        }
        if (report.sourcesUsed.isNotEmpty()) {
            renderer.section("Sources & AI Providers")
            report.sourcesUsed.forEach { renderer.bullet(it) }
        }

        if (includeTranscript) {
            renderer.section("Appendix A — Complete Conversation")
            report.steps.forEach { s ->
                renderer.subhead("Turn ${s.stepNumber} • ${s.answeredByLabel}")
                renderer.paragraph("User: ${s.description}")
                renderer.paragraph("ArogyaDoot: ${s.result}")
            }
        }
        renderer.finish()
        return doc
    }

    private class PdfRenderer(private val doc: PdfDocument) {
        private var pageNo = 0
        private lateinit var page: PdfDocument.Page
        private lateinit var canvas: Canvas
        private var y = MARGIN
        private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 10.5f }
        private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = 9f }
        private val section = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 14f; typeface = Typeface.DEFAULT_BOLD }
        private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 20f; typeface = Typeface.DEFAULT_BOLD }
        private val subhead = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 10.5f; typeface = Typeface.DEFAULT_BOLD }

        init { newPage() }

        fun title(text: String) { ensure(34f); canvas.drawText(text, MARGIN, y, titlePaint); y += 28f }
        fun section(text: String) { space(12f); ensure(28f); canvas.drawText(text, MARGIN, y, section); y += 20f }
        fun subhead(text: String) { ensure(20f); canvas.drawText(text, MARGIN, y, subhead); y += 15f }
        fun line(text: String) { drawWrapped(text, small, 12f) }
        fun paragraph(text: String) { drawWrapped(text, body, 14f); space(4f) }
        fun bullet(text: String) { drawWrapped("• $text", body, 14f, hanging = 10f) }
        fun space(px: Float) { y += px }

        private fun drawWrapped(text: String, paint: Paint, lineHeight: Float, hanging: Float = 0f) {
            val words = text.replace(Regex("\\s+"), " ").trim().split(" ")
            if (words.isEmpty()) return
            var line = ""
            var first = true
            for (word in words) {
                val test = if (line.isBlank()) word else "$line $word"
                val allowed = BODY_W - if (first) 0f else hanging
                if (paint.measureText(test) > allowed && line.isNotBlank()) {
                    ensure(lineHeight + 2f)
                    canvas.drawText(line, MARGIN + if (first) 0f else hanging, y, paint)
                    y += lineHeight
                    line = word
                    first = false
                } else line = test
            }
            if (line.isNotBlank()) {
                ensure(lineHeight + 2f)
                canvas.drawText(line, MARGIN + if (first) 0f else hanging, y, paint)
                y += lineHeight
            }
        }

        private fun ensure(height: Float) { if (y + height > PAGE_H - MARGIN) newPage() }
        private fun newPage() {
            if (::page.isInitialized) doc.finishPage(page)
            pageNo += 1
            page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
            canvas = page.canvas
            y = MARGIN
        }
        fun finish() { if (::page.isInitialized) doc.finishPage(page) }
    }

    private fun safeFileName(report: RCAReport): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date(report.endedAtMs.takeIf { it > 0 } ?: System.currentTimeMillis()))
        val topic = (report.goal.ifBlank { report.problemStatement })
            .replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').take(40).ifBlank { "Session" }
        return "ArogyaDoot_${topic}_$stamp.pdf"
    }

    private fun formatDate(ms: Long): String = if (ms <= 0) "—" else SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(ms))
    private fun formatDuration(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val min = totalSec / 60
        val sec = totalSec % 60
        return "${min}m ${sec}s"
    }
}
