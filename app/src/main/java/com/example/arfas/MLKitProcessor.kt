package com.example.arfas

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private const val TAG = "MLKitProcessor"

/**
 * MLKitProcessor — deterministic on-device vision.
 *
 * Runs BEFORE any LLM call. Zero network, zero cost, ~100ms on mid-range Android.
 * Results are written into the notebook as ML_KIT entries — highest trust.
 *
 * Three detectors:
 *   1. Text Recognition (OCR) — labels, error codes, serial numbers, gauge readings
 *   2. Object Detection       — component categories with bounding boxes
 *   3. Barcode / QR Scanner   — part numbers, equipment IDs, QR codes
 */
class MLKitProcessor {

    private val textRecognizer = TextRecognition.getClient(
        TextRecognizerOptions.DEFAULT_OPTIONS
    )

    // Hindi/Marathi labels, MCP cards and registers. Bundled model = works offline.
    // Needs com.google.mlkit:text-recognition-devanagari:16.0.1 (see BUILD.md).
    private val devanagariRecognizer = TextRecognition.getClient(
        DevanagariTextRecognizerOptions.Builder().build()
    )

    // NOTE: ObjectDetection with enableClassification() uses an UNBUNDLED model.
    // On first run without internet, the model may not be downloaded yet.
    // After one successful download it works fully offline forever.
    // addOnFailureListener handles the case gracefully — returns empty list.
    private val objectDetector = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
            .enableMultipleObjects()
            .enableClassification()
            .build()
    )

    private val barcodeScanner = BarcodeScanning.getClient()

    // ── Main entry point ──────────────────────────────────────────────────────

    suspend fun process(bitmap: Bitmap): MLKitResult {
        val image = InputImage.fromBitmap(bitmap, 0)

        val ocrText  = runOCR(image)
        val objectOutcome = runObjectDetection(image)
        val objects  = objectOutcome.objects
        val barcodes = runBarcodeScanning(image)

        Log.d(TAG, "OCR='${ocrText.take(60)}' objects=${objects.size} barcodes=${barcodes.size} objectModel=${objectOutcome.available}")

        return MLKitResult(
            ocrText         = ocrText,
            detectedObjects = objects,
            barcodes        = barcodes,
            objectDetectionAvailable = objectOutcome.available,
            sensorWarnings = objectOutcome.warning.takeIf { it.isNotBlank() }?.let { listOf(it) }.orEmpty(),
            hasText         = ocrText.isNotBlank(),
            hasObjects      = objects.isNotEmpty(),
            hasBarcode      = barcodes.isNotEmpty(),
            frameWidth      = bitmap.width,
            frameHeight     = bitmap.height
        )
    }

    // ── OCR ───────────────────────────────────────────────────────────────────

    /** Text + barcodes only (the Meta vision stack does object detection with DETR). */
    suspend fun readTextAndBarcodes(bitmap: Bitmap): Pair<String, List<String>> {
        val image = InputImage.fromBitmap(bitmap, 0)
        return runOCR(image) to runBarcodeScanning(image)
    }

    /** OCR-only entry point for Phase-1 document attachments. */
    suspend fun recognizeText(bitmap: Bitmap): String =
        runOCR(InputImage.fromBitmap(bitmap, 0))

    /**
     * Pramaan register photos: every OCR line with its position, so a table can be rebuilt row by
     * row (ArogyaTableOcr). The Devanagari reader's lines are used when the page has Devanagari.
     */
    suspend fun recognizeLines(bitmap: Bitmap): List<ArogyaTableOcr.Line> {
        val image = InputImage.fromBitmap(bitmap, 0)
        suspend fun read(r: com.google.mlkit.vision.text.TextRecognizer): List<ArogyaTableOcr.Line> =
            suspendCancellableCoroutine { cont ->
                r.process(image)
                    .addOnSuccessListener { res ->
                        // ML Kit often joins a whole printed table row into one line ("Name Husband Age …"):
                        // keep each word's position and split the line into cells at wide gaps.
                        cont.resume(res.textBlocks.flatMap { b -> b.lines }.flatMap { l ->
                            val words = l.elements.mapNotNull { e -> e.boundingBox?.let { bb -> ArogyaTableOcr.Line(e.text, bb.left, bb.top, bb.right, bb.bottom) } }
                            if (words.isNotEmpty()) ArogyaTableOcr.cells(words)
                            else listOfNotNull(l.boundingBox?.let { bb -> ArogyaTableOcr.Line(l.text, bb.left, bb.top, bb.right, bb.bottom) })
                        })
                    }
                    .addOnFailureListener { e -> Log.w(TAG, "OCR lines failed: ${e.message}"); cont.resume(emptyList()) }
            }
        val latin = read(textRecognizer)
        val hindi = read(devanagariRecognizer)
        return if (hindi.any { l -> l.text.any { it in '\u0900'..'\u097F' } }) hindi else latin
    }

    /**
     * Latin + Devanagari. The Devanagari result is used when it actually contains Devanagari
     * letters; its lines are merged after the Latin ones without duplicates.
     */
    private suspend fun runOCR(image: InputImage): String {
        val latin = runRecognizer(textRecognizer, image)
        val hindi = runRecognizer(devanagariRecognizer, image)
        if (hindi.none { it in '\u0900'..'\u097F' }) return latin
        val seen = HashSet<String>()
        return (latin.lines() + hindi.lines()).map { it.trim() }.filter { it.isNotBlank() && seen.add(it.lowercase()) }
            .joinToString("\n")
    }

    private suspend fun runRecognizer(recognizer: com.google.mlkit.vision.text.TextRecognizer, image: InputImage): String =
        suspendCancellableCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    cont.resume(result.textBlocks.joinToString("\n") { it.text }.trim())
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "OCR failed: ${e.message}")
                    cont.resume("")
                }
        }

    // ── Object Detection ──────────────────────────────────────────────────────

    private data class ObjectDetectionOutcome(
        val objects: List<MLKitObject>,
        val available: Boolean,
        val warning: String = ""
    )

    private suspend fun runObjectDetection(image: InputImage): ObjectDetectionOutcome =
        suspendCancellableCoroutine { cont ->
            objectDetector.process(image)
                .addOnSuccessListener { results ->
                    cont.resume(ObjectDetectionOutcome(
                        objects = results.map { obj ->
                            val label = obj.labels.maxByOrNull { it.confidence }
                            MLKitObject(
                                label = label?.text ?: "Unknown",
                                confidence = label?.confidence ?: 0f,
                                boundingBox = obj.boundingBox
                            )
                        },
                        available = true
                    ))
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "ObjectDetection unavailable: ${e.message}")
                    cont.resume(ObjectDetectionOutcome(
                        objects = emptyList(),
                        available = false,
                        warning = "OBJECT_CLASSIFIER_UNAVAILABLE"
                    ))
                }
        }

    // ── Barcode / QR ──────────────────────────────────────────────────────────

    private suspend fun runBarcodeScanning(image: InputImage): List<String> =
        suspendCancellableCoroutine { cont ->
            barcodeScanner.process(image)
                .addOnSuccessListener { barcodes ->
                    cont.resume(barcodes.mapNotNull { barcode ->
                        when (barcode.valueType) {
                            Barcode.TYPE_TEXT,
                            Barcode.TYPE_URL,
                            Barcode.TYPE_PRODUCT -> barcode.rawValue
                            else                 -> barcode.rawValue
                        }
                    })
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "Barcode failed: ${e.message}")
                    cont.resume(emptyList())
                }
        }

    fun close() {
        textRecognizer.close()
        devanagariRecognizer.close()
        objectDetector.close()
        barcodeScanner.close()
    }
}

// ── Result data classes ───────────────────────────────────────────────────────

data class MLKitResult(
    val ocrText: String                    = "",
    val detectedObjects: List<MLKitObject> = emptyList(),
    val barcodes: List<String>             = emptyList(),
    val hasText: Boolean                   = false,
    val hasObjects: Boolean                = false,
    val hasBarcode: Boolean                = false,
    val frameWidth: Int                    = 0,
    val frameHeight: Int                   = 0,
    val objectDetectionAvailable: Boolean  = true,
    val sensorWarnings: List<String>       = emptyList()
) {
    // True if ML Kit found enough to partially answer without any LLM
    val isSubstantial: Boolean
        get() = hasText || hasBarcode || detectedObjects.any {
            it.confidence > 0.5f && it.label.isNotBlank() && !it.label.equals("Unknown", ignoreCase = true)
        }

    /**
     * Produces a structured brief for Gemma to INTERPRET — not a raw dump.
     *
     * Rules:
     * - OCR text is trimmed to meaningful fragments (error codes, labels, model
     *   numbers). Full sentences of UI text are excluded — Gemma should not
     *   read them aloud, it should reason about what they mean.
     * - Object labels are filtered only by confidence; semantic relevance is
     *   decided later from the current turn rather than a static label blacklist.
     * - The output is framed as EVIDENCE for Gemma to reason from, not answers
     *   to speak verbatim.
     */
    fun toPromptContext(): String {
        val parts = mutableListOf<String>()

        // OCR — extract only short meaningful fragments (error codes, model
        // numbers, labels). Skip long sentences — those are UI text the user
        // can read themselves; Gemma should not relay them word-for-word.
        if (ocrText.isNotBlank()) {
            val meaningful = ocrText
                .lines()
                .map { it.trim() }
                .filter { line ->
                    line.isNotBlank() &&
                    line.length in 2..60 &&          // skip single chars and walls of text
                    !line.all { it.isDigit() }        // skip pure number strings (pixel coords etc)
                }
                .take(4)                              // max 4 lines — enough context, not a dump
            if (meaningful.isNotEmpty()) {
                parts.add("VISIBLE TEXT: ${meaningful.joinToString(" | ")}")
            }
        }

        // Object detection — confidence and geometry only. No semantic label
        // blacklist is allowed to decide what the camera is permitted to observe.
        // Relevance to the user's current turn is resolved later by the semantic router.
        val usefulObjects = detectedObjects
            .filter { it.confidence > 0.55f && it.label.isNotBlank() && !it.label.equals("Unknown", ignoreCase = true) }
            .sortedByDescending { it.confidence }
            .take(5)

        if (usefulObjects.isNotEmpty()) {
            // Gap 2 fix: convert bounding box coordinates to human-readable positions.
            // ML Kit gives pixel rects — convert to "top-left", "center", "bottom-right" etc.
            // This lets Gemma handle "that wire on the left" or "the button at the top".
            val withPositions = usefulObjects.joinToString(", ") { obj ->
                val pos = obj.boundingBox?.let { box ->
                    val cx = box.centerX().toFloat()
                    val cy = box.centerY().toFloat()
                    val width = frameWidth.takeIf { it > 0 }?.toFloat() ?: 1f
                    val height = frameHeight.takeIf { it > 0 }?.toFloat() ?: 1f
                    val hPos = when {
                        cx < width / 3f       -> "left"
                        cx > width * 2f / 3f  -> "right"
                        else                  -> "center"
                    }
                    val vPos = when {
                        cy < height / 3f      -> "top"
                        cy > height * 2f / 3f -> "bottom"
                        else                  -> "middle"
                    }
                    if (hPos == "center" && vPos == "middle") "" else "$vPos-$hPos"
                } ?: ""
                if (pos.isNotBlank()) "${obj.label} ($pos)" else obj.label
            }
            parts.add("DETECTED: $withPositions")
        }

        // Barcodes — always useful, keep as-is
        if (barcodes.isNotEmpty()) {
            parts.add("SCANNED CODE: ${barcodes.first().take(50)}")
        }
        if (!objectDetectionAvailable) {
            parts.add("SENSOR STATUS: local object classification unavailable; OCR/barcode results above remain valid.")
        }

        return parts.joinToString("\n")
    }

    /**
     * Raw OCR text — used only for notebook storage and RCA audit.
     * Never sent to TTS or spoken directly.
     */
    fun rawOcrForNotebook(): String = ocrText.trim().take(200)
}

data class MLKitObject(
    val label: String,
    val confidence: Float,
    val boundingBox: Rect? = null
)