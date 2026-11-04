package com.example.arfas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Photos read the way a person reads them. With signal and a Meta key, Muse Spark (Meta's vision model) looks at
 * the whole photo (handwriting, tables without headings, a name written on two lines, any layout) and writes
 * what is on it as plain text. Pramaan's own readers then turn that text into fields exactly as they do with the
 * phone's ML Kit text, so the same checks and the ASHA's review apply.
 *
 * Order follows the one Settings choice: Cloud first → Muse Spark first; Local first → only when the phone's own
 * reading came back incomplete. No signal / no key / slow → the phone's reading (ML Kit + rules + phone model).
 * Aadhaar numbers are blacked out on the phone before the photo is sent and never come back from the cloud.
 */
object ArogyaPhotoVision {
    private const val TAG = "ArogyaDemo"
    const val LABEL = "Muse Spark 1.3 (Meta, online): read the photo"

    // ── Android side ──

    /** Whether to send this photo: signal + Meta key, and Cloud first or the phone's own reading came back incomplete. */
    suspend fun wanted(context: Context, phoneReadingGood: Boolean): Boolean = withContext(Dispatchers.IO) {
        MetaOnline.ready(context) && (ArogyaCloud.cloudFirst(context) || !phoneReadingGood)
    }

    private fun blackout(bmp: Bitmap, boxes: List<ArogyaTableOcr.Line>): Bitmap {
        if (boxes.isEmpty()) return bmp
        val out = bmp.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out); val p = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
        boxes.forEach { b -> val pad = (b.h / 2).coerceAtLeast(4); c.drawRect((b.left - pad).toFloat(), (b.top - pad).toFloat(), (b.right + pad).toFloat(), (b.bottom + pad).toFloat(), p) }
        return out
    }

    /**
     * The photo's text as Muse Spark reads it, or null (no signal, no key, slow, nothing read). [lines] are the phone's
     * ML Kit lines for the same bitmap: they locate the Aadhaar numbers to black out before sending.
     */
    suspend fun transcribe(context: Context, bmp: Bitmap, lines: List<ArogyaTableOcr.Line>, kind: ArogyaPhotoText.Kind): String? = withContext(Dispatchers.IO) {
        val t = System.currentTimeMillis()
        val hidden = if (kind == ArogyaPhotoText.Kind.MEDICINE) emptyList() else ArogyaPhotoText.hiddenBoxes(lines)
        val safe = blackout(bmp, hidden)
        val scale = 1600f / maxOf(safe.width, safe.height)
        val small = if (scale < 1f) Bitmap.createScaledBitmap(safe, (safe.width * scale).toInt(), (safe.height * scale).toInt(), true) else safe
        val jpeg = ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
        val raw = ArogyaCloud.withDeadline(ArogyaVisitAi.CLOUD_DEADLINE_MS, "photo_vision") {
            kotlinx.coroutines.runBlocking { MetaOnline.api(context).spark("arogya_photo_${kind.name.lowercase()}", ArogyaPhotoText.SYSTEM, ArogyaPhotoText.prompt(kind), jpeg) }
        }
        val text = ArogyaPhotoText.clean(raw)
        Log.i(TAG, "AROGYA_PHOTO_VISION kind=$kind ok=${text != null} chars=${text?.length ?: 0} aadhaar_hidden=${hidden.size} ms=${System.currentTimeMillis() - t}")
        text
    }
}
