package com.example.arfas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Visit documents stay local. OCR is a draft for human review, never clinical evidence. */
object ArogyaVisitMedia {
    suspend fun import(context: Context, uri: Uri, store: ArogyaVisitRepository): ArogyaAttachment = withContext(Dispatchers.IO) {
        val mime = context.contentResolver.getType(uri).orEmpty()
        require(mime.startsWith("image/") || mime == "application/pdf") { "Choose an image or PDF." }
        val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
            val out=ByteArrayOutputStream(); val buf=ByteArray(8192)
            while(true) { val n=stream.read(buf); if(n<0) break; require(out.size()+n<=15*1024*1024) { "File must be under 15 MB." }; out.write(buf,0,n) }
            out.toByteArray()
        } ?: error("Cannot read attachment")
        storeBytes(context,bytes,mime,store)
    }
    suspend fun camera(context: Context, bitmap: Bitmap, store: ArogyaVisitRepository): ArogyaAttachment = withContext(Dispatchers.IO) {
        val out=ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.JPEG,90,out)
        storeBytes(context,out.toByteArray(),"image/jpeg",store)
    }
    private suspend fun storeBytes(context: Context, bytes: ByteArray, mime: String, store: ArogyaVisitRepository): ArogyaAttachment {
        val id=UUID.randomUUID().toString()
        val text = runCatching {
            val ocr=MLKitProcessor()
            try {
                if(mime=="application/pdf") {
                    val temp=File(context.noBackupFilesDir,"arogya-ocr-$id.pdf")
                    try {
                        temp.writeBytes(bytes)
                        ParcelFileDescriptor.open(temp,ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                            PdfRenderer(fd).use { pdf ->
                                buildString {
                                    appendLine("OCR draft: first ${minOf(3,pdf.pageCount)} of ${pdf.pageCount} pages only. Verify against the original.")
                                    for(i in 0 until minOf(3,pdf.pageCount)) pdf.openPage(i).use { page ->
                                        val scale=minOf(1600f/page.width,1600f/page.height)
                                        val b=Bitmap.createBitmap((page.width*scale).toInt().coerceAtLeast(1),(page.height*scale).toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888)
                                        try { b.eraseColor(Color.WHITE); page.render(b,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY); appendLine("Page ${i+1}: ${ocr.recognizeText(b)}") } finally { b.recycle() }
                                    }
                                }
                            }
                        }
                    } finally { temp.delete() }
                } else {
                    val opts=BitmapFactory.Options().apply { inJustDecodeBounds=true }; BitmapFactory.decodeByteArray(bytes,0,bytes.size,opts)
                    require(opts.outWidth>0 && opts.outHeight>0)
                    var sample=1; while(maxOf(opts.outWidth,opts.outHeight)/sample>2048) sample*=2
                    val b=BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply { inSampleSize=sample }) ?: error("Cannot decode image")
                    try { "OCR draft; verify orientation, dates and units against original:\n"+ocr.recognizeText(b) } finally { b.recycle() }
                }
            } finally { ocr.close() }
        }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it; "OCR unavailable. Review the original document manually; no fields were filled." }
        store.saveAttachment(id,bytes)
        return ArogyaAttachment(id,if(mime=="application/pdf") "Visit document" else "Card image",mime,if(text.length<=16000) text else "OCR TEXT TRUNCATED — review the complete original document.\n"+text.take(15900))
    }
    suspend fun preview(context: Context, store: ArogyaVisitRepository, a: ArogyaAttachment, pageIndex: Int): Pair<Bitmap, Int> = withContext(Dispatchers.IO) {
        val bytes=store.attachment(a.id)
        if(a.mime!="application/pdf") {
            val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }; BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
            require(bounds.outWidth>0 && bounds.outHeight>0)
            var sample=1; while(maxOf(bounds.outWidth,bounds.outHeight)/sample>1600) sample*=2
            (BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply {inSampleSize=sample}) ?: error("Unreadable image")) to 1
        } else {
            val temp=File(context.noBackupFilesDir,"arogya-preview-${a.id}.pdf")
            try {
                temp.writeBytes(bytes)
                ParcelFileDescriptor.open(temp,ParcelFileDescriptor.MODE_READ_ONLY).use {fd -> PdfRenderer(fd).use {pdf ->
                    require(pdf.pageCount>0)
                    pdf.openPage(pageIndex.coerceIn(0,pdf.pageCount-1)).use { page ->
                        val scale=minOf(1400f/page.width,1400f/page.height)
                        val bitmap=Bitmap.createBitmap((page.width*scale).toInt().coerceAtLeast(1),(page.height*scale).toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.WHITE); page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap to pdf.pageCount
                    }
                } }
            } finally {temp.delete()}
        }
    }
    /** User-selected destination. No report is uploaded, sent or silently added to public Downloads. */
    suspend fun exportPdf(context: Context, uri: Uri, report: String, mono: Boolean = false) = withContext(Dispatchers.IO) {
        require(report.isNotBlank()) { "Cannot export an empty visit report" }
        val paint=TextPaint().apply { color=Color.BLACK; textSize=12f; isAntiAlias=true
            if (mono) typeface=android.graphics.Typeface.MONOSPACE }
        val layout=StaticLayout.Builder.obtain(report,0,report.length,paint,511).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(3f,1f).build()
        val pdf=PdfDocument()
        try {
            var line=0; var pageNo=1
            while(line<layout.lineCount) {
                val top=layout.getLineTop(line); var end=line+1
                while(end<layout.lineCount && layout.getLineBottom(end)-top<=730) end++
                val bottom=layout.getLineBottom(end-1)
                val page=pdf.startPage(PdfDocument.PageInfo.Builder(595,842,pageNo++).create())
                page.canvas.save(); page.canvas.translate(42f,48f-top); page.canvas.clipRect(0,top,511,bottom); layout.draw(page.canvas); page.canvas.restore()
                page.canvas.drawText("ArogyaDoot • page ${pageNo-1}",42f,813f,paint); pdf.finishPage(page); line=end
            }
            context.contentResolver.openOutputStream(uri,"w")?.use { pdf.writeTo(it) } ?: error("Cannot open PDF destination")
        } finally { pdf.close() }
    }
}
