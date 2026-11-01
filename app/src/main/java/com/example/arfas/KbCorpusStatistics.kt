package com.example.arfas

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.text.Normalizer
import java.util.Locale

/** Read-only corpus signals for the later containment-enabled history phase. */
class KbCorpusStatistics {
    private var signature: String = ""
    private var documentCount: Int = 0
    private var documentFrequency: Map<String, Int> = emptyMap()
    private var identifiers: Set<String> = emptySet()

    @Synchronized
    fun isCurrent(value: String): Boolean = signature == value && documentCount > 0

    @Synchronized
    fun rebuild(value: String, documents: List<HybridKnowledgeRetriever.Document>) {
        val frequency = HashMap<String, Int>()
        val foundIdentifiers = LinkedHashSet<String>()
        documents.forEach { document ->
            tokens(document.text).toSet().forEach { token -> frequency[token] = (frequency[token] ?: 0) + 1 }
            foundIdentifiers += identifiers(document.text)
        }
        signature = value
        documentCount = documents.size
        documentFrequency = frequency
        identifiers = foundIdentifiers
    }

    /**
     * Saves the statistics next to the pack so the next app start loads them instead of
     * re-reading and re-tokenising every passage. Written to a temporary file and renamed.
     */
    @Synchronized
    fun save(file: File): Boolean = runCatching {
        if (documentCount <= 0) return@runCatching false
        val tmp = File(file.path + ".tmp")
        DataOutputStream(BufferedOutputStream(tmp.outputStream(), 1 shl 16)).use { out ->
            out.writeInt(FORMAT)
            out.writeUTF(signature)
            out.writeInt(documentCount)
            out.writeInt(documentFrequency.size)
            documentFrequency.forEach { (k, v) -> out.writeUTF(k); out.writeInt(v) }
            out.writeInt(identifiers.size)
            identifiers.forEach { out.writeUTF(it) }
        }
        tmp.renameTo(file)
    }.getOrDefault(false)

    /** Loads saved statistics when they were built for [expected]; false leaves this object unchanged. */
    @Synchronized
    fun load(file: File, expected: String): Boolean = runCatching {
        if (!file.exists()) return@runCatching false
        DataInputStream(BufferedInputStream(file.inputStream(), 1 shl 16)).use { input ->
            if (input.readInt() != FORMAT || input.readUTF() != expected) return@runCatching false
            val count = input.readInt()
            val dfSize = input.readInt()
            val frequency = HashMap<String, Int>(dfSize * 4 / 3 + 16)
            repeat(dfSize) { frequency[input.readUTF()] = input.readInt() }
            val idSize = input.readInt()
            val ids = HashSet<String>(idSize * 4 / 3 + 16)
            repeat(idSize) { ids += input.readUTF() }
            if (count <= 0) return@runCatching false
            signature = expected
            documentCount = count
            documentFrequency = frequency
            identifiers = ids
            true
        }
    }.getOrDefault(false)

    @Synchronized
    fun meaningfulLiteralSlots(query: String): Set<String> {
        if (documentCount <= 0) return emptySet()
        // The vocabulary median is dominated by one-off words (Zipf's law), which
        // incorrectly discards common but meaningful terms. Only near-universal
        // corpus terms are treated as non-slots; the boundary scales with corpus size.
        val highFrequencyCutoff = (documentCount - kotlin.math.sqrt(documentCount.toDouble()).toInt())
            .coerceAtLeast(1)
        return tokens(query).filterTo(linkedSetOf()) { token ->
            token in identifiers || (documentFrequency[token] ?: 0) <= highFrequencyCutoff
        }
    }

    private fun tokens(text: String): List<String> = Regex("[\\p{L}\\p{N}][\\p{L}\\p{M}\\p{N}._/-]*")
        .findAll(Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT))
        .map { it.value }.toList()

    private fun identifiers(text: String): Set<String> = Regex("[\\p{L}\\p{M}\\p{N}._/-]*\\d[\\p{L}\\p{M}\\p{N}._/-]*")
        .findAll(Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT))
        .map { it.value }.toSet()

    private companion object {
        const val FORMAT = 1
    }
}
