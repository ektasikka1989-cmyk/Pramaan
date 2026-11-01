package com.example.arfas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A second encrypted key slot (Together AI), same scheme as MetaKeyStore: AES-GCM key in AndroidKeyStore, only ciphertext in prefs. */
class ArogyaKeyVault(context: Context, private val name: String) {
    private val prefs = context.getSharedPreferences("arogya_keys", Context.MODE_PRIVATE)
    private val alias = "arogya_key_$name"
    private fun secret(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }
    fun get(): String = runCatching {
        val c = prefs.getString("${name}_c", null) ?: return ""; val iv = prefs.getString("${name}_iv", null) ?: return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        String(cipher.doFinal(Base64.decode(c, Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrDefault("")
    fun save(v: String) {
        if (v.isBlank()) { prefs.edit().remove("${name}_c").remove("${name}_iv").apply(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, secret())
        prefs.edit().putString("${name}_c", Base64.encodeToString(cipher.doFinal(v.trim().toByteArray()), Base64.NO_WRAP))
            .putString("${name}_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).apply()
    }
}

/** Which cloud models are switched on, and their keys. Offline (phone) is always the fallback. */
object ArogyaCloud {
    private const val TAG = "ArogyaDemo"
    fun prefs(c: Context) = c.getSharedPreferences("arogya_model", Context.MODE_PRIVATE)
    /** Cloud model used for ArogyaDoot's AI: Muse Spark by default, or Muse Glimmer. */
    fun textEndpoint(c: Context) = ArogyaModelEndpoints.byId(prefs(c).getString("endpoint", "meta") ?: "meta") ?: ArogyaModelEndpoints.byId("meta")
    /** The Meta key saved in Pramaan's settings (GroqKeyStore). Voice and SAM go through Pramaan's MetaOnline client. */
    fun metaKey(c: Context) = runCatching { GroqKeyStore(c.applicationContext).metaKey() }.getOrDefault("")
    fun togetherKey(c: Context) = ArogyaKeyVault(c.applicationContext, "together").get()
    fun keyFor(c: Context, ep: ArogyaModelEndpoint) = if (ep.keyFor == "together") togetherKey(c) else metaKey(c)
    fun online(c: Context) = MetaOnline.hasInternet(c)
    fun setCloudFirst(c: Context, on: Boolean) = AnswerPolicyStore(c.applicationContext).set(if (on) AnswerPolicy.CLOUD_FIRST else AnswerPolicy.LOCAL_FIRST)

    /** For the AI router (call off the main thread): cloud model + key if usable right now, and the order. */
    fun choice(c: Context): ArogyaCloudChoice {
        val (ep, key) = textChoice(c); return ArogyaCloudChoice(ep, key, cloudFirst(c))
    }

    /** Runs a blocking network call but gives up waiting after [ms] (weak village signal); null = too slow or failed. */
    suspend fun <T> withDeadline(ms: Long, block: () -> T): T? {
        val job = CoroutineScope(Dispatchers.IO).async { runCatching(block) }
        val r = withTimeoutOrNull(ms) { job.await() } ?: run { job.cancel(); Log.w(TAG, "AROGYA_CLOUD_TIMEOUT ms=$ms"); return null }
        return r.onFailure { Log.w(TAG, "AROGYA_CLOUD_FAILED ${it.message?.take(200)}") }.getOrNull()
    }

    /** The app's own Settings choice (Local first / Cloud first) — the same one Ask SOP uses. */
    fun cloudFirst(c: Context) = runCatching { AnswerPolicyStore(c.applicationContext).get() == AnswerPolicy.CLOUD_FIRST }.getOrDefault(false)

    /** Cloud text model for form filling, or null → on-phone Llama. Call off the main thread. */
    fun textChoice(c: Context): Pair<ArogyaModelEndpoint?, String> {
        val ep = textEndpoint(c) ?: return null to ""
        if (!online(c)) { Log.i(TAG, "AROGYA_CLOUD_SKIP offline model=${ep.model}"); return null to "" }
        val k = keyFor(c, ep); return if (k.isBlank()) null to "" else ep to k
    }

    /** Muse Voice Transcribe (online, Meta key); null → use Whisper on the phone. The Settings order decides when it is tried. */
    suspend fun transcribe(c: Context, samples: FloatArray): String? = withContext(Dispatchers.IO) {
        if (samples.size < 8_000 || !MetaOnline.ready(c)) return@withContext null
        val t = System.currentTimeMillis()
        val wav = java.io.File(c.cacheDir, "arogya_note_${System.currentTimeMillis()}.wav")
        try {
            MetaOnline.writeWav(samples, samples.size, 16_000, wav)
            val text = withDeadline(30_000) { kotlinx.coroutines.runBlocking { MetaOnline.api(c).transcribe("arogya_voice_note", wav, diarize = false).text } }
            Log.i(TAG, "AROGYA_VOICE_CLOUD model=muse-voice-transcribe ms=${System.currentTimeMillis() - t} sec=${samples.size / 16000} chars=${text?.length ?: -1}")
            text?.ifBlank { null }
        } finally { wav.delete() }
    }

    fun status(c: Context): String {
        val ep = textEndpoint(c); val mk = metaKey(c).isNotBlank(); val tk = togetherKey(c).isNotBlank()
        return listOf(
            "Order: " + (if (cloudFirst(c)) "Cloud first" else "Local first") + " (from Settings, same as Ask SOP)",
            "Forms & text: " + (ep?.let { it.label + if (keyFor(c, it).isNotBlank()) " (key saved)" else " (no key — phone model used)" } ?: "phone model only (offline)"),
            "Voice: " + if (mk) (if (cloudFirst(c)) "Muse Voice Transcribe first, Whisper if offline" else "Whisper first, Muse Voice Transcribe if Whisper hears nothing") else "Whisper on the phone (add the Meta key for Muse)",
            "Reading aloud: " + if (cloudFirst(c)) "best Android voice (online voice when there is signal)" else "offline Android voice",
            "Photo counting: " + if (mk) "SAM 3.1 when online" else "type the count (needs the Meta key)",
            "Keys: Meta ${if (mk) "✓ (Settings)" else "— add it in Settings"} · Together ${if (tk) "✓" else "—"}"
        ).joinToString("\n")
    }
}

/** One place to switch on Meta cloud models and save keys; includes a live connection test. */
@Composable
fun ArogyaCloudSettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val p = remember { ArogyaCloud.prefs(context) }
    var choice by remember { mutableStateOf(ArogyaCloud.textEndpoint(context)?.id ?: "meta") }
    var cloudFirst by remember { mutableStateOf(ArogyaCloud.cloudFirst(context)) }
    var togetherKey by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf("") }
    var test by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { saved = withContext(Dispatchers.IO) { ArogyaCloud.status(context) } }
    fun persist() {
        p.edit().putString("endpoint", choice).apply()
        ArogyaCloud.setCloudFirst(context, cloudFirst)
        if (togetherKey.isNotBlank()) runCatching { ArogyaKeyVault(context.applicationContext, "together").save(togetherKey) }
        togetherKey = ""
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("☁ Cloud or phone AI") }, text = {
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("One setting for every AI action — answers, voice, forms, card reading, messages. Cloud first: Meta's cloud models, and the phone takes over automatically with no signal, a slow network or an error. Local first: the phone, and the cloud only if the phone cannot do it. The Meta key is in the app's Settings.",
                style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = cloudFirst, onClick = { cloudFirst = true }, label = { Text("☁ Cloud first") })
                FilterChip(selected = !cloudFirst, onClick = { cloudFirst = false }, label = { Text("📱 Local first") })
            }
            if (saved.isNotBlank()) Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) { Text(saved, Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall) }
            Text("Cloud model for forms, messages and card reading", fontWeight = FontWeight.Bold)
            ArogyaModelEndpoints.all.map { it.id to it.label }.forEach { (id, label) ->
                Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(choice == id, { choice = id }); Text(label, style = MaterialTheme.typography.bodyMedium) }
            }
            Text("Together AI key — Muse Glimmer 30B", fontWeight = FontWeight.Bold)
            OutlinedTextField(togetherKey, { togetherKey = it.trim() }, label = { Text("Paste key (blank = keep saved)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            OutlinedButton(onClick = {
                persist(); testing = true; test = ""
                scope.launch {
                    test = withContext(Dispatchers.IO) {
                        val ep = ArogyaModelEndpoints.byId(choice) ?: return@withContext "Choose a cloud model first."
                        if (!ArogyaCloud.online(context)) return@withContext "No internet right now."
                        val key = ArogyaCloud.keyFor(context, ep); if (key.isBlank()) return@withContext "No key saved for ${ep.label}."
                        val t = System.currentTimeMillis()
                        try {
                            val r = OpenAiCompatibleClient(ep, { key }, 30_000).complete("You are a health assistant.", "Name one ORS ingredient in one sentence.", 900)
                            Log.i("ArogyaDemo", "AROGYA_CLOUD_TEST ok model=${ep.model} ms=${System.currentTimeMillis() - t} reply=${r.take(20)}")
                            "✓ ${ep.label} answered in ${System.currentTimeMillis() - t} ms: \"${r.take(20)}\""
                        } catch (e: CancellationException) { throw e } catch (e: Exception) {
                            Log.w("ArogyaDemo", "AROGYA_CLOUD_TEST failed model=${ep.model} ${e.message?.take(200)}"); "✗ ${e.message?.take(160)}"
                        }
                    }
                    saved = withContext(Dispatchers.IO) { ArogyaCloud.status(context) }; testing = false
                }
            }, enabled = !testing) { Text(if (testing) "Testing…" else "Save and test connection") }
            if (test.isNotBlank()) Text(test, style = MaterialTheme.typography.bodySmall, color = if (test.startsWith("✓")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(onClick = { persist(); onDismiss() }) { Text("Save") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

/** Count drug-kit items in a photo with SAM 3.1; the ASHA confirms the number before it goes in the register. */
@Composable
fun SamCountDialog(balances: Map<String, Int>, onRecord: (item: String, counted: Int, note: String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var item by remember { mutableStateOf(ArogyaRegistryLogic.DRUG_KIT.first()) }
    var phrase by remember { mutableStateOf(ArogyaKitPhrases.phrase(item)) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var boxes by remember { mutableStateOf<List<SamBox>>(emptyList()) }   // Pramaan's SamBox (SamRecordParser)
    var counted by remember { mutableStateOf("") }
    var per by remember { mutableStateOf(ArogyaKitPhrases.perItem(item).toString()) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var shotUri by remember { mutableStateOf<Uri?>(null) }
    fun run(uri: Uri, temp: Boolean) {
        busy = true; msg = ""; boxes = emptyList()
        scope.launch {
            try {
                val (bmp, jpeg) = withContext(Dispatchers.IO) {
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                    var sample = 1; while (maxOf(opts.outWidth, opts.outHeight) / sample > 2000) sample *= 2
                    val raw = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
                        ?: throw IllegalStateException("Could not open the photo")
                    val scale = 1280f / maxOf(raw.width, raw.height)
                    val b = if (scale < 1f) Bitmap.createScaledBitmap(raw, (raw.width * scale).toInt(), (raw.height * scale).toInt(), true) else raw
                    b to ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
                }
                bitmap = bmp
                if (!withContext(Dispatchers.IO) { MetaOnline.ready(context) }) { msg = "SAM 3.1 needs internet and the Meta key (Settings). You can still type the count."; return@launch }
                val t = System.currentTimeMillis()
                val found = MetaOnline.api(context).sam("arogya_kit_count", jpeg, phrase.ifBlank { "packet" })
                boxes = found; val n = ArogyaSamCount.count(found.map { it.confidence }); counted = (n * (per.toIntOrNull() ?: 1)).toString()
                msg = "SAM 3.1 found $n \"$phrase\" in ${(System.currentTimeMillis() - t) / 1000}s" + (if ((per.toIntOrNull() ?: 1) > 1) " × $per per item" else "") + ". Check the boxes and correct the number if needed."
                Log.i("ArogyaDemo", "AROGYA_SAM_COUNT item=\"$item\" phrase=\"$phrase\" found=${found.size} counted=$n ms=${System.currentTimeMillis() - t}")
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                msg = "Could not count: ${e.message?.take(140)}. Type the count yourself."; Log.w("ArogyaDemo", "AROGYA_SAM_FAILED ${e.message?.take(200)}")
            } finally { busy = false; if (temp) runCatching { context.contentResolver.delete(uri, null, null) } }
        }
    }
    val shoot = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> val u = shotUri; shotUri = null; if (u != null) { if (ok) run(u, true) else runCatching { context.contentResolver.delete(u, null, null) } } }
    val camPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { g ->
        if (g) { val u = runCatching { context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "kit_${System.currentTimeMillis()}.jpg"); put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg") }) }.getOrNull()
            if (u != null) { shotUri = u; runCatching { shoot.launch(u) } } }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) run(u, false) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Row(verticalAlignment = Alignment.CenterVertically) { Text("📷 Count stock from a photo", Modifier.weight(1f)); AiBadge("Count drug kit") } }, text = {
        Column(Modifier.heightIn(max = 600.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Lay the items out on a plain cloth, not overlapping, and take one photo.", style = MaterialTheme.typography.bodySmall)
            ArogyaRegistryLogic.DRUG_KIT.take(11).forEach { i -> Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(item == i, { item = i; phrase = ArogyaKitPhrases.phrase(i); per = ArogyaKitPhrases.perItem(i).toString() }); Text("$i  (register: ${balances[i] ?: 0})", style = MaterialTheme.typography.bodyMedium) } }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(phrase, { phrase = it.take(40) }, label = { Text("What to look for (English)") }, singleLine = true, modifier = Modifier.weight(2f))
                OutlinedTextField(per, { per = it.filter { c -> c.isDigit() }.take(3) }, label = { Text("Units each") }, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { camPerm.launch(android.Manifest.permission.CAMERA) }, enabled = !busy) { Text("📷 Take photo") }
                OutlinedButton(onClick = { pick.launch(arrayOf("image/*")) }, enabled = !busy) { Text("Choose photo") }
            }
            if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("SAM 3.1 is finding each item…", style = MaterialTheme.typography.bodySmall) }
            bitmap?.let { bmp -> Box(Modifier.fillMaxWidth().aspectRatio(bmp.width.toFloat() / bmp.height)) {
                Image(bmp.asImageBitmap(), contentDescription = "Photo of drug kit items", modifier = Modifier.fillMaxSize())
                Canvas(Modifier.fillMaxSize()) { boxes.forEach { bx ->
                    val sx = size.width / bx.imageWidth.coerceAtLeast(1); val sy = size.height / bx.imageHeight.coerceAtLeast(1)
                    drawRect(if ((bx.confidence ?: 1f) >= 0.5f) Color(0xFF00C853) else Color(0xFFFFAB00), Offset(bx.left * sx, bx.top * sy), Size((bx.right - bx.left) * sx, (bx.bottom - bx.top) * sy), style = Stroke(3f))
                } }
            } }
            if (msg.isNotBlank()) Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            OutlinedTextField(counted, { counted = it.filter { c -> c.isDigit() }.take(4) }, label = { Text("Count you confirm") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            counted.toIntOrNull()?.let { n -> val diff = n - (balances[item] ?: 0)
                Text(if (diff == 0) "Matches the register ✓" else "Register says ${balances[item] ?: 0}; difference ${if (diff > 0) "+" else ""}$diff will be recorded as a stock check.",
                    color = if (diff == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = { Button(onClick = { counted.toIntOrNull()?.let { onRecord(item, it, if (boxes.isNotEmpty()) "Stock check by photo (SAM 3.1 found ${ArogyaSamCount.count(boxes.map { it.confidence })})" else "Stock check (counted by hand)") } },
        enabled = !busy && counted.toIntOrNull() != null) { Text("Save count") } }, dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } })
}
