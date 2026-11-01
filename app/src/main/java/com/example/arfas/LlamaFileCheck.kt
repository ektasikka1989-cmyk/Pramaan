package com.example.arfas

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Checks that a GGUF file is complete before the app uses it: reads the header, works out how
 * many bytes all tensors need and compares that with the file size. A truncated copy or download
 * is otherwise only noticed by llama.cpp as "could not load GGUF model". (Same logic as
 * tools/check_gguf.py on the PC.)
 */
object LlamaFileCheck {
    @Volatile var lastReason: String = ""
        private set

    // ggml type id -> (bytes per block, values per block)
    private val TYPE_SIZE: Map<Int, Pair<Int, Int>> = mapOf(
        0 to (4 to 1), 1 to (2 to 1), 2 to (18 to 32), 3 to (20 to 32), 6 to (22 to 32), 7 to (24 to 32),
        8 to (34 to 32), 9 to (36 to 32), 10 to (84 to 256), 11 to (110 to 256), 12 to (144 to 256),
        13 to (176 to 256), 14 to (210 to 256), 15 to (292 to 256), 16 to (66 to 256), 17 to (74 to 256),
        18 to (98 to 256), 19 to (50 to 256), 20 to (18 to 32), 21 to (110 to 256), 22 to (82 to 256),
        23 to (136 to 256), 24 to (1 to 1), 25 to (2 to 1), 26 to (4 to 1), 27 to (8 to 1), 28 to (8 to 1),
        29 to (56 to 256), 30 to (2 to 1), 34 to (54 to 256), 35 to (66 to 256), 39 to (17 to 32), 40 to (36 to 64), 41 to (18 to 128), 42 to (18 to 64)
    )
    private val FIXED: Map<Int, Int> = mapOf(0 to 1, 1 to 1, 2 to 2, 3 to 2, 4 to 4, 5 to 4, 6 to 4, 7 to 1, 10 to 8, 11 to 8, 12 to 8)

    fun isComplete(file: File): Boolean {
        lastReason = ""
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val size = raf.length()
                // Header is small; map at most 64 MB of it.
                val buf = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, minOf(size, 64L shl 20)).order(ByteOrder.LITTLE_ENDIAN)
                if (buf.remaining() < 24 || buf.int != 0x46554747) { lastReason = "not a GGUF file"; return false }
                buf.int // version
                val nTensors = buf.long
                val nKv = buf.long
                var alignment = 32L
                repeat(nKv.toInt()) {
                    val key = str(buf)
                    val vt = buf.int
                    if (key == "general.alignment" && vt == 4) alignment = buf.int.toLong() and 0xffffffffL else skipValue(buf, vt)
                }
                var end = 0L
                repeat(nTensors.toInt()) {
                    str(buf)
                    val nDims = buf.int
                    var count = 1L
                    repeat(nDims) { count *= buf.long }
                    val type = buf.int
                    val offset = buf.long
                    val (bb, bv) = TYPE_SIZE[type] ?: run { lastReason = "unknown tensor type $type"; return false }
                    end = maxOf(end, offset + count / bv * bb)
                }
                val dataStart = (buf.position() + alignment - 1) / alignment * alignment
                val need = dataStart + end
                if (size < need) {
                    lastReason = "${(need - size) / 1_000_000} MB missing (${size / 1_000_000} of ${need / 1_000_000} MB)"
                    false
                } else true
            }
        } catch (t: Throwable) {
            lastReason = "header unreadable: ${t.javaClass.simpleName}"
            false
        }
    }

    private fun str(b: ByteBuffer): String {
        val n = b.long.toInt()
        val bytes = ByteArray(n); b.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun skipValue(b: ByteBuffer, vt: Int) {
        FIXED[vt]?.let { b.position(b.position() + it); return }
        when (vt) {
            8 -> { val n = b.long.toInt(); b.position(b.position() + n) }
            9 -> {
                val inner = b.int
                val count = b.long
                val fixed = FIXED[inner]
                if (fixed != null) b.position(b.position() + (fixed * count).toInt())
                else repeat(count.toInt()) { skipValue(b, inner) }
            }
            else -> throw IllegalStateException("metadata type $vt")
        }
    }
}
