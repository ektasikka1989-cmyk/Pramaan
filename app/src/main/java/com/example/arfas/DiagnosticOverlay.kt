package com.example.arfas

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * On-screen diagnostic. No adb, no logcat.
 *
 * A small "DIAG" chip sits top-left. Tap it to expand the full report:
 * model state, self-test result, KB status, notebook contents, last turn.
 *
 * Long-press the expanded panel to copy everything to the clipboard so you
 * can paste it into chat.
 */
object Diag {
    private const val MAX = 200
    private val lines = mutableStateListOf<String>()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** Headline on the collapsed chip: the real engine state, set by LocalModelManager. */
    var status: String by mutableStateOf("Local model: not loaded")
        private set

    private fun onMain(block: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) block() else main.post(block)
    }

    fun updateStatus(value: String) = onMain { status = value }

    /** Thread-safe: callers are model/inference threads; Compose state is changed on the main thread. */
    fun log(tag: String, msg: String) {
        val t = android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis())
        val line = "$t $tag  $msg"
        onMain {
            lines.add(line)
            while (lines.size > MAX) lines.removeAt(0)
        }
    }

    fun clear() = onMain { lines.clear() }

    fun asText(): String = lines.joinToString("\n")

    /** Shown in debuggable builds only (the demo/debug APK); release builds never show it. */
    fun isEnabled(context: android.content.Context): Boolean =
        (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    @Composable
    fun Overlay(modifier: Modifier = Modifier) {
        var expanded by remember { mutableStateOf(false) }
        val ctx = androidx.compose.ui.platform.LocalContext.current
        if (!isEnabled(ctx)) return

        // Collapsed: a tiny dot-pill on the right, below the top bar, so it never covers the
        // menu / settings buttons. The full status and log open on tap.
        Box(modifier = modifier.fillMaxSize().padding(top = if (expanded) 8.dp else 64.dp, start = 8.dp, end = 8.dp),
            contentAlignment = if (expanded) Alignment.TopCenter else Alignment.TopEnd) {

            if (!expanded) {
                val bad = status.contains("✗") || status.contains("fail", true)
                Surface(
                    color    = Color(0xFF1A1A2E).copy(alpha = 0.85f),
                    shape    = RoundedCornerShape(10.dp),
                    modifier = Modifier.clickable { expanded = true }
                ) {
                    Text(
                        (if (bad) "● " else "● ") + "DIAG",
                        color      = if (bad) Color(0xFFFF5252) else Color(0xFF00E676),
                        fontSize   = 9.sp,
                        maxLines   = 1,
                        fontFamily = FontFamily.Monospace,
                        modifier   = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            } else {
                Surface(
                    color    = Color(0xFF0A0A14).copy(alpha = 0.97f),
                    shape    = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth(0.96f)
                        .fillMaxHeight(0.78f)
                        .clickable { expanded = false }
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            Text("DIAGNOSTICS — tap to close",
                                color = Color(0xFF00E5FF), fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace)
                            Spacer(Modifier.weight(1f))
                            Text("COPY", color = Color(0xFF00E676), fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.clickable {
                                    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                        as android.content.ClipboardManager
                                    cm.setPrimaryClip(
                                        android.content.ClipData.newPlainText("diag", asText()))
                                    android.widget.Toast.makeText(
                                        ctx, "Copied — paste into chat",
                                        android.widget.Toast.LENGTH_SHORT).show()
                                })
                        }
                        Text(status, color = Color(0xFFFFD54F), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.height(6.dp))
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            lines.forEach {
                                Text(it,
                                    color = when {
                                        it.contains("✗") || it.contains("FAIL") -> Color(0xFFFF5252)
                                        it.contains("✓") || it.contains("OK")   -> Color(0xFF00E676)
                                        else                                    -> Color(0xFFCCCCCC)
                                    },
                                    fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
        }
    }
}
