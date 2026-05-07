package com.palazik.vpn.ui.screen

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.palazik.vpn.data.model.*
import com.palazik.vpn.ui.viewmodel.MainViewModel
import java.util.UUID

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

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {

        // ── Top bar ──────────────────────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically,
        ) {
            Column {
                Text("Profiles", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                AnimatedVisibility(visible = ui.profiles.isNotEmpty()) {
                    Text(
                        "${ui.profiles.size} profiles",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                AnimatedVisibility(visible = ui.profiles.isNotEmpty()) {
                    IconButton(onClick = { vm.pingAll() }) {
                        Icon(Icons.Rounded.NetworkCheck, "Ping all")
                    }
                }
                AnimatedVisibility(visible = ui.activeProfile != null) {
                    IconButton(onClick = { vm.generateShareLink(); showShareLink = true }) {
                        Icon(Icons.Rounded.Share, "Share active")
                    }
                }
                // Bug fix #1: use IconButton + icon-only buttons to prevent text wrapping
                IconButton(onClick = { showImport = true }) {
                    Icon(Icons.Rounded.Link, "Import via link")
                }
                FilledTonalButton(
                    onClick = { showManual = true },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Icon(Icons.Rounded.Add, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    // Bug fix #2: explicit color so text stays visible on any background
                    Text("Manual", color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        AnimatedContent(
            targetState = ui.profiles.isEmpty(),
            transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) },
            label = "profiles_content",
        ) { isEmpty ->
            if (isEmpty) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Rounded.VpnKey, null,
                            Modifier.size(72.dp),
                            tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "No profiles yet",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Import via link or add manually",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        Spacer(Modifier.height(24.dp))
                        FilledTonalButton(onClick = { showManual = true }) {
                            Icon(Icons.Rounded.Add, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Add Profile", color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                }
            } else {
                // Bug fix #6: group profiles by subscription with collapsible sections
                GroupedProfilesList(
                    profiles      = ui.profiles,
                    subscriptions = ui.subscriptions,
                    onSelect      = { vm.selectProfile(it) },
                    onDelete      = { vm.removeProfile(it) },
                    onPing        = { vm.pingProfile(it) },
                    onEdit        = { editProfile = it },
                    onRefreshSub  = { sub -> vm.updateSubscription(sub) },
                )
            }
        }
    }

    // ── Import dialog ─────────────────────────────────────────────────────────
    if (showImport) {
        AlertDialog(
            onDismissRequest = { showImport = false; importText = "" },
            title = { Text("Import via Link") },
            icon  = { Icon(Icons.Rounded.Link, null) },
            text = {
                OutlinedTextField(
                    value         = importText,
                    onValueChange = { importText = it },
                    label         = { Text("Share link") },
                    placeholder   = { Text("vmess://, vless://, ss://…", style = MaterialTheme.typography.bodySmall) },
                    modifier      = Modifier.fillMaxWidth(),
                    minLines      = 3,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
                )
            },
            confirmButton = {
                Button(
                    onClick  = { vm.importProfileFromLink(importText.trim()); showImport = false; importText = "" },
                    enabled  = importText.isNotBlank(),
                ) { Text("Import") }
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
            onSave    = { vm.addManualProfile(it); showManual = false },
            onDismiss = { showManual = false },
        )
    }

    // ── Edit ─────────────────────────────────────────────────────────────────
    editProfile?.let { toEdit ->
        ManualProfileDialog(
            initial   = toEdit,
            onSave    = { vm.updateProfile(it); editProfile = null },
            onDismiss = { editProfile = null },
        )
    }

    // ── Share link ────────────────────────────────────────────────────────────
    if (showShareLink && ui.shareLink != null) {
        AlertDialog(
            onDismissRequest = { showShareLink = false; vm.clearShareLink() },
            title = { Text("Share Profile") },
            icon  = { Icon(Icons.Rounded.Share, null) },
            text = {
                Column {
                    Text(
                        "Send this link to another palazikVPN device:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Text(
                            ui.shareLink!!,
                            modifier = Modifier.padding(12.dp),
                            style    = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    clipboard.setText(AnnotatedString(ui.shareLink!!))
                    showShareLink = false; vm.clearShareLink()
                }) {
                    Icon(Icons.Rounded.ContentCopy, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
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
// Grouped profiles list (collapsible per subscription + manual group)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun GroupedProfilesList(
    profiles: List<VpnProfile>,
    subscriptions: List<Subscription>,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onPing: (VpnProfile) -> Unit,
    onEdit: (VpnProfile) -> Unit,
    onRefreshSub: (Subscription) -> Unit,
) {
    // Track expanded state per group key ("manual" or sub.id)
    val expandedGroups = remember {
        mutableStateMapOf<String, Boolean>().apply {
            put("manual", true)
            subscriptions.forEach { put(it.id, true) }
        }
    }
    // Ensure new subs get an initial expanded state
    LaunchedEffect(subscriptions) {
        subscriptions.forEach { if (!expandedGroups.containsKey(it.id)) expandedGroups[it.id] = true }
    }

    val manualProfiles = remember(profiles) { profiles.filter { it.subscriptionId == null } }
    // Build stable list of (sub, profiles) pairs — avoids Map forEach in LazyColumn
    val subGroups = remember(profiles, subscriptions) {
        subscriptions.map { sub -> Pair(sub, profiles.filter { it.subscriptionId == sub.id }) }
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {

        // ── Manual profiles group ──────────────────────────────────────────────
        if (manualProfiles.isNotEmpty()) {
            item(key = "header_manual") {
                GroupHeader(
                    title     = "Manual",
                    count     = manualProfiles.size,
                    expanded  = expandedGroups["manual"] ?: true,
                    onToggle  = { expandedGroups["manual"] = !(expandedGroups["manual"] ?: true) },
                    onRefresh = null,
                )
            }
            if (expandedGroups["manual"] != false) {
                items(manualProfiles, key = { it.id }) { profile ->
                    ProfileCard(
                        profile  = profile,
                        subName  = null,
                        isActive = profile.isActive,
                        onSelect = { onSelect(profile.id) },
                        onDelete = { onDelete(profile.id) },
                        onPing   = { onPing(profile) },
                        onEdit   = { onEdit(profile) },
                    )
                }
            }
        }

        // ── Per-subscription groups ────────────────────────────────────────────
        subGroups.forEach { (sub, subProfiles) ->
            item(key = "header_${sub.id}") {
                GroupHeader(
                    title     = sub.name,
                    count     = subProfiles.size,
                    expanded  = expandedGroups[sub.id] ?: true,
                    onToggle  = { expandedGroups[sub.id] = !(expandedGroups[sub.id] ?: true) },
                    onRefresh = { onRefreshSub(sub) },
                )
            }
            if (expandedGroups[sub.id] != false && subProfiles.isNotEmpty()) {
                items(subProfiles, key = { it.id }) { profile ->
                    ProfileCard(
                        profile  = profile,
                        subName  = null,
                        isActive = profile.isActive,
                        onSelect = { onSelect(profile.id) },
                        onDelete = { onDelete(profile.id) },
                        onPing   = { onPing(profile) },
                        onEdit   = { onEdit(profile) },
                    )
                }
            }
        }

        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun GroupHeader(
    title: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    onRefresh: (() -> Unit)?,
) {
    val rotation by animateFloatAsState(
        targetValue   = if (expanded) 0f else -90f,
        animationSpec = tween(200),
        label         = "arrow_$title",
    )
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onToggle, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Rounded.KeyboardArrowDown, null,
                    Modifier.size(20.dp).graphicsLayer { rotationZ = rotation },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(
                title,
                style      = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color      = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier   = Modifier.weight(1f),
            )
            Surface(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                shape = CircleShape,
            ) {
                Text(
                    "$count",
                    modifier   = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    style      = MaterialTheme.typography.labelSmall,
                    color      = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (onRefresh != null) {
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onRefresh, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Rounded.Refresh, "Refresh subscription", Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
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
    val containerColor by animateColorAsState(
        targetValue   = if (isActive) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
        animationSpec = tween(350),
        label         = "card_bg",
    )
    val elevation by animateDpAsState(
        targetValue   = if (isActive) 6.dp else 1.dp,
        animationSpec = tween(300),
        label         = "card_elev",
    )

    ElevatedCard(
        onClick  = onSelect,
        colors   = CardDefaults.elevatedCardColors(containerColor = containerColor),
        modifier = Modifier.fillMaxWidth(),
        shape    = MaterialTheme.shapes.large,
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = elevation),
    ) {
        Column(Modifier.padding(14.dp)) {
            // ── Badge row ──────────────────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Badge(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)) {
                    Text(
                        profile.protocol.name,
                        color      = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        style      = MaterialTheme.typography.labelSmall,
                        modifier   = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                AnimatedVisibility(visible = profile.transport != Transport.TCP) {
                    Row {
                        Spacer(Modifier.width(6.dp))
                        Badge(containerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)) {
                            Text(
                                profile.transport.name,
                                color  = MaterialTheme.colorScheme.secondary,
                                style  = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                AnimatedVisibility(visible = isActive) {
                    Row {
                        Spacer(Modifier.width(6.dp))
                        Badge(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)) {
                            Text(
                                "ACTIVE",
                                color      = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                style      = MaterialTheme.typography.labelSmall,
                                modifier   = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                // Latency chip — animates in/out
                AnimatedVisibility(
                    visible = profile.latencyMs >= 0,
                    enter   = fadeIn() + scaleIn(),
                    exit    = fadeOut() + scaleOut(),
                ) {
                    val latColor = when {
                        profile.latencyMs < 150 -> MaterialTheme.colorScheme.primary
                        profile.latencyMs < 400 -> MaterialTheme.colorScheme.secondary
                        else                    -> MaterialTheme.colorScheme.error
                    }
                    Surface(color = latColor.copy(alpha = 0.15f), shape = CircleShape) {
                        Text(
                            "${profile.latencyMs}ms",
                            modifier   = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style      = MaterialTheme.typography.labelSmall,
                            color      = latColor,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(profile.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            Text(
                "${profile.address}:${profile.port}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )

            // Subscription source
            AnimatedVisibility(visible = subName != null) {
                Column {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Subscriptions, null, Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.tertiary)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            subName ?: "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), thickness = 0.5.dp)
            Spacer(Modifier.height(2.dp))

            // ── Action row ─────────────────────────────────────────────────────
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onPing, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Icon(Icons.Rounded.NetworkCheck, null, Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Ping", style = MaterialTheme.typography.labelMedium)
                }
                TextButton(onClick = onEdit, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Icon(Icons.Rounded.Edit, null, Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Edit", style = MaterialTheme.typography.labelMedium)
                }
                TextButton(
                    onClick        = onDelete,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    colors         = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Rounded.Delete, null, Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Delete", style = MaterialTheme.typography.labelMedium)
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

    val noTransportProtos = remember { listOf(Protocol.WIREGUARD, Protocol.SHADOWSOCKS, Protocol.HYSTERIA2, Protocol.SOCKS5) }
    val noSecurityProtos  = remember { listOf(Protocol.WIREGUARD, Protocol.SHADOWSOCKS, Protocol.SOCKS5) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEdit) "Edit Profile" else "Add Profile") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Profile Name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)

                ExposedDropdownMenuBox(expanded = protoExpanded, onExpandedChange = { protoExpanded = it }) {
                    OutlinedTextField(
                        value = protocol.name, onValueChange = {}, readOnly = true,
                        label = { Text("Protocol") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(protoExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                    )
                    ExposedDropdownMenu(expanded = protoExpanded, onDismissRequest = { protoExpanded = false }) {
                        listOf(Protocol.VLESS, Protocol.VMESS, Protocol.SHADOWSOCKS, Protocol.TROJAN,
                            Protocol.HYSTERIA2, Protocol.WIREGUARD, Protocol.SOCKS5, Protocol.TUIC).forEach { p ->
                            DropdownMenuItem(text = { Text(p.name) }, onClick = { protocol = p; protoExpanded = false })
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text("Address") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text("Port") }, modifier = Modifier.width(88.dp), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }

                // Protocol-specific fields
                when (protocol) {
                    Protocol.VMESS, Protocol.VLESS, Protocol.TROJAN, Protocol.SOCKS5, Protocol.TUIC -> {
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
                        OutlinedTextField(value = ssMethod,   onValueChange = { ssMethod   = it }, label = { Text("Cipher")   }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        OutlinedTextField(value = ssPassword, onValueChange = { ssPassword = it }, label = { Text("Password") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    }
                    Protocol.HYSTERIA2 -> {
                        OutlinedTextField(value = hystPwd, onValueChange = { hystPwd = it }, label = { Text("Password") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    }
                    Protocol.WIREGUARD -> {
                        OutlinedTextField(value = wgPrivKey, onValueChange = { wgPrivKey = it }, label = { Text("Private Key") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        OutlinedTextField(value = wgPubKey,  onValueChange = { wgPubKey  = it }, label = { Text("Peer Public Key") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        OutlinedTextField(value = wgPsk,     onValueChange = { wgPsk     = it }, label = { Text("Pre-Shared Key (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(value = wgDns, onValueChange = { wgDns = it }, label = { Text("DNS") }, modifier = Modifier.weight(1f), singleLine = true)
                            OutlinedTextField(value = wgMtu, onValueChange = { wgMtu = it }, label = { Text("MTU") }, modifier = Modifier.width(88.dp), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        }
                    }
                    else -> {}
                }

                // Transport
                AnimatedVisibility(visible = protocol !in noTransportProtos) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ExposedDropdownMenuBox(expanded = transportExpanded, onExpandedChange = { transportExpanded = it }) {
                            OutlinedTextField(
                                value = transport.name, onValueChange = {}, readOnly = true,
                                label = { Text("Transport") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(transportExpanded) },
                                modifier = Modifier.fillMaxWidth().menuAnchor(),
                            )
                            ExposedDropdownMenu(expanded = transportExpanded, onDismissRequest = { transportExpanded = false }) {
                                Transport.values().forEach { t ->
                                    DropdownMenuItem(text = { Text(t.name) }, onClick = { transport = t; transportExpanded = false })
                                }
                            }
                        }
                        AnimatedVisibility(visible = transport in listOf(Transport.WS, Transport.H2, Transport.XHTTP, Transport.GRPC)) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedTextField(value = path, onValueChange = { path = it }, label = { Text(if (transport == Transport.GRPC) "Service Name" else "Path") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("Host") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            }
                        }
                    }
                }

                // Security
                AnimatedVisibility(visible = protocol !in noSecurityProtos) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ExposedDropdownMenuBox(expanded = securityExpanded, onExpandedChange = { securityExpanded = it }) {
                            OutlinedTextField(
                                value = security.name, onValueChange = {}, readOnly = true,
                                label = { Text("Security") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(securityExpanded) },
                                modifier = Modifier.fillMaxWidth().menuAnchor(),
                            )
                            ExposedDropdownMenu(expanded = securityExpanded, onDismissRequest = { securityExpanded = false }) {
                                Security.values().forEach { s ->
                                    DropdownMenuItem(text = { Text(s.name) }, onClick = { security = s; securityExpanded = false })
                                }
                            }
                        }
                        AnimatedVisibility(visible = security != Security.NONE) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedTextField(value = sni,         onValueChange = { sni         = it }, label = { Text("SNI") },         modifier = Modifier.fillMaxWidth(), singleLine = true)
                                OutlinedTextField(value = fingerprint, onValueChange = { fingerprint = it }, label = { Text("Fingerprint") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            }
                        }
                        AnimatedVisibility(visible = security == Security.REALITY) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedTextField(value = publicKey, onValueChange = { publicKey = it }, label = { Text("Public Key (pbk)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                OutlinedTextField(value = shortId,   onValueChange = { shortId   = it }, label = { Text("Short ID (sid)") },  modifier = Modifier.fillMaxWidth(), singleLine = true)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave((initial ?: VpnProfile()).copy(
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
                    ))
                },
                enabled = address.isNotBlank(),
            ) { Text(if (isEdit) "Save" else "Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
