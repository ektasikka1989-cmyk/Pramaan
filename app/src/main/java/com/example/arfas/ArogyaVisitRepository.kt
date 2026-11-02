package com.example.arfas

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Separate encrypted, backup-excluded store; existing chat/legacy notes are not migrated or overwritten. */
class ArogyaVisitRepository(context: Context) : ArogyaVisitPersistence {
    private val dir = File(context.noBackupFilesDir, "arogyadoot_visits").apply { mkdirs() }
    private val record = AtomicFile(File(dir, "visits.enc"))
    init {
        // Remove plaintext PDF rendering scratch left by an interrupted previous process.
        context.noBackupFilesDir.listFiles()?.filter {
            it.isFile && (it.name.startsWith("arogya-ocr-") || it.name.startsWith("arogya-preview-")) && it.extension == "pdf"
        }?.forEach { it.delete() }
    }

    @Synchronized private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey("arogyadoot_visits_v1", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("arogyadoot_visits_v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    internal fun encrypt(bytes: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key())
        return byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + cipher.doFinal(bytes)
    }
    internal fun decrypt(bytes: ByteArray): ByteArray {
        require(bytes.isNotEmpty()); val length = bytes[0].toInt() and 255
        require(length == 12 && bytes.size > length + 16)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, length + 1)))
        return cipher.doFinal(bytes.copyOfRange(length + 1, bytes.size))
    }
    private fun write(file: AtomicFile, bytes: ByteArray) {
        val stream = file.startWrite()
        try { stream.write(encrypt(bytes)); file.finishWrite(stream) }
        catch (t: Throwable) { file.failWrite(stream); throw t }
    }
    @Synchronized fun all(): List<ArogyaVisit> {
        if (!record.baseFile.exists() && !File(record.baseFile.path + ".bak").exists()) return emptyList()
        val root = JSONObject(decrypt(record.readFully()).toString(Charsets.UTF_8))
        require(root.getInt("version") == 1) { "Unsupported visit store version" }
        val a = root.getJSONArray("visits")
        // A corrupt record raises an error. Never replace an unreadable store with an empty list.
        return (0 until a.length()).map { decode(a.getJSONObject(it)) }.also {rows->require(rows.map {it.id}.distinct().size==rows.size)}
    }
    override fun readVisits(): List<ArogyaVisit> = all()
    @Synchronized override fun writeVisits(visits: List<ArogyaVisit>) {
        val previous = all() // Never overwrite an unreadable store.
        val root = JSONObject().put("version", 1).put("visits", JSONArray().also { a -> visits.forEach { a.put(encode(it)) } })
        write(record, root.toString().toByteArray(Charsets.UTF_8))
        val retained = visits.flatMap { it.attachments }.map { it.id }.toSet()
        // Delete detached attachments only after the replacement record has committed.
        previous.flatMap { it.attachments }.filterNot { it.id in retained }.forEach {
            runCatching { deleteAttachment(it.id) }
        }
    }
    @Synchronized fun save(v: ArogyaVisit) {
        val rows = all().filterNot { it.id == v.id } + v
        val root = JSONObject().put("version",1).put("visits",JSONArray().also { a -> rows.forEach { a.put(encode(it)) } })
        write(record,root.toString().toByteArray(Charsets.UTF_8))
    }
    @Synchronized fun delete(id: String) {
        val rows = all(); val old = rows.firstOrNull { it.id == id } ?: return
        val root = JSONObject().put("version",1).put("visits",JSONArray().also { a -> rows.filterNot { it.id == id }.forEach { a.put(encode(it)) } })
        write(record,root.toString().toByteArray(Charsets.UTF_8))
        old.attachments.forEach { attachmentFile(it.id).delete() }
    }
    private fun attachmentFile(id: String): File {
        require(Regex("[a-fA-F0-9-]{36}").matches(id))
        return File(dir,"$id.enc")
    }
    @Synchronized fun saveAttachment(id: String, bytes: ByteArray) { require(bytes.size <= 15 * 1024 * 1024); write(AtomicFile(attachmentFile(id)),bytes) }
    @Synchronized fun attachment(id: String): ByteArray = decrypt(AtomicFile(attachmentFile(id)).readFully())
    @Synchronized fun deleteAttachment(id: String) { AtomicFile(attachmentFile(id)).delete() }
    companion object {
        fun encode(v: ArogyaVisit): JSONObject = JSONObject().apply {
            put("id",v.id); put("personId",v.personId); put("name",v.name); put("village",v.village); put("kind",v.kind.name)
            put("captureOriginal",v.captureOriginal)
            put("captureItems",JSONArray().also {a->v.captureItems.forEach {a.put(JSONObject().put("category",it.category).put("quote",it.quote).put("selectedByAi",it.selectedByAi))}})
            put("date",v.date); put("birthDate",v.birthDate); put("homeBirth",v.homeBirth); put("notes",v.notes)
            put("nextDate",v.nextDate); put("nextReason",v.nextReason); put("planConfirmed",v.planConfirmed)
            put("referral",v.referral.name); put("referralNote",v.referralNote); put("completed",v.completed)
            put("reviewed",v.reviewed); put("demo",v.demo); put("updatedAt",v.updatedAt); put("protocolVersion",v.protocolVersion)
            put("revision",v.revision);put("notesReviewedForDanger",v.notesReviewedForDanger);put("referralEpisodeId",v.referralEpisodeId);put("referralResolutionConfirmed",v.referralResolutionConfirmed)
            put("answers",JSONObject().also { o -> v.answers.forEach { (k,a) -> o.put(k,a.name) } })
            put("attachments",JSONArray().also { a -> v.attachments.forEach { a.put(JSONObject().put("id",it.id).put("label",it.label).put("mime",it.mime).put("extractedText",it.extractedText)) } })
            put("guideLog",JSONArray().also { a -> v.guideLog.forEach { e -> a.put(JSONObject().put("at",e.at).put("question",e.question).put("answer",e.answer)
                .put("quote",e.quote).put("quoteSource",e.quoteSource).put("provider",e.provider).put("elapsedMs",e.elapsedMs).put("warning",e.warning)
                .put("tier",e.tier).put("onlineAnswer",e.onlineAnswer).put("onlineTier",e.onlineTier)
                .put("onlineSources",JSONArray().also { oa -> e.onlineSources.forEach { s -> oa.put(encodeSource(s)) } })
                .put("sources",JSONArray().also { sa -> e.sources.forEach { s -> sa.put(encodeSource(s)) } })) } })
        }
        private fun encodeSource(s: ArogyaSource): JSONObject = JSONObject().put("id",s.id).put("title",s.title).put("locator",s.locator)
            .put("excerpt",s.excerpt).put("url",s.url).put("highlights",JSONArray(s.highlights))
            .put("kind",s.kind).put("date",s.date).put("domain",s.domain)
        private fun decodeSource(s: JSONObject): ArogyaSource { val h=s.optJSONArray("highlights") ?: JSONArray()
            return ArogyaSource(s.getString("id"),s.getString("title"),s.optString("locator",""),s.optString("excerpt",""),s.optString("url",""),
                (0 until h.length()).map { h.getString(it) },s.optString("kind","guide"),s.optString("date",""),s.optString("domain","")) }
        private fun decodeGuide(o: JSONObject): List<ArogyaGuideEntry> {
            val a = o.optJSONArray("guideLog") ?: return emptyList()
            return (0 until a.length()).mapNotNull { i -> runCatching { val e=a.getJSONObject(i); val sa=e.optJSONArray("sources") ?: JSONArray()
                val oa=e.optJSONArray("onlineSources") ?: JSONArray()
                ArogyaGuideEntry(e.getLong("at"),e.getString("question"),e.getString("answer"),e.optString("quote",""),e.optString("quoteSource",""),
                    (0 until sa.length()).map { j -> decodeSource(sa.getJSONObject(j)) },
                    e.optString("provider",""),e.optLong("elapsedMs",0),e.optString("warning",""),
                    e.optInt("tier",1),e.optString("onlineAnswer",""),
                    (0 until oa.length()).map { j -> decodeSource(oa.getJSONObject(j)) },e.optInt("onlineTier",0)) }.getOrNull() }
        }
        fun decode(o: JSONObject): ArogyaVisit {
            require(o.getString("id").isNotBlank() && o.getString("personId").isNotBlank())
            require(o.getString("protocolVersion") in ArogyaWorkflow.SUPPORTED_VERSIONS)
            require(o.optLong("revision",0)>=0)
            val answers=o.getJSONObject("answers"); val a=o.getJSONArray("attachments")
            return ArogyaVisit(id=o.getString("id"),personId=o.getString("personId"),name=o.getString("name"),village=o.getString("village"),
                kind=ArogyaKind.valueOf(o.getString("kind")),date=o.getString("date"),birthDate=o.getString("birthDate"),homeBirth=o.getBoolean("homeBirth"),
                captureOriginal=o.optString("captureOriginal",""),
                captureItems=(if(o.has("captureItems")) o.getJSONArray("captureItems") else null)?.let {items->(0 until items.length()).map {i->val item=items.getJSONObject(i);ArogyaCaptureItem(item.getString("category"),item.getString("quote"),item.getBoolean("selectedByAi"))}} ?: emptyList(),
                notes=o.getString("notes"),nextDate=o.getString("nextDate"),nextReason=o.getString("nextReason"),planConfirmed=o.getBoolean("planConfirmed"),
                referral=ArogyaReferral.valueOf(o.getString("referral")),referralNote=o.getString("referralNote"),completed=o.getBoolean("completed"),reviewed=o.getBoolean("reviewed"),
                demo=o.getBoolean("demo"),updatedAt=o.getLong("updatedAt"),protocolVersion=o.getString("protocolVersion"),revision=o.optLong("revision",0),notesReviewedForDanger=o.optBoolean("notesReviewedForDanger",false),referralEpisodeId=o.optString("referralEpisodeId",""),referralResolutionConfirmed=o.optBoolean("referralResolutionConfirmed",false),
                answers=answers.keys().asSequence().associateWith { ArogyaAnswer.valueOf(answers.getString(it)) },
                attachments=(0 until a.length()).map { val j=a.getJSONObject(it); ArogyaAttachment(j.getString("id"),j.getString("label"),j.getString("mime"),j.getString("extractedText")) },
                guideLog=decodeGuide(o))
        }
    }
}

/** Single repository/queue per app process; no Activity or composable is retained. */
object ArogyaVisitStore {
    private var instance: Pair<ArogyaVisitRepository, ArogyaVisitQueue>? = null
    @Synchronized fun get(context: Context): Pair<ArogyaVisitRepository, ArogyaVisitQueue> =
        instance ?: ArogyaVisitRepository(context.applicationContext).let { repository ->
            (repository to ArogyaVisitQueue(repository)).also { instance = it }
        }
}
