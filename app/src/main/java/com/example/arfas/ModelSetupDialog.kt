package com.example.arfas

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

/**
 * ModelSetupDialog — shown on first launch when no local model is installed.
 *
 * FixSight installs one compatible .litertlm bundle at a time. The same picker
 * can select a text-only LLM or a multimodal/VLM bundle; model capabilities are
 * detected when the LiteRT-LM engine loads the selected file.
 *
 * Two install options:
 *   1. Browse Phone Storage — file picker for .litertlm file
 *   2. Download from Google Drive — paste sharing link
 */
@Composable
fun ModelSetupDialog(
    deviceProfile:    DeviceCapabilityChecker.DeviceProfile,
    modelFileManager: ModelFileManager,
    modelFileName:    String,
    projectorFileName: String = "",   // unused for Gemma3-1B but kept for API compat
    onSetupComplete:  () -> Unit,
    onSkip:           () -> Unit
) {
    val scope       = rememberCoroutineScope()
    var setupState  by remember { mutableStateOf(SetupState.CHOOSE_METHOD) }
    var driveUrl    by remember { mutableStateOf("") }
    var progress    by remember { mutableStateOf(0) }
    var statusText  by remember { mutableStateOf("") }
    var errorText   by remember { mutableStateOf("") }

    // Single file picker for the .litertlm file
    val modelFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            setupState = SetupState.COPYING
            statusText = "Copying model file..."
            scope.launch {
                val ok = modelFileManager.copyFromUri(
                    uri            = it,
                    targetFileName = modelFileName,
                    onProgress     = { pct -> progress = pct }
                )
                setupState = if (ok) SetupState.DONE else SetupState.ERROR.also {
                    errorText = "Failed to copy file. Please try again."
                }
            }
        }
    }

    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress     = setupState == SetupState.CHOOSE_METHOD,
            dismissOnClickOutside  = false
        )
    ) {
        Surface(
            shape    = RoundedCornerShape(20.dp),
            color    = Color(0xFF1A1A2E),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFF00E5FF).copy(alpha = 0.3f), RoundedCornerShape(20.dp))
        ) {
            Column(
                modifier            = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector        = Icons.Default.Psychology,
                    contentDescription = null,
                    tint               = Color(0xFF00E5FF),
                    modifier           = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text       = "ArogyaDoot AI Setup",
                    color      = Color.White,
                    fontSize   = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text          = DeviceCapabilityChecker.getCapabilityDescription(deviceProfile.modelTier),
                    color         = Color.Gray,
                    fontSize      = 12.sp,
                    textAlign     = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text      = "Storage depends on selected model • Works offline once installed",
                    color     = Color(0xFF00E5FF).copy(alpha = 0.7f),
                    fontSize  = 11.sp,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(20.dp))
                Divider(color = Color.White.copy(alpha = 0.1f))
                Spacer(modifier = Modifier.height(20.dp))

                when (setupState) {
                    SetupState.CHOOSE_METHOD -> ChooseMethodContent(
                        modelFileName = modelFileName,
                        onBrowsePhone = { modelFileLauncher.launch(arrayOf("*/*")) },
                        onDriveUrl    = { setupState = SetupState.ENTER_URL },
                        onSkip        = onSkip
                    )

                    SetupState.ENTER_URL -> EnterUrlContent(
                        modelFileName   = modelFileName,
                        driveUrl        = driveUrl,
                        onUrlChange     = { driveUrl = it },
                        onStartDownload = {
                            setupState = SetupState.DOWNLOADING
                            statusText = "Starting download..."
                            scope.launch {
                                val ok = modelFileManager.downloadFromDrive(
                                    driveUrl       = driveUrl,
                                    targetFileName = modelFileName,
                                    onProgress     = { pct ->
                                        progress   = pct
                                        statusText = "Downloading... $pct%"
                                    },
                                    onError = { msg ->
                                        errorText  = msg
                                        setupState = SetupState.ERROR
                                    }
                                )
                                if (ok) setupState = SetupState.DONE
                            }
                        },
                        onBack = { setupState = SetupState.CHOOSE_METHOD }
                    )

                    SetupState.COPYING,
                    SetupState.DOWNLOADING -> ProgressContent(
                        progress   = progress,
                        statusText = statusText
                    )

                    SetupState.ERROR -> ErrorContent(
                        errorText = errorText,
                        onRetry   = { setupState = SetupState.CHOOSE_METHOD; errorText = "" }
                    )

                    SetupState.DONE -> {
                        LaunchedEffect(Unit) { onSetupComplete() }
                    }

                    SetupState.PICK_FILES -> {} // unused for single-file model
                }
            }
        }
    }
}

// ── Sub-composables ───────────────────────────────────────────────────────────

@Composable
private fun ChooseMethodContent(
    modelFileName: String,
    onBrowsePhone: () -> Unit,
    onDriveUrl:    () -> Unit,
    onSkip:        () -> Unit
) {
    Text(
        text      = "You need one file to enable offline AI:",
        color     = Color.Gray,
        fontSize  = 12.sp,
        textAlign = TextAlign.Center
    )
    Spacer(modifier = Modifier.height(8.dp))
    FileChip(name = modelFileName, size = "~500MB")
    Spacer(modifier = Modifier.height(6.dp))
    Text(
        text      = "Download from:\nhttps://huggingface.co/litert-community/Gemma3-1B-IT",
        color     = Color(0xFF00E5FF).copy(alpha = 0.6f),
        fontSize  = 10.sp,
        textAlign = TextAlign.Center
    )
    Spacer(modifier = Modifier.height(20.dp))
    Text(
        text       = "How would you like to provide it?",
        color      = Color.White,
        fontSize   = 14.sp,
        fontWeight = FontWeight.Medium,
        textAlign  = TextAlign.Center
    )
    Spacer(modifier = Modifier.height(16.dp))

    SetupButton(
        icon    = Icons.Default.Folder,
        label   = "Browse Phone Storage",
        sub     = "Select the downloaded .litertlm file",
        color   = Color(0xFF1565C0),
        onClick = onBrowsePhone
    )
    Spacer(modifier = Modifier.height(10.dp))
    SetupButton(
        icon    = Icons.Default.CloudDownload,
        label   = "Download from Google Drive",
        sub     = "Paste a sharing link",
        color   = Color(0xFF1B5E20),
        onClick = onDriveUrl
    )
    Spacer(modifier = Modifier.height(16.dp))
    TextButton(onClick = onSkip) {
        Text(
            text     = "Skip for now (cloud AI only)",
            color    = Color.Gray,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun EnterUrlContent(
    modelFileName:   String,
    driveUrl:        String,
    onUrlChange:     (String) -> Unit,
    onStartDownload: () -> Unit,
    onBack:          () -> Unit
) {
    Text(
        text      = "Upload $modelFileName to Google Drive,\nshare it publicly, and paste the link below.",
        color     = Color.Gray,
        fontSize  = 12.sp,
        textAlign = TextAlign.Center
    )
    Spacer(modifier = Modifier.height(16.dp))
    OutlinedTextField(
        value         = driveUrl,
        onValueChange = onUrlChange,
        label         = { Text("Google Drive sharing link", color = Color.Gray, fontSize = 12.sp) },
        placeholder   = { Text("https://drive.google.com/file/d/...", color = Color.Gray.copy(0.5f), fontSize = 11.sp) },
        colors        = OutlinedTextFieldDefaults.colors(
            focusedBorderColor   = Color(0xFF00E5FF),
            unfocusedBorderColor = Color.Gray,
            focusedTextColor     = Color.White,
            unfocusedTextColor   = Color.White
        ),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        singleLine      = false,
        maxLines        = 3,
        modifier        = Modifier.fillMaxWidth()
    )
    Spacer(modifier = Modifier.height(6.dp))
    Text(
        text    = "File must be shared as 'Anyone with the link can view'",
        color   = Color.Gray,
        fontSize = 10.sp
    )
    Spacer(modifier = Modifier.height(16.dp))
    Button(
        onClick  = onStartDownload,
        enabled  = driveUrl.contains("drive.google.com") || driveUrl.startsWith("http"),
        colors   = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B5E20)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(Icons.Default.CloudDownload, null, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text("Start Download")
    }
    Spacer(modifier = Modifier.height(8.dp))
    TextButton(onClick = onBack) { Text("← Back", color = Color.Gray) }
}

@Composable
private fun ProgressContent(progress: Int, statusText: String) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue  = 0.4f,
        targetValue   = 1f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label         = "alpha"
    )
    Icon(
        imageVector        = Icons.Default.Downloading,
        contentDescription = null,
        tint               = Color(0xFF00E5FF).copy(alpha = alpha),
        modifier           = Modifier.size(40.dp)
    )
    Spacer(modifier = Modifier.height(16.dp))
    Text(statusText, color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center)
    Spacer(modifier = Modifier.height(12.dp))
    LinearProgressIndicator(
        progress = { progress / 100f },
        modifier = Modifier.fillMaxWidth(),
        color    = Color(0xFF00E5FF)
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text("$progress%", color = Color.Gray, fontSize = 12.sp)
    Spacer(modifier = Modifier.height(8.dp))
    Text("Keep the app open during setup.", color = Color.Gray, fontSize = 11.sp)
}

@Composable
private fun ErrorContent(errorText: String, onRetry: () -> Unit) {
    Icon(Icons.Default.ErrorOutline, null, tint = Color.Red, modifier = Modifier.size(40.dp))
    Spacer(modifier = Modifier.height(12.dp))
    Text("Setup Failed", color = Color.Red, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    Spacer(modifier = Modifier.height(8.dp))
    Text(errorText, color = Color.Gray, fontSize = 12.sp, textAlign = TextAlign.Center)
    Spacer(modifier = Modifier.height(16.dp))
    Button(
        onClick = onRetry,
        colors  = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0))
    ) { Text("Try Again") }
}

@Composable
private fun FileChip(name: String, size: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.InsertDriveFile, null, tint = Color(0xFF00E5FF), modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(name, color = Color.White, fontSize = 11.sp, modifier = Modifier.weight(1f))
        Text(size, color = Color.Gray, fontSize = 11.sp)
    }
}

@Composable
private fun SetupButton(
    icon:    androidx.compose.ui.graphics.vector.ImageVector,
    label:   String,
    sub:     String,
    color:   Color,
    onClick: () -> Unit
) {
    Button(
        onClick  = onClick,
        colors   = ButtonDefaults.buttonColors(containerColor = color),
        shape    = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().height(64.dp)
    ) {
        Icon(icon, null, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.Start) {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(sub, fontSize = 11.sp, color = Color.White.copy(alpha = 0.7f))
        }
    }
}

enum class SetupState {
    CHOOSE_METHOD, PICK_FILES, ENTER_URL, COPYING, DOWNLOADING, ERROR, DONE
}
