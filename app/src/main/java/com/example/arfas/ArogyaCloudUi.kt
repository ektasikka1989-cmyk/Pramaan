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
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.input.pointer.pointerInput
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
    suspend fun transcribe(c: Context, samples: FloatArray, languages: List<String> = listOf("Hindi", "English")): String? = withContext(Dispatchers.IO) {
        if (samples.size < 8_000 || !MetaOnline.ready(c)) return@withContext null
        val t = System.currentTimeMillis()
        val wav = java.io.File(c.cacheDir, "arogya_note_${System.currentTimeMillis()}.wav")
        try {
            MetaOnline.writeWav(samples, samples.size, 16_000, wav)
            val text = withDeadline(30_000) { kotlinx.coroutines.runBlocking { MetaOnline.api(c).transcribe("arogya_voice_note", wav, languages = languages, diarize = false).text } }
            Log.i(TAG, "AROGYA_VOICE_CLOUD model=muse-voice-transcribe ms=${System.currentTimeMillis() - t} sec=${samples.size / 16000} chars=${text?.length ?: -1} lang=${languages.joinToString("+")}")
            text?.ifBlank { null }
        } finally { wav.delete() }
    }

    fun status(c: Context): String {
        val ep = textEndpoint(c); val mk = metaKey(c).isNotBlank(); val tk = togetherKey(c).isNotBlank()
        return listOf(
            "Order: " + (if (cloudFirst(c)) "Cloud first" else "Local first") + " (from Settings, same as Ask SOP)",
            "Forms & text: " + (ep?.let { it.label + if (keyFor(c, it).isNotBlank()) " (key saved)" else " (no key — phone model used)" } ?: "phone model only (offline)"),
            "Voice: " + if (cloudFirst(c)) (if (mk) "Muse Voice Transcribe first, Whisper if offline" else "Whisper on the phone (add the Meta key for Muse)") else "Whisper only (on this phone)",
            "Reading aloud: " + if (cloudFirst(c)) "best Android voice (online voice when there is signal)" else "offline Android voice",
            "Photo counting: " + if (mk) "SAM 3.1 when online" else "type the count (needs the Meta key)",
            "Keys: Meta ${if (mk) "✓ (Settings)" else "— add it in Settings"} · Together ${if (tk) "✓" else "—"}"
        ).joinToString("\n")
    }
}

/** One place to switch on Meta cloud models and save keys; includes a live connection test. */
@Composable
fun ArogyaCloudSettingsDialog(hindi: Boolean = false, onDismiss: () -> Unit) {
    fun tr(en: String, hi: String) = if (hindi) hi else en
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
    AlertDialog(onDismissRequest = onDismiss, title = { Text(tr("☁ Cloud or phone AI", "☁ क्लाउड या फ़ोन AI")) }, text = {
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(tr("One setting for every AI action — answers, voice, forms, card reading, messages. Cloud first: Meta's cloud models, and the phone takes over automatically with no signal, a slow network or an error. Local first: the phone, and the cloud only if the phone cannot do it. The Meta key is in the app's Settings.",
                "हर AI काम के लिए एक सेटिंग — जवाब, आवाज़, फ़ॉर्म, कार्ड पढ़ना, संदेश। क्लाउड पहले: Meta के क्लाउड मॉडल, और बिना सिग्नल, धीमे नेटवर्क या गलती पर फ़ोन अपने आप ले लेता है। फ़ोन पहले: फ़ोन, और क्लाउड तभी जब फ़ोन न कर पाए। Meta key ऐप की Settings में है।"),
                style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = cloudFirst, onClick = { cloudFirst = true }, label = { Text(tr("☁ Cloud first", "☁ क्लाउड पहले")) })
                FilterChip(selected = !cloudFirst, onClick = { cloudFirst = false }, label = { Text(tr("📱 Local first", "📱 फ़ोन पहले")) })
            }
            if (saved.isNotBlank()) Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) { Text(saved, Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall) }
            Text(tr("Cloud model for forms, messages and card reading", "फ़ॉर्म, संदेश और कार्ड पढ़ने के लिए क्लाउड मॉडल"), fontWeight = FontWeight.Bold)
            ArogyaModelEndpoints.all.map { it.id to it.label }.forEach { (id, label) ->
                Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(choice == id, { choice = id }); Text(label, style = MaterialTheme.typography.bodyMedium) }
            }
            Text(tr("Together AI key — Muse Glimmer 30B", "Together AI key — Muse Glimmer 30B"), fontWeight = FontWeight.Bold)
            OutlinedTextField(togetherKey, { togetherKey = it.trim() }, label = { Text(tr("Paste key (blank = keep saved)", "key चिपकाएँ (खाली = पुरानी रहेगी)")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            OutlinedButton(onClick = {
                persist(); testing = true; test = ""
                scope.launch {
                    test = withContext(Dispatchers.IO) {
                        val ep = ArogyaModelEndpoints.byId(choice) ?: return@withContext tr("Choose a cloud model first.", "पहले क्लाउड मॉडल चुनें।")
                        if (!ArogyaCloud.online(context)) return@withContext tr("No internet right now.", "अभी इंटरनेट नहीं है।")
                        val key = ArogyaCloud.keyFor(context, ep); if (key.isBlank()) return@withContext tr("No key saved for ${ep.label}.", "${ep.label} के लिए key नहीं है।")
                        val t = System.currentTimeMillis()
                        try {
                            val r = OpenAiCompatibleClient(ep, { key }, 30_000).complete("You are a health assistant.", "Name one ORS ingredient in one sentence.", 900)
                            Log.i("ArogyaDemo", "AROGYA_CLOUD_TEST ok model=${ep.model} ms=${System.currentTimeMillis() - t} reply=${r.take(20)}")
                            "✓ ${ep.label} " + tr("answered in ", "ने जवाब दिया ") + "${System.currentTimeMillis() - t} ms: \"${r.take(20)}\""
                        } catch (e: CancellationException) { throw e } catch (e: Exception) {
                            Log.w("ArogyaDemo", "AROGYA_CLOUD_TEST failed model=${ep.model} ${e.message?.take(200)}"); "✗ ${e.message?.take(160)}"
                        }
                    }
                    saved = withContext(Dispatchers.IO) { ArogyaCloud.status(context) }; testing = false
                }
            }, enabled = !testing) { Text(if (testing) tr("Testing…", "जाँच…") else tr("Save and test connection", "सहेजें और कनेक्शन जाँचें")) }
            if (test.isNotBlank()) Text(test, style = MaterialTheme.typography.bodySmall, color = if (test.startsWith("✓")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(onClick = { persist(); onDismiss() }) { Text(tr("Save", "सहेजें")) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel", "रद्द करें")) } })
}

/** Numbered dots on the counted photo: tap a dot to remove it, tap empty space to add one. */
@Composable
private fun DotsPhoto(bmp: Bitmap, dots: List<CountDot>, onTap: (Float, Float) -> Unit) {
    val tap = rememberUpdatedState(onTap)
    val ratio = bmp.height.toFloat() / bmp.width.coerceAtLeast(1)
    BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(1f / ratio)
        .pointerInput(bmp) { detectTapGestures { off -> tap.value(off.x / size.width, off.y / size.height) } }) {
        Image(bmp.asImageBitmap(), contentDescription = "Photo of drug kit items", modifier = Modifier.fillMaxSize())
        val w = maxWidth
        dots.forEachIndexed { i, d ->
            Box(Modifier.offset(x = (w * d.x) - 13.dp, y = (w * ratio * d.y) - 13.dp).size(26.dp)
                .background(Color(0xFF00C853), CircleShape), contentAlignment = Alignment.Center) {
                Text("${i + 1}", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * Count drug-kit items from a photo with numbered dots the ASHA fixes by tapping.
 * Online + Cloud first uses SAM 3.1; otherwise the on-phone MobileSAM + DINOv2
 * counter (taught by tapping one example item once); manual tap-to-count always
 * works as a fallback. Nothing reaches the register until she confirms the count.
 */
@Composable
fun SamCountDialog(hindi: Boolean = false, balances: Map<String, Int>, vision: MetaVisionEngine? = null,
    onRecord: (item: String, counted: Int, note: String) -> Unit, onDismiss: () -> Unit) {
    fun tr(en: String, hi: String) = if (hindi) hi else en
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var item by remember { mutableStateOf(ArogyaRegistryLogic.DRUG_KIT.first()) }
    var phrase by remember { mutableStateOf(ArogyaKitPhrases.phrase(item)) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var dots by remember { mutableStateOf<List<CountDot>>(emptyList()) }
    var foundN by remember { mutableStateOf(0) }
    var phase by remember { mutableStateOf("") }   // "" | teach | counting | done | manual
    var engine by remember { mutableStateOf("") }  // mobilesam_dino | dino_grid | sam31 | manual
    var engineMs by remember { mutableStateOf(0L) }
    var per by remember { mutableStateOf(ArogyaKitPhrases.perItem(item).toString()) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var prog by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var shotUri by remember { mutableStateOf<Uri?>(null) }

    fun engineLabel() = when (engine) {
        "mobilesam_dino" -> "MobileSAM + DINOv2 (estimate)"
        "dino_grid" -> "DINOv2 grid (estimate)"
        "sam31" -> "SAM 3.1 (Meta)"
        else -> tr("Counted by tapping", "उँगली से गिना")
    }

    fun runPhone(bmp: Bitmap) {
        val v = vision ?: return
        busy = true; phase = "counting"; prog = 0 to 0
        msg = tr("Looking for each item…", "हर सामान ढूँढ रहे हैं…")
        scope.launch {
            try {
                val t = System.currentTimeMillis()
                val res = v.countTaught(item, bmp) { done, total -> prog = done to total }
                dots = ArogyaCountDots.fromBoxes(res.boxes.map { it.box })
                foundN = dots.size; engineMs = System.currentTimeMillis() - t
                engine = if (res.usedSam) "mobilesam_dino" else "dino_grid"
                phase = "done"; prog = null
                msg = if (dots.isEmpty()) tr("Found nothing like it — tap each item yourself, or re-tap the example.",
                    "ऐसा कुछ नहीं मिला — हर सामान खुद दबाएँ, या नमूना दोबारा दबाएँ।")
                else tr("Found ${dots.size}. Tap a dot to remove it, tap empty space to add.",
                    "${dots.size} मिला। हटाने के लिए निशान दबाएँ, जोड़ने के लिए खाली जगह दबाएँ।")
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                phase = "manual"; prog = null
                msg = tr("Could not count", "गिन नहीं पाए") + ": ${e.message?.take(140)}. " + tr("Tap each item yourself.", "हर सामान खुद दबाएँ।")
            } finally { busy = false }
        }
    }

    fun runSam31(jpegBytes: ByteArray) {
        busy = true; phase = "counting"; prog = null
        msg = tr("SAM 3.1 is finding each item…", "SAM 3.1 हर सामान ढूँढ रहा है…")
        scope.launch {
            try {
                val t = System.currentTimeMillis()
                val found = MetaOnline.api(context).sam("arogya_kit_count", jpegBytes, phrase.ifBlank { "packet" })
                val conf = found.filter { (it.confidence ?: 1f) >= 0.5f }
                val boxes = conf.map {
                    val w = it.imageWidth.coerceAtLeast(1).toFloat(); val h = it.imageHeight.coerceAtLeast(1).toFloat()
                    MetaVisionMath.Box(it.left / w, it.top / h, (it.right - it.left) / w, (it.bottom - it.top) / h)
                }
                val keep = MetaVisionMath.nms(boxes, conf.map { it.confidence ?: 1f })
                dots = ArogyaCountDots.fromBoxes(keep.map { boxes[it] })
                foundN = dots.size; engineMs = System.currentTimeMillis() - t
                engine = "sam31"; phase = "done"
                msg = if (dots.isEmpty()) tr("Found nothing — tap each item yourself.", "कुछ नहीं मिला — हर सामान खुद दबाएँ।")
                else tr("Found ${dots.size}. Tap a dot to remove it, tap empty space to add.",
                    "${dots.size} मिला। हटाने के लिए निशान दबाएँ, जोड़ने के लिए खाली जगह दबाएँ।")
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                phase = "manual"
                msg = tr("Could not count", "गिन नहीं पाए") + ": ${e.message?.take(140)}. " + tr("Tap each item yourself.", "हर सामान खुद दबाएँ।")
                Log.w("ArogyaDemo", "AROGYA_SAM_FAILED ${e.message?.take(200)}")
            } finally { busy = false }
        }
    }

    fun run(uri: Uri, temp: Boolean) {
        busy = true; msg = ""; dots = emptyList(); foundN = 0; engine = ""; prog = null; phase = ""
        scope.launch {
            try {
                val (bmp, jpegBytes) = withContext(Dispatchers.IO) {
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
                val cloud = withContext(Dispatchers.IO) { ArogyaCloud.cloudFirst(context) && MetaOnline.ready(context) }
                val canPhone = vision != null && vision.has(MetaModelStore.Component.DINOV2)
                busy = false
                if (cloud) runSam31(jpegBytes)
                else if (canPhone && vision!!.rememberedLabels().any { it.equals(item, ignoreCase = true) }) runPhone(bmp)
                else if (canPhone) {
                    phase = "teach"
                    msg = tr("Tap one $item in the photo, so the phone learns what to count.",
                        "फ़ोटो में एक $item दबाएँ, ताकि फ़ोन सीखे कि क्या गिनना है।")
                } else {
                    phase = "manual"
                    msg = tr("No counter available — tap each item yourself.", "गिनती नहीं हो पाई — हर सामान खुद दबाएँ।")
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                busy = false; phase = "manual"
                msg = tr("Could not open the photo", "फ़ोटो नहीं खुली") + ": ${e.message?.take(140)}"
            } finally { if (temp) runCatching { context.contentResolver.delete(uri, null, null) } }
        }
    }

    fun onPhotoTap(x: Float, y: Float) {
        val bmp = bitmap ?: return
        if (phase == "teach" && vision != null) {
            busy = true
            msg = tr("Learning this item…", "ये सामान सीख रहे हैं…")
            scope.launch {
                try {
                    val box = MetaVisionMath.Box((x - 0.125f).coerceIn(0f, 0.75f), (y - 0.125f).coerceIn(0f, 0.75f), 0.25f, 0.25f)
                    if (!vision.rememberBox(item, bmp, box)) throw IllegalStateException(tr("Teaching failed", "सीख नहीं पाए"))
                    busy = false
                    runPhone(bmp)
                } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    busy = false; phase = "manual"
                    msg = tr("Could not learn it", "सीख नहीं पाए") + ": ${e.message?.take(120)}. " + tr("Tap each item yourself.", "हर सामान खुद दबाएँ।")
                }
            }
        } else if (phase == "done" || phase == "manual") {
            dots = ArogyaCountDots.toggle(dots, x, y)
            if (engine.isEmpty()) engine = "manual"
        }
    }

    val shoot = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> val u = shotUri; shotUri = null; if (u != null) { if (ok) run(u, true) else runCatching { context.contentResolver.delete(u, null, null) } } }
    val camPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { g ->
        if (g) { val u = runCatching { context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "kit_${System.currentTimeMillis()}.jpg"); put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg") }) }.getOrNull()
            if (u != null) { shotUri = u; runCatching { shoot.launch(u) } } }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) run(u, false) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Row(verticalAlignment = Alignment.CenterVertically) { Text(tr("📷 Count stock from a photo", "📷 फ़ोटो से स्टॉक गिनें"), Modifier.weight(1f)); AiBadge("Count drug kit") } }, text = {
        Column(Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(tr("Lay the items out on a plain cloth, not overlapping, and take one photo.", "सामान सादे कपड़े पर अलग-अलग रखें और एक फ़ोटो लें।"), style = MaterialTheme.typography.bodySmall)
            ArogyaRegistryLogic.DRUG_KIT.take(11).forEach { i -> Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(item == i, { item = i; phrase = ArogyaKitPhrases.phrase(i); per = ArogyaKitPhrases.perItem(i).toString()
                    dots = emptyList(); foundN = 0; engine = ""; phase = "" }); Text("$i  (register: ${balances[i] ?: 0})", style = MaterialTheme.typography.bodyMedium) } }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(phrase, { phrase = it.take(40) }, label = { Text(tr("What to look for (English)", "क्या ढूँढें (English में)")) }, singleLine = true, modifier = Modifier.weight(2f))
                OutlinedTextField(per, { per = it.filter { c -> c.isDigit() }.take(3) }, label = { Text(tr("Units each", "प्रति नग")) }, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { camPerm.launch(android.Manifest.permission.CAMERA) }, enabled = !busy) { Text(tr("📷 Take photo", "📷 फ़ोटो लें")) }
                OutlinedButton(onClick = { pick.launch(arrayOf("image/*")) }, enabled = !busy) { Text(tr("Choose photo", "फ़ोटो चुनें")) }
            }
            if (bitmap != null && (phase == "done" || phase == "manual" || phase == "teach")) {
                OutlinedButton(onClick = { phase = "manual"; if (engine.isEmpty()) engine = "manual"
                    msg = tr("Tap each item yourself.", "हर सामान खुद दबाएँ।") }, enabled = !busy) { Text(tr("Count by tapping", "उँगली से गिनें")) }
            }
            if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(prog?.let { (done, total) -> tr("Looking… $done/$total", "देख रहे हैं… $done/$total") } ?: msg, style = MaterialTheme.typography.bodySmall) }
            bitmap?.let { bmp -> if (phase == "done" || phase == "manual" || phase == "teach") DotsPhoto(bmp, dots) { x, y -> if (!busy) onPhotoTap(x, y) } }
            if (msg.isNotBlank() && !busy) Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            if (engine == "mobilesam_dino" || engine == "dino_grid") AiResultChip(engineLabel(), false, engineMs, hindi)
            if (engine == "sam31") AiResultChip(engineLabel(), true, engineMs, hindi)
            if (engine.isNotBlank()) ConfirmChip(hindi)
            if (phase == "done" || phase == "manual") {
                val n = dots.size * (per.toIntOrNull() ?: 1)
                Text(tr("Count: $n", "गिनती: $n") + (if ((per.toIntOrNull() ?: 1) > 1) " (${dots.size} × $per " + tr("per item", "प्रति नग") + ")" else ""),
                    fontWeight = FontWeight.Bold)
                val diff = n - (balances[item] ?: 0)
                Text(if (diff == 0) tr("Matches the register ✓", "रजिस्टर से मिला ✓") else tr("Register says ${balances[item] ?: 0}; difference ${if (diff > 0) "+" else ""}$diff will be recorded as a stock check.", "रजिस्टर में ${balances[item] ?: 0}; अंतर ${if (diff > 0) "+" else ""}$diff स्टॉक जाँच में दर्ज होगा।"),
                    color = if (diff == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }, confirmButton = { Button(onClick = {
            val n = dots.size * (per.toIntOrNull() ?: 1)
            val eng = engine.ifBlank { "manual" }
            Log.i("ArogyaDemo", "AROGYA_COUNT engine=$eng item=\"$item\" found=$foundN confirmed=${dots.size} ms=$engineMs")
            onRecord(item, n, "Stock check by photo (${engineLabel()} found $foundN, confirmed ${dots.size})")
        }, enabled = !busy && (phase == "done" || phase == "manual")) { Text(tr("Save count", "गिनती सहेजें")) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(tr("Cancel", "रद्द करें")) } })
}
