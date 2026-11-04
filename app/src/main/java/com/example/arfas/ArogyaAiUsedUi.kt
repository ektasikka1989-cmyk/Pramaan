package com.example.arfas

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

/**
 * Small tag beside an AI result: "✨ Muse Spark 1.3 ☁". Tap it → a small box names every step that made this
 * answer (e.g. "Phone: ML Kit + rules → incomplete; Meta Muse Spark 1.3, online"). Tapping anywhere else closes it.
 * Shows only what actually ran for this answer (the engine text recorded by the feature), never a guess.
 */
@Composable
fun AiUsedTag(engine: String, hindi: Boolean, ms: Long = 0L, modifier: Modifier = Modifier) {
    if (engine.isBlank()) return
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.clickable { open = !open }) {
            Text(ArogyaAiUsed.short(engine), fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
        }
        if (open) AiStepsPopup(engine, hindi, ms, null) { open = false }
    }
}

/** The small box that names each step behind an answer. Closes on any tap outside it (or on it). */
@Composable
fun AiStepsPopup(engine: String, hindi: Boolean, ms: Long, onlineHint: Boolean?, onDismiss: () -> Unit) {
    fun tr(en: String, hi: String) = if (hindi) hi else en
    val steps = remember(engine) { ArogyaAiUsed.steps(engine) }
    Popup(alignment = Alignment.TopStart, offset = IntOffset(0, 64), onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, dismissOnClickOutside = true, dismissOnBackPress = true)) {
        Surface(shape = RoundedCornerShape(10.dp), tonalElevation = 6.dp, shadowElevation = 6.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.widthIn(max = 300.dp).clickable { onDismiss() }) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(tr("How this answer was made", "यह उत्तर कैसे बना"), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                steps.forEachIndexed { i, s ->
                    val online = if (s.maker.isBlank() && onlineHint != null) onlineHint else s.online
                    val where = if (online) tr("☁ online", "☁ ऑनलाइन") else tr("📴 on the phone", "📴 फ़ोन पर")
                    Text("${i + 1}. " + (if (s.notUsed) "✗ " else if (s.ai) "✨ " else "⚙ ") + s.model + (if (s.maker.isNotBlank()) " · ${s.maker}" else "") + " · $where" +
                        (if (s.notUsed) tr(" — tried, not used: ", " — कोशिश की, इस्तेमाल नहीं: ") + s.why else ""), fontSize = 12.sp,
                        color = if (s.notUsed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                }
                if (steps.size > 1) Text(tr("Steps run in this order; the last one gave the answer shown.", "कदम इसी क्रम में चले; दिखा उत्तर आखिरी कदम का है।"), fontSize = 11.sp)
                if (ms > 0) Text(tr("Time: ", "समय: ") + if (ms < 1000) "<1 s" else "${ms / 1000} s", fontSize = 11.sp)
                Text(tr("You check and approve every AI answer.", "हर AI उत्तर आप जाँचकर पक्का करती हैं।"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * Shown when the phone's offline reading of a photo is incomplete: what was captured, what was not, and a button
 * to read the same photo again online with Muse Spark (Meta). Nothing is sent until the ASHA presses it.
 */
@Composable
fun ReadOnlineOffer(captured: List<String>, missing: List<String>, hindi: Boolean, busy: Boolean, note: String, onReadOnline: () -> Unit) {
    if (missing.isEmpty()) return
    fun tr(en: String, hi: String) = if (hindi) hi else en
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(tr("📴 Read on the phone (offline)", "📴 फ़ोन पर पढ़ा (ऑफ़लाइन)"), fontWeight = FontWeight.Bold, fontSize = 13.sp)
            if (captured.isNotEmpty()) Text(tr("Captured: ", "पढ़ा गया: ") + captured.joinToString(", "), fontSize = 12.sp)
            Text(tr("Not captured: ", "नहीं पढ़ा गया: ") + missing.joinToString(", "), fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            Text(tr("For a better reading, read this photo online.", "बेहतर पढ़ाई के लिए यह फ़ोटो ऑनलाइन पढ़ें।"), fontSize = 12.sp)
            Button(onClick = onReadOnline, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (busy) tr("Muse Spark is reading…", "Muse Spark पढ़ रहा है…") else tr("☁ Read online with Muse Spark (Meta)", "☁ Muse Spark (Meta) से ऑनलाइन पढ़ें"))
            }
            Text(tr("Aadhaar is blacked out on the phone before the photo is sent.", "भेजने से पहले आधार फ़ोन पर काला किया जाता है।"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (note.isNotBlank()) Text(note, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
        }
    }
}

/** Display names for the person fields, for "Captured / Not captured". */
fun personFieldLabel(key: String, hindi: Boolean): String = when (key) {
    "name" -> if (hindi) "नाम" else "Name"
    "relative" -> if (hindi) "पति/रिश्तेदार" else "Husband / relative"
    "age" -> if (hindi) "उम्र" else "Age"
    "phone" -> if (hindi) "मोबाइल" else "Mobile"
    "household" -> if (hindi) "मकान नं." else "House No."
    "village" -> if (hindi) "गाँव" else "Village"
    "category" -> if (hindi) "श्रेणी" else "Category"
    "lmp" -> "LMP"
    "aadhaar" -> if (hindi) "आधार" else "Aadhaar"
    else -> key
}
