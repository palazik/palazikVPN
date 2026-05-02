package com.palazik.vpn.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.palazik.vpn.data.model.Protocol
import com.palazik.vpn.data.model.VpnProfile
import com.palazik.vpn.ui.viewmodel.MainViewModel

@Composable
fun ProfilesScreen(vm: MainViewModel) {
    val ui        by vm.ui.collectAsState()
    val clipboard = LocalClipboardManager.current
    val keyboard  = LocalSoftwareKeyboardController.current

    var showImport    by remember { mutableStateOf(false) }
    var showShareLink by remember { mutableStateOf(false) }
    var importText    by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(16.dp)) {

        // ── Top bar ──────────────────────────────────────────────────────────
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("Profiles", style = MaterialTheme.typography.headlineSmall)
            Row {
                IconButton(onClick = { vm.pingAll() }) {
                    Icon(Icons.Rounded.Speed, "Ping all")
                }
                IconButton(onClick = { vm.generateShareLink(); showShareLink = true }) {
                    Icon(Icons.Rounded.Share, "Share")
                }
                FilledTonalButton(onClick = { showImport = true }) {
                    Icon(Icons.Rounded.Add, null)
                    Spacer(Modifier.width(4.dp))
                    Text("Import")
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── Profile list ─────────────────────────────────────────────────────
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ui.profiles, key = { it.id }) { profile ->
                ProfileCard(
                    profile   = profile,
                    isActive  = profile.isActive,
                    onSelect  = { vm.selectProfile(profile.id) },
                    onDelete  = { vm.removeProfile(profile.id) },
                    onPing    = { vm.pingProfile(profile) },
                )
            }
        }
    }

    // ── Import dialog ─────────────────────────────────────────────────────────
    if (showImport) {
        AlertDialog(
            onDismissRequest = { showImport = false; importText = "" },
            title = { Text("Import Profile") },
            text = {
                OutlinedTextField(
                    value = importText,
                    onValueChange = { importText = it },
                    label = { Text("Paste link (vmess://, vless://, ss://, palazikVPN://, …)") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
                )
            },
            confirmButton = {
                Button(onClick = {
                    vm.importProfileFromLink(importText)
                    showImport = false
                    importText = ""
                }) { Text("Import") }
            },
            dismissButton = {
                TextButton(onClick = { showImport = false; importText = "" }) { Text("Cancel") }
            },
        )
    }

    // ── Share link dialog ─────────────────────────────────────────────────────
    if (showShareLink && ui.shareLink != null) {
        AlertDialog(
            onDismissRequest = { showShareLink = false; vm.clearShareLink() },
            title = { Text("Share Profile") },
            text = {
                Column {
                    Text("Send this link to another device running palazikVPN:",
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            ui.shareLink!!,
                            modifier = Modifier.padding(8.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    clipboard.setText(AnnotatedString(ui.shareLink!!))
                    showShareLink = false
                    vm.clearShareLink()
                }) {
                    Icon(Icons.Rounded.ContentCopy, null)
                    Spacer(Modifier.width(4.dp))
                    Text("Copy")
                }
            },
            dismissButton = {
                TextButton(onClick = { showShareLink = false; vm.clearShareLink() }) { Text("Close") }
            },
        )
    }
}

@Composable
private fun ProfileCard(
    profile: VpnProfile,
    isActive: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    onPing: () -> Unit,
) {
    val containerColor = if (isActive)
        MaterialTheme.colorScheme.primaryContainer
    else
        MaterialTheme.colorScheme.surfaceVariant

    ElevatedCard(
        onClick = onSelect,
        colors = CardDefaults.elevatedCardColors(containerColor = containerColor),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Protocol chip
            Surface(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    text     = profile.protocol.name,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style    = MaterialTheme.typography.labelSmall,
                    color    = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(profile.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    "${profile.address}:${profile.port}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            if (profile.latencyMs >= 0) {
                Text(
                    "${profile.latencyMs}ms",
                    style = MaterialTheme.typography.labelMedium,
                    color = when {
                        profile.latencyMs < 150 -> MaterialTheme.colorScheme.primary
                        profile.latencyMs < 400 -> MaterialTheme.colorScheme.secondary
                        else -> MaterialTheme.colorScheme.error
                    },
                )
                Spacer(Modifier.width(4.dp))
            }
            IconButton(onClick = onPing,   modifier = Modifier.size(32.dp)) {
                Icon(Icons.Rounded.Speed, "Ping", Modifier.size(18.dp))
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Rounded.Delete, "Delete", Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}
