package com.example.arfas

import java.io.File

/**
 * Installed knowledge packs kept side by side for quick switching (plain file moves, no Android).
 *
 * The active pack lives in [packDir] as `<activeDbName>` plus every prepared file whose name starts
 * with [prefix] (full-text index, corpus profile, calibration, statistics). A library entry is a
 * folder `library/<packId>/` holding the same files and a small meta file. Moving files keeps their
 * dates, so the saved preparation stays valid and a switched-to pack starts from its saved files.
 */
class GenericKbPackLibrary(
    private val packDir: File,
    private val activeDbName: String,
    private val prefix: String
) {
    data class Meta(val packId: String, val displayName: String, val rowCount: Int)

    private val libraryDir = File(packDir, "library")
    private val activeDb get() = File(packDir, activeDbName)

    fun activeFiles(): List<File> = packDir.listFiles()?.filter { f ->
        f.isFile && f.name.startsWith(prefix) &&
            !f.name.endsWith(".candidate") && !f.name.endsWith(".backup") && !f.name.endsWith(".tmp")
    }.orEmpty()

    private fun safeName(id: String): String = id.filter { it.isLetterOrDigit() }.take(64)

    /** The library folder of [packId], when that pack is in the library. */
    fun entry(packId: String): File? =
        safeName(packId).takeIf { it.isNotEmpty() }?.let { File(libraryDir, it) }?.takeIf { File(it, activeDbName).exists() }

    fun metaOf(dir: File): Meta {
        val lines = runCatching { File(dir, META).readLines() }.getOrDefault(emptyList())
        return Meta(lines.getOrNull(0).orEmpty().ifBlank { dir.name }, lines.getOrNull(2).orEmpty(), lines.getOrNull(1)?.toIntOrNull() ?: 0)
    }

    fun entries(): List<Meta> = libraryDir.listFiles()
        ?.filter { it.isDirectory && File(it, activeDbName).exists() }
        ?.sortedBy { it.name }
        ?.map { metaOf(it) }
        .orEmpty()

    /**
     * Moves the active pack (closed by the caller) into the library under [meta]. Returns its folder,
     * or null when nothing was moved (the active pack is then unchanged).
     */
    fun stashActive(meta: Meta): File? {
        if (!activeDb.exists()) return null
        val dir = File(libraryDir, safeName(meta.packId).ifEmpty { "pack" + System.currentTimeMillis() })
        if (dir.exists()) dir.deleteRecursively()
        if (!dir.mkdirs()) return null
        for (f in activeFiles()) {
            if (!f.renameTo(File(dir, f.name))) {
                dir.listFiles()?.forEach { it.renameTo(File(packDir, it.name)) }
                dir.deleteRecursively()
                return null
            }
        }
        runCatching { File(dir, META).writeText("${meta.packId}\n${meta.rowCount}\n${meta.displayName}\n") }
        return dir
    }

    /** Moves a library pack into the (empty) active slot. Returns its meta, or null on failure. */
    fun restore(dir: File): Meta? {
        if (activeDb.exists()) return null
        val meta = metaOf(dir)
        dir.listFiles()?.filter { it.name != META }?.forEach { it.renameTo(File(packDir, it.name)) }
        if (!activeDb.exists()) return null
        dir.deleteRecursively()
        return meta
    }

    fun remove(packId: String): Boolean = entry(packId)?.deleteRecursively() == true

    companion object {
        const val META = "pack_meta.txt"
    }
}
