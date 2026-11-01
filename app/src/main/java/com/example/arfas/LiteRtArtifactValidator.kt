package com.example.arfas

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/** Cheap pre-load validation matching AndroLLM's LiteRT artifact gate. */
object LiteRtArtifactValidator {
    data class Result(val valid: Boolean, val format: String = "", val version: Int = 0, val error: String = "")

    private val litertMagic = byteArrayOf('L'.code.toByte(),'I'.code.toByte(),'T'.code.toByte(),'E'.code.toByte(),'R'.code.toByte(),'T'.code.toByte(),'L'.code.toByte(),'M'.code.toByte())

    fun validateLitertLm(path: String): Result {
        val f = File(path)
        if (!f.exists()) return Result(false, error = "Model file not found")
        if (!f.canRead()) return Result(false, error = "Model file is not readable")
        return try {
            FileInputStream(f).use { input ->
                val h = ByteArray(12)
                if (input.read(h) < h.size) return Result(false, error = "Model file is truncated")
                if (!h.copyOfRange(0, 8).contentEquals(litertMagic)) {
                    return Result(false, error = "Not a LiteRT-LM container (missing LITERTLM header)")
                }
                val version = (h[8].toInt() and 0xff) or
                    ((h[9].toInt() and 0xff) shl 8) or
                    ((h[10].toInt() and 0xff) shl 16) or
                    ((h[11].toInt() and 0xff) shl 24)
                if (version < 1) Result(false, error = "Unsupported LiteRT-LM container version $version")
                else Result(true, format = "litertlm", version = version)
            }
        } catch (t: Throwable) {
            Result(false, error = "Model header read failed: ${t.message}")
        }
    }

    fun sha256(path: String): String? = try {
        val d = MessageDigest.getInstance("SHA-256")
        FileInputStream(File(path)).use { input ->
            val b = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(b)
                if (n < 0) break
                d.update(b, 0, n)
            }
        }
        d.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Throwable) { null }
}
