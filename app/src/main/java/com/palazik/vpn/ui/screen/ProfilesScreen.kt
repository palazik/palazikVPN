package com.palazik.vpn.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.palazik.vpn.data.model.*
import com.palazik.vpn.ui.viewmodel.MainViewModel
import java.util.UUID

// ─────────────────────────────────────────────────────────────────────────────
// Screen
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesScreen(vm: MainViewModel) {
    val ui        by vm.ui.collectAsState()
    val clipboard = LocalClipboardManager.current
    val keyboard  = LocalSoftwareKeyboardController.current

    var showImport    by remember { mutableStateOf(false) }
    var showManual    by remember { mutableStateOf(false) }
    var editProfile   by remember { mutableStateOf<VpnProfile?>(null) }
    var showShareLink by remember { mutableStateOf(false) }
    var importText    by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(16.dp)) {

        // ── Top bar ──────────────────────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically,
        ) {
            Text("Profiles", style = MaterialTheme.typography.headlineSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = { vm.pingAll() }) {
                    Icon(Icons.Rounded.Speed, "Ping all")
                }
                IconButton(onClick = { vm.generateShareLink(); showShareLink = true }) {
                    Icon(Icons.Rounded.Share, "Share active")
                }
                OutlinedButton(onClick = { showImport = true }) {
                    Icon(Icons.Rounded.Link, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Link")
                }
                FilledTonalButton(onClick = { showManual = true }) {
                    Icon(Icons.Rounded.Add, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Manual")
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        if (ui.profiles.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.VpnKey, null, Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(12.dp))
                    Text("No profiles yet", style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(4.dp))
                    Text("Import via link or add manually",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline)
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ui.profiles, key = { it.id }) { profile ->
                    val subName = profile.subscriptionId?.let { sid ->
                        ui.subscriptions.firstOrNull { it.id == sid }?.name
                    }
                    ProfileCard(
                        profile  = profile,
                        subName  = subName,
                        isActive = profile.isActive,
                        onSelect = { vm.selectProfile(profile.id) },
                        onDelete = { vm.removeProfile(profile.id) },
                        onPing   = { vm.pingProfile(profile) },
                        onEdit   = { editProfile = profile },
                    )
                }
            }
        }
    }

    // ── Import via link ───────────────────────────────────────────────────────
    if (showImport) {
        AlertDialog(
            onDismissRequest = { showImport = false; importText = "" },
            title = { Text("Import via Link") },
            text = {
                OutlinedTextField(
                    value         = importText,
                    onValueChange = { importText = it },
                    label         = { Text("vmess://, vless://, ss://, trojan://, palazikVPN://…") },
                    modifier      = Modifier.fillMaxWidth(),
                    minLines      = 3,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
                )
            },
            confirmButton = {
                Button(onClick = {
                    vm.importProfileFromLink(importText)
                    showImport = false; importText = ""
                }) { Text("Import") }
            },
            dismissButton = {
                TextButton(onClick = { showImport = false; importText = "" }) { Text("Cancel") }
            },
        )
    }

    // ── Manual add ───────────────────────────────────────────────────────────
    if (showManual) {
        ManualProfileDialog(
            initial   = null,
            onSave    = { profile -> vm.addManualProfile(profile); showManual = false },
            onDismiss = { showManual = false },
        )
    }

    // ── Edit ─────────────────────────────────────────────────────────────────
    editProfile?.let { toEdit ->
        ManualProfileDialog(
            initial   = toEdit,
            onSave    = { profile -> vm.updateProfile(profile); editProfile = null },
            onDismiss = { editProfile = null },
        )
    }

    // ── Share link ────────────────────────────────────────────────────────────
    if (showShareLink && ui.shareLink != null) {
        AlertDialog(
            onDismissRequest = { showShareLink = false; vm.clearShareLink() },
            title = { Text("Share Profile") },
            text = {
                Column {
                    Text("Send this link to another palazikVPN device:",
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(ui.shareLink!!, modifier = Modifier.padding(8.dp),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    clipboard.setText(AnnotatedString(ui.shareLink!!))
                    showShareLink = false; vm.clearShareLink()
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

// ─────────────────────────────────────────────────────────────────────────────
// Profile card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ProfileCard(
    profile: VpnProfile,
    subName: String?,
    isActive: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    onPing: () -> Unit,
    onEdit: () -> Unit,
) {
    val containerColor = if (isActive)
        MaterialTheme.colorScheme.primaryContainer
    else
        MaterialTheme.colorScheme.surfaceVariant

    ElevatedCard(
        onClick  = onSelect,
        colors   = CardDefaults.elevatedCardColors(containerColor = containerColor),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
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
                if (profile.transport != Transport.TCP) {
                    Spacer(Modifier.width(4.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f),
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            text     = profile.transport.name,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style    = MaterialTheme.typography.labelSmall,
                            color    = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                if (profile.latencyMs >= 0) {
                    Text(
                        "${profile.latencyMs}ms",
                        style = MaterialTheme.typography.labelMedium,
                        color = when {
                            profile.latencyMs < 150 -> MaterialTheme.colorScheme.primary
                            profile.latencyMs < 400 -> MaterialTheme.colorScheme.secondary
                            else                    -> MaterialTheme.colorScheme.error
                        },
                    )
                    Spacer(Modifier.width(4.dp))
                }
                IconButton(onClick = onPing,   Modifier.size(32.dp)) {
                    Icon(Icons.Rounded.Speed, "Ping", Modifier.size(18.dp))
                }
                IconButton(onClick = onEdit,   Modifier.size(32.dp)) {
                    Icon(Icons.Rounded.Edit, "Edit", Modifier.size(18.dp))
                }
                IconButton(onClick = onDelete, Modifier.size(32.dp)) {
                    Icon(Icons.Rounded.Delete, "Delete", Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(profile.name, style = MaterialTheme.typography.titleSmall)
            Text(
                "${profile.address}:${profile.port}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            if (subName != null) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Subscriptions, null, Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.tertiary)
                    Spacer(Modifier.width(4.dp))
                    Text(subName, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Manual add / edit dialog
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManualProfileDialog(
    initial: VpnProfile?,
    onSave: (VpnProfile) -> Unit,
    onDismiss: () -> Unit,
) {
    val isEdit = initial != null

    var name        by remember { mutableStateOf(initial?.name        ?: "") }
    var protocol    by remember { mutableStateOf(initial?.protocol    ?: Protocol.VLESS) }
    var address     by remember { mutableStateOf(initial?.address     ?: "") }
    var port        by remember { mutableStateOf(initial?.port?.toString() ?: "443") }
    var uuid        by remember { mutableStateOf(initial?.uuid        ?: "") }
    var transport   by remember { mutableStateOf(initial?.transport   ?: Transport.TCP) }
    var path        by remember { mutableStateOf(initial?.path        ?: "/") }
    var host        by remember { mutableStateOf(initial?.host        ?: "") }
    var security    by remember { mutableStateOf(initial?.security    ?: Security.TLS) }
    var sni         by remember { mutableStateOf(initial?.sni         ?: "") }
    var fingerprint by remember { mutableStateOf(initial?.fingerprint ?: "chrome") }
    var publicKey   by remember { mutableStateOf(initial?.publicKey   ?: "") }
    var shortId     by remember { mutableStateOf(initial?.shortId     ?: "") }
    var ssMethod    by remember { mutableStateOf(initial?.ssMethod    ?: "chacha20-ietf-poly1305") }
    var ssPassword  by remember { mutableStateOf(initial?.ssPassword  ?: "") }
    var hystPwd     by remember { mutableStateOf(initial?.hystPassword ?: "") }
    var wgPrivKey   by remember { mutableStateOf(initial?.wgPrivateKey    ?: "") }
    var wgPubKey    by remember { mutableStateOf(initial?.wgPeerPublicKey  ?: "") }
    var wgPsk       by remember { mutableStateOf(initial?.wgPreSharedKey  ?: "") }
    var wgDns       by remember { mutableStateOf(initial?.wgDns       ?: "1.1.1.1") }
    var wgMtu       by remember { mutableStateOf(initial?.wgMtu?.toString() ?: "1280") }

    var protoExpanded     by remember { mutableStateOf(false) }
    var transportExpanded by remember { mutableStateOf(false) }
    var securityExpanded  by remember { mutableStateOf(false) }

    val noTransportProtos = listOf(Protocol.WIREGUARD, Protocol.SHADOWSOCKS,
        Protocol.HYSTERIA2, Protocol.SOCKS5)
    val noSecurityProtos  = listOf(Protocol.WIREGUARD, Protocol.SHADOWSOCKS,
        Protocol.SOCKS5)
    val pathTransports    = listOf(Transport.WS, Transport.H2, Transport.XHTTP, Transport.GRPC)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEdit) "Edit Profile" else "Add Profile Manually") },
        text = {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Name
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Profile Name") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                )

                // Protocol
                ExposedDropdownMenuBox(
                    expanded = protoExpanded,
                    onExpandedChange = { protoExpanded = it },
                ) {
                    OutlinedTextField(
                        value         = protocol.name,
                        onValueChange = {},
                        readOnly      = true,
                        label         = { Text("Protocol") },
                        trailingIcon  = { ExposedDropdownMenuDefaults.TrailingIcon(protoExpanded) },
                        modifier      = Modifier.fillMaxWidth().menuAnchor(),
                    )
                    ExposedDropdownMenu(
                        expanded = protoExpanded,
                        onDismissRequest = { protoExpanded = false },
                    ) {
                        listOf(
                            Protocol.VLESS, Protocol.VMESS, Protocol.SHADOWSOCKS,
                            Protocol.TROJAN, Protocol.HYSTERIA2, Protocol.WIREGUARD,
                            Protocol.SOCKS5, Protocol.TUIC, Protocol.XHTTP,
                        ).forEach { p ->
                            DropdownMenuItem(
                                text    = { Text(p.name) },
                                onClick = { protocol = p; protoExpanded = false },
                            )
                        }
                    }
                }

                // Address + Port
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = address, onValueChange = { address = it },
                        label = { Text("Address") },
                        modifier = Modifier.weight(1f), singleLine = true,
                    )
                    OutlinedTextField(
                        value = port, onValueChange = { port = it },
                        label = { Text("Port") },
                        modifier = Modifier.width(90.dp), singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }

                // Protocol-specific credential fields
                when (protocol) {
                    Protocol.VMESS, Protocol.VLESS, Protocol.TROJAN,
                    Protocol.SOCKS5, Protocol.TUIC, Protocol.XHTTP -> {
                        OutlinedTextField(
                            value = uuid, onValueChange = { uuid = it },
                            label = { Text(if (protocol == Protocol.TROJAN) "Password" else "UUID") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                            trailingIcon = {
                                IconButton(onClick = { uuid = UUID.randomUUID().toString() }) {
                                    Icon(Icons.Rounded.Refresh, "Generate", Modifier.size(18.dp))
                                }
                            },
                        )
                    }
                    Protocol.SHADOWSOCKS -> {
                        OutlinedTextField(
                            value = ssMethod, onValueChange = { ssMethod = it },
                            label = { Text("Cipher") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                        )
                        OutlinedTextField(
                            value = ssPassword, onValueChange = { ssPassword = it },
                            label = { Text("Password") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                        )
                    }
                    Protocol.HYSTERIA2 -> {
                        OutlinedTextField(
                            value = hystPwd, onValueChange = { hystPwd = it },
                            label = { Text("Password") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                        )
                    }
                    Protocol.WIREGUARD -> {
                        OutlinedTextField(
                            value = wgPrivKey, onValueChange = { wgPrivKey = it },
                            label = { Text("Private Key") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                        )
                        OutlinedTextField(
                            value = wgPubKey, onValueChange = { wgPubKey = it },
                            label = { Text("Peer Public Key") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                        )
                        OutlinedTextField(
                            value = wgPsk, onValueChange = { wgPsk = it },
                            label = { Text("Pre-Shared Key (optional)") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = wgDns, onValueChange = { wgDns = it },
                                label = { Text("DNS") },
                                modifier = Modifier.weight(1f), singleLine = true,
                            )
                            OutlinedTextField(
                                value = wgMtu, onValueChange = { wgMtu = it },
                                label = { Text("MTU") },
                                modifier = Modifier.width(90.dp), singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            )
                        }
                    }
                    else -> {}
                }

                // Transport
                if (protocol !in noTransportProtos) {
                    ExposedDropdownMenuBox(
                        expanded = transportExpanded,
                        onExpandedChange = { transportExpanded = it },
                    ) {
                        OutlinedTextField(
                            value         = transport.name,
                            onValueChange = {},
                            readOnly      = true,
                            label         = { Text("Transport") },
                            trailingIcon  = { ExposedDropdownMenuDefaults.TrailingIcon(transportExpanded) },
                            modifier      = Modifier.fillMaxWidth().menuAnchor(),
                        )
                        ExposedDropdownMenu(
                            expanded = transportExpanded,
                            onDismissRequest = { transportExpanded = false },
                        ) {
                            Transport.values().forEach { t ->
                                DropdownMenuItem(
                                    text    = { Text(t.name) },
                                    onClick = { transport = t; transportExpanded = false },
                                )
                            }
                        }
                    }
                    if (transport in pathTransports) {
                        OutlinedTextField(
                            value = path, onValueChange = { path = it },
                            label = { Text(if (transport == Transport.GRPC) "Service Name" else "Path") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                        )
                        OutlinedTextField(
                            value = host, onValueChange = { host = it },
                            label = { Text("Host") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                        )
                    }
                }

                // Security
                if (protocol !in noSecurityProtos) {
                    ExposedDropdownMenuBox(
                        expanded = securityExpanded,
                        onExpandedChange = { securityExpanded = it },
                    ) {
                        OutlinedTextField(
                            value         = security.name,
                            onValueChange = {},
                            readOnly      = true,
                            label         = { Text("Security") },
                            trailingIcon  = { ExposedDropdownMenuDefaults.TrailingIcon(securityExpanded) },
                            modifier      = Modifier.fillMaxWidth().menuAnchor(),
                        )
                        ExposedDropdownMenu(
                            expanded = securityExpanded,
                            onDismissRequest = { securityExpanded = false },
                        ) {
                            Security.values().forEach { s ->
                                DropdownMenuItem(
                                    text    = { Text(s.name) },
                                    onClick = { security = s; securityExpanded = false },
                                )
                            }
                        }
                    }
                    if (security != Security.NONE) {
                        OutlinedTextField(
                            value = sni, onValueChange = { sni = it },
                            label = { Text("SNI") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                        )
                        OutlinedTextField(
                            value = fingerprint, onValueChange = { fingerprint = it },
                            label = { Text("Fingerprint") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                        )
                    }
                    if (security == Security.REALITY) {
                        OutlinedTextField(
                            value = publicKey, onValueChange = { publicKey = it },
                            label = { Text("Public Key (pbk)") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                        )
                        OutlinedTextField(
                            value = shortId, onValueChange = { shortId = it },
                            label = { Text("Short ID (sid)") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val profile = (initial ?: VpnProfile()).copy(
                    name            = name.ifBlank { "Unnamed" },
                    protocol        = protocol,
                    address         = address.trim(),
                    port            = port.toIntOrNull() ?: 443,
                    uuid            = uuid.trim(),
                    transport       = transport,
                    path            = path.ifBlank { "/" },
                    host            = host.trim(),
                    security        = security,
                    sni             = sni.trim(),
                    fingerprint     = fingerprint.trim(),
                    publicKey       = publicKey.trim(),
                    shortId         = shortId.trim(),
                    ssMethod        = ssMethod.trim(),
                    ssPassword      = ssPassword.trim(),
                    hystPassword    = hystPwd.trim(),
                    wgPrivateKey    = wgPrivKey.trim(),
                    wgPeerPublicKey = wgPubKey.trim(),
                    wgPreSharedKey  = wgPsk.trim(),
                    wgDns           = wgDns.trim(),
                    wgMtu           = wgMtu.toIntOrNull() ?: 1280,
                )
                onSave(profile)
            }) { Text(if (isEdit) "Save" else "Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
