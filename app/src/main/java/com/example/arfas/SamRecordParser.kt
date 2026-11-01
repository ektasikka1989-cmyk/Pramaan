package com.example.arfas

/** One SAM 3.1 detection: pixel box in the sent image (right/bottom exclusive). */
data class SamBox(
    val objectId: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val imageWidth: Int,
    val imageHeight: Int,
    val confidence: Float?
) {
    fun normalized(): MetaVisionMath.Box = MetaVisionMath.Box(
        left.toFloat() / imageWidth, top.toFloat() / imageHeight,
        (right - left).toFloat() / imageWidth, (bottom - top).toFloat() / imageHeight
    )
}

/**
 * SAM 3.1 output grammar (from Meta's meta_sam_parser): each line is a frame header `<Nf>` (or
 * `<Nf;fields>`) followed by records `id<|box;x1=..;y1=..;x2=..;y2=..;w=W;h=H;c=..|><|mask;...|>`,
 * optionally comma-separated. x2/y2 are inclusive. Only boxes are read here.
 */
object SamRecordParser {
    private val HEADER = Regex("^<([0-9]+)f(?:;([^>]*))?>(.*)$")
    private val RECORD = Regex(",?([0-9]+)((?:<\\|[A-Za-z][A-Za-z0-9_.-]*(?:;[^|]*)?\\|>)+)")
    private val TOKEN = Regex("<\\|([A-Za-z][A-Za-z0-9_.-]*)(?:;([^|]*))?\\|>")

    fun parseBoxes(output: String): List<SamBox> {
        val out = ArrayList<SamBox>()
        for (rawLine in output.split('\n')) {
            val line = rawLine.trim()
            val header = HEADER.find(line) ?: continue
            for (rec in RECORD.findAll(header.groupValues[3])) {
                val tokens = TOKEN.findAll(rec.groupValues[2]).toList()
                val boxes = tokens.filter { it.groupValues[1] == "box" }
                val masks = tokens.filter { it.groupValues[1] == "mask" }
                if (boxes.size != 1 || masks.size != 1) continue
                val f = fields(boxes[0].groupValues[2])
                val x1 = f["x1"]?.toIntOrNull() ?: continue
                val y1 = f["y1"]?.toIntOrNull() ?: continue
                val x2 = f["x2"]?.toIntOrNull() ?: continue
                val y2 = f["y2"]?.toIntOrNull() ?: continue
                val w = f["w"]?.toIntOrNull() ?: continue
                val h = f["h"]?.toIntOrNull() ?: continue
                if (w <= 0 || h <= 0 || x1 < 0 || y1 < 0 || x2 < x1 || y2 < y1 || x2 >= w || y2 >= h) continue
                val c = f["c"]?.toFloatOrNull()?.takeIf { it.isFinite() && it in 0f..1f }
                out += SamBox(rec.groupValues[1], x1, y1, x2 + 1, y2 + 1, w, h, c)
            }
        }
        return out
    }

    private fun fields(body: String): Map<String, String> = body.split(';').mapNotNull { part ->
        val i = part.indexOf('=')
        if (i <= 0) null else part.substring(0, i).trim() to part.substring(i + 1).trim()
    }.toMap()
}
