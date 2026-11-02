package com.example.arfas

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Batch 6 (U4): one colour per meaning, one card shape. Time-boxed: new and touched
 * screens use this; older screens keep their colours until a later pass.
 *  - AI (✨) = primary — every AI suggestion still needs the ASHA's approval.
 *  - danger = error — fixed action rules, never AI.
 *  - fixed rules (schedules, incentives, form rules) = tertiary.
 */
object ArogyaUi {
    @Composable fun ai(): Color = MaterialTheme.colorScheme.primary
    @Composable fun aiContainer(): Color = MaterialTheme.colorScheme.primaryContainer
    @Composable fun danger(): Color = MaterialTheme.colorScheme.error
    @Composable fun dangerContainer(): Color = MaterialTheme.colorScheme.errorContainer
    @Composable fun rules(): Color = MaterialTheme.colorScheme.tertiary
    @Composable fun rulesContainer(): Color = MaterialTheme.colorScheme.tertiaryContainer
}

/** "What changed" after a save: the same text that went into the diary entry. */
@Composable
fun WhatChangedDialog(summary: String, hindi: Boolean, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, confirmButton = { TextButton(onClick = onDismiss) { Text(if (hindi) "ठीक है" else "OK") } },
        title = { Text(if (hindi) "क्या बदला" else "What changed") },
        text = { Text(summary) })
}

/** Entry history: where one record came from, with an optional extra action (e.g. open the person). */
@Composable
fun OriginDialog(info: OriginInfo, hindi: Boolean, onDismiss: () -> Unit, extraButton: @Composable (() -> Unit)? = null) {
    AlertDialog(onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(if (hindi) "ठीक है" else "OK") } },
        dismissButton = extraButton,
        title = { Text(info.title) },
        text = { Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
            info.lines.forEach { Text(it) } } })
}

/** The shared card: medium shape, 12 dp padding, bold title, 6 dp between rows. */
@Composable
fun ArogyaCard(
    title: String,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surface,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = container)) {
        Column(Modifier.padding(12.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.Bold, color = titleColor)
            content()
        }
    }
}
