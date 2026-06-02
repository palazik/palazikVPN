package com.palazik.vpn.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.palazik.vpn.R
import com.palazik.vpn.data.model.AppSettings
import com.palazik.vpn.data.model.DesignSystem
import com.palazik.vpn.data.model.PingMode
import com.palazik.vpn.ui.theme.LocalDesignSystem
import com.palazik.vpn.ui.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

private val PingModeOptions             = PingMode.values().toList()
private val SubscriptionIntervalOptions = listOf(2L, 6L, 12L, 24L)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: MainViewModel, onOpenStyle: () -> Unit) {
    val designSystem = LocalDesignSystem.current
    if (designSystem == DesignSystem.MIUIX) {
        MiuixSettingsScreen(vm, onOpenStyle)
    } else {
        Md3SettingsScreen(vm, onOpenStyle)
    }
}

// ── MiuiX Settings ──────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MiuixSettingsScreen(vm: MainViewModel, onOpenStyle: () -> Unit) {
    val ui        by vm.ui.collectAsState()
    val clipboard  = LocalClipboardManager.current
    var showAppPicker by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState()),
    ) {
        SmallTitle(text = "Settings")

        // ── Style ────────────────────────────────────────────────────────────
        MiuixCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            ArrowPreference(
                title = "Style",
                summary = "Design system, dark mode, color theme",
                onClick = onOpenStyle,
            )
        }

        Spacer(Modifier.height(12.dp))

        // ── Connection ───────────────────────────────────────────────────────
        SmallTitle(text = "Connection")
        MiuixCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "Ping Test Mode",
                    style    = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                Text(
                    "TCP — raw socket connect (fastest).\nGET / HEAD — Cloudflare request through VPN.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    PingModeOptions.forEachIndexed { idx, mode ->
                        SegmentedButton(
                            shape    = SegmentedButtonDefaults.itemShape(idx, PingModeOptions.size),
                            selected = ui.pingMode == mode,
                            onClick  = { vm.setPingMode(mode) },
                            label    = {
                                Text(when (mode) {
                                    PingMode.TCP       -> "TCP"
                                    PingMode.HTTP_GET  -> "GET"
                                    PingMode.HTTP_HEAD -> "HEAD"
                                })
                            },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── DNS ──────────────────────────────────────────────────────────────
        SmallTitle(text = "DNS")
        MiuixCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            Column(Modifier.padding(16.dp)) {
                var tunDns    by remember(ui.settings.dnsServers) { mutableStateOf(ui.settings.dnsServers.joinToString(", ")) }
                var remoteDns by remember(ui.settings.remoteDns)  { mutableStateOf(ui.settings.remoteDns) }
                var directDns by remember(ui.settings.directDns)  { mutableStateOf(ui.settings.directDns) }

                OutlinedTextField(
                    value          = tunDns,
                    onValueChange  = { tunDns = it },
                    label          = { Text("VPN DNS servers") },
                    supportingText = { Text("Comma separated") },
                    modifier       = Modifier.fillMaxWidth(),
                    singleLine     = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value         = remoteDns,
                    onValueChange = { remoteDns = it },
                    label         = { Text("Remote DNS") },
                    modifier      = Modifier.fillMaxWidth(),
                    singleLine    = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value         = directDns,
                    onValueChange = { directDns = it },
                    label         = { Text("Direct DNS") },
                    modifier      = Modifier.fillMaxWidth(),
                    singleLine    = true,
                )
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(onClick = {
                        vm.updateAppSettings(
                            ui.settings.copy(
                                dnsServers = tunDns.split(",", "\n").map { it.trim() }.filter { it.isNotBlank() },
                                remoteDns  = remoteDns,
                                directDns  = directDns,
                            )
                        )
                    }) {
                        Icon(Icons.Rounded.Save, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Save DNS")
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── Split Tunneling ──────────────────────────────────────────────────
        SmallTitle(text = "Split Tunneling")
        MiuixCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "${ui.settings.bypassPackages.size} apps bypass VPN",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                if (ui.settings.bypassPackages.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        ui.settings.bypassPackages.take(4).forEach { pkg ->
                            AssistChip(
                                onClick      = {},
                                label        = { Text(pkg, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                trailingIcon = {
                                    IconButton(
                                        onClick  = { vm.updateAppSettings(ui.settings.copy(bypassPackages = ui.settings.bypassPackages - pkg)) },
                                        modifier = Modifier.size(24.dp),
                                    ) {
                                        Icon(Icons.Rounded.Close, null, Modifier.size(16.dp))
                                    }
                                },
                            )
                        }
                        if (ui.settings.bypassPackages.size > 4) {
                            Text(
                                "+${ui.settings.bypassPackages.size - 4} more",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalArrangement   = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = { vm.updateAppSettings(ui.settings.copy(bypassPackages = emptyList())) }) {
                        Icon(Icons.Rounded.Clear, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Clear")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { showAppPicker = true }) {
                        Icon(Icons.Rounded.Apps, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Choose Apps")
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── Routing & Privacy ────────────────────────────────────────────────
        SmallTitle(text = "Routing & Privacy")
        MiuixCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            Column(Modifier.padding(16.dp)) { RoutingSettingsContent(vm, ui.settings) }
        }

        Spacer(Modifier.height(12.dp))

        // ── Subscriptions ────────────────────────────────────────────────────
        SmallTitle(text = "Subscriptions")
        MiuixCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            Column(Modifier.padding(16.dp)) { SubscriptionUaContent(vm, ui.settings) }
        }

        Spacer(Modifier.height(12.dp))

        // ── Backup ───────────────────────────────────────────────────────────
        SmallTitle(text = "Backup")
        MiuixCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            Column(Modifier.padding(16.dp)) { BackupSettingsContent(vm) }
        }

        Spacer(Modifier.height(12.dp))

        // ── Startup ──────────────────────────────────────────────────────────
        SmallTitle(text = "Startup")
        MiuixCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            SwitchPreference(
                title = "Auto-connect on boot",
                summary = "Starts the selected profile after reboot",
                checked = ui.settings.startOnBoot,
                onCheckedChange = { enabled -> vm.updateAppSettings(ui.settings.copy(startOnBoot = enabled)) },
            )
            SwitchPreference(
                title = "Auto-update subscriptions",
                summary = "Refreshes every ${ui.settings.subscriptionUpdateIntervalHours}h when network available",
                checked = ui.settings.autoUpdateSubscriptions,
                onCheckedChange = { enabled -> vm.updateAppSettings(ui.settings.copy(autoUpdateSubscriptions = enabled)) },
            )
            AnimatedVisibility(visible = ui.settings.autoUpdateSubscriptions) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Update interval",
                        style    = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement   = Arrangement.spacedBy(8.dp),
                    ) {
                        SubscriptionIntervalOptions.forEach { hours ->
                            FilterChip(
                                selected    = ui.settings.subscriptionUpdateIntervalHours == hours,
                                onClick     = { vm.updateAppSettings(ui.settings.copy(subscriptionUpdateIntervalHours = hours)) },
                                label       = { Text("${hours}h") },
                                leadingIcon = if (ui.settings.subscriptionUpdateIntervalHours == hours) {
                                    { Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)) }
                                } else null,
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── Diagnostics ──────────────────────────────────────────────────────
        SmallTitle(text = "Diagnostics")
        MiuixCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            Column(Modifier.padding(16.dp)) {
                if (ui.diagnostics.isEmpty()) {
                    Text(
                        "No connection events yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ui.diagnostics.takeLast(6).forEach { line ->
                            Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(
                        onClick = { clipboard.setText(AnnotatedString(ui.diagnostics.joinToString("\n"))) },
                        enabled = ui.diagnostics.isNotEmpty(),
                    ) {
                        Icon(Icons.Rounded.ContentCopy, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Copy Logs")
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── About ────────────────────────────────────────────────────────────
        SmallTitle(text = "About")
        MiuixCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            ArrowPreference(
                title = "palazikVPN",
                summary = "V${stringResource(R.string.app_version)} • by palaziks",
            )
        }

        Spacer(Modifier.height(16.dp))
    }

    if (showAppPicker) {
        AppPickerDialog(
            apps      = ui.installedApps,
            selected  = ui.settings.bypassPackages.toSet(),
            onDismiss = { showAppPicker = false },
            onSave    = { selected ->
                vm.updateAppSettings(ui.settings.copy(bypassPackages = selected.sorted()))
                showAppPicker = false
            },
        )
    }
}

// ── MD3 Expressive Settings ─────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun Md3SettingsScreen(vm: MainViewModel, onOpenStyle: () -> Unit) {
    val ui        by vm.ui.collectAsState()
    val clipboard  = LocalClipboardManager.current
    var showAppPicker by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))

        // ── Style button ──────────────────────────────────────────────────────
        ElevatedCard(
            onClick = onOpenStyle,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        Icons.Rounded.Palette,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column {
                        Text("Style", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Design system, dark mode, color theme",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            when (ui.designSystem) {
                                DesignSystem.MIUIX -> "Miuix"
                                DesignSystem.MD3   -> "M3 Expressive"
                            } + " · " + when (ui.darkMode.name) {
                                "SYSTEM"       -> "System dark"
                                "ALWAYS_DARK"  -> "Dark"
                                "ALWAYS_LIGHT" -> "Light"
                                else           -> ""
                            } + " · " + when (ui.appTheme.name) {
                                "CYBER"   -> "Cyber"
                                "OCEAN"   -> "Ocean"
                                "FOREST"  -> "Forest"
                                "SUNSET"  -> "Sunset"
                                "DYNAMIC" -> "Dynamic"
                                else      -> ""
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Icon(
                    Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // ── Connection ────────────────────────────────────────────────────────
        SettingsSection(title = "Connection") {
            Text(
                "Ping Test Mode",
                style    = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Text(
                "TCP — raw socket connect (fastest, most accurate, default).\n" +
                "GET / HEAD — Cloudflare request through the running VPN.",
                style    = MaterialTheme.typography.bodySmall,
                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                PingModeOptions.forEachIndexed { idx, mode ->
                    SegmentedButton(
                        shape    = SegmentedButtonDefaults.itemShape(idx, PingModeOptions.size),
                        selected = ui.pingMode == mode,
                        onClick  = { vm.setPingMode(mode) },
                        label    = {
                            Text(when (mode) {
                                PingMode.TCP       -> "TCP"
                                PingMode.HTTP_GET  -> "GET"
                                PingMode.HTTP_HEAD -> "HEAD"
                            })
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        SettingsSection(title = "DNS") {
            var tunDns    by remember(ui.settings.dnsServers) { mutableStateOf(ui.settings.dnsServers.joinToString(", ")) }
            var remoteDns by remember(ui.settings.remoteDns)  { mutableStateOf(ui.settings.remoteDns) }
            var directDns by remember(ui.settings.directDns)  { mutableStateOf(ui.settings.directDns) }

            OutlinedTextField(
                value          = tunDns,
                onValueChange  = { tunDns = it },
                label          = { Text("VPN DNS servers") },
                supportingText = { Text("Comma separated") },
                modifier       = Modifier.fillMaxWidth(),
                singleLine     = true,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value         = remoteDns,
                onValueChange = { remoteDns = it },
                label         = { Text("Remote DNS") },
                modifier      = Modifier.fillMaxWidth(),
                singleLine    = true,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value         = directDns,
                onValueChange = { directDns = it },
                label         = { Text("Direct DNS") },
                modifier      = Modifier.fillMaxWidth(),
                singleLine    = true,
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(onClick = {
                    vm.updateAppSettings(
                        ui.settings.copy(
                            dnsServers = tunDns.split(",", "\n").map { it.trim() }.filter { it.isNotBlank() },
                            remoteDns  = remoteDns,
                            directDns  = directDns,
                        )
                    )
                }) {
                    Icon(Icons.Rounded.Save, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Save DNS")
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        SettingsSection(title = "Split Tunneling") {
            Text(
                "${ui.settings.bypassPackages.size} apps bypass VPN",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            if (ui.settings.bypassPackages.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ui.settings.bypassPackages.take(4).forEach { pkg ->
                        AssistChip(
                            onClick      = {},
                            label        = { Text(pkg, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            trailingIcon = {
                                IconButton(
                                    onClick  = { vm.updateAppSettings(ui.settings.copy(bypassPackages = ui.settings.bypassPackages - pkg)) },
                                    modifier = Modifier.size(24.dp),
                                ) {
                                    Icon(Icons.Rounded.Close, null, Modifier.size(16.dp))
                                }
                            },
                        )
                    }
                    if (ui.settings.bypassPackages.size > 4) {
                        Text(
                            "+${ui.settings.bypassPackages.size - 4} more",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalArrangement   = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = { vm.updateAppSettings(ui.settings.copy(bypassPackages = emptyList())) }) {
                    Icon(Icons.Rounded.Clear, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Clear")
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { showAppPicker = true }) {
                    Icon(Icons.Rounded.Apps, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Choose Apps")
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        SettingsSection(title = "Routing & Privacy") {
            RoutingSettingsContent(vm, ui.settings)
        }

        Spacer(Modifier.height(8.dp))

        SettingsSection(title = "Subscriptions") {
            SubscriptionUaContent(vm, ui.settings)
        }

        Spacer(Modifier.height(8.dp))

        SettingsSection(title = "Backup") {
            BackupSettingsContent(vm)
        }

        Spacer(Modifier.height(8.dp))

        SettingsSection(title = "Startup") {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Auto-connect on boot", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Starts the selected profile after reboot when VPN permission is already granted.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked         = ui.settings.startOnBoot,
                    onCheckedChange = { enabled -> vm.updateAppSettings(ui.settings.copy(startOnBoot = enabled)) },
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Auto-update subscriptions", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Refreshes subscriptions every ${ui.settings.subscriptionUpdateIntervalHours} hours when network is available.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked         = ui.settings.autoUpdateSubscriptions,
                    onCheckedChange = { enabled -> vm.updateAppSettings(ui.settings.copy(autoUpdateSubscriptions = enabled)) },
                )
            }
            AnimatedVisibility(visible = ui.settings.autoUpdateSubscriptions) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Update interval",
                        style    = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement   = Arrangement.spacedBy(8.dp),
                    ) {
                        SubscriptionIntervalOptions.forEach { hours ->
                            FilterChip(
                                selected    = ui.settings.subscriptionUpdateIntervalHours == hours,
                                onClick     = { vm.updateAppSettings(ui.settings.copy(subscriptionUpdateIntervalHours = hours)) },
                                label       = { Text("${hours}h") },
                                leadingIcon = if (ui.settings.subscriptionUpdateIntervalHours == hours) {
                                    { Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)) }
                                } else null,
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        SettingsSection(title = "Diagnostics") {
            if (ui.diagnostics.isEmpty()) {
                Text(
                    "No connection events yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ui.diagnostics.takeLast(6).forEach { line ->
                        Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(
                    onClick = { clipboard.setText(AnnotatedString(ui.diagnostics.joinToString("\n"))) },
                    enabled = ui.diagnostics.isNotEmpty(),
                ) {
                    Icon(Icons.Rounded.ContentCopy, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Copy Logs")
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        SettingsSection(title = "About") {
            ListItem(
                headlineContent   = { Text("palazikVPN") },
                supportingContent = { Text("V${stringResource(R.string.app_version)} • by palaziks") },
                leadingContent    = { Icon(Icons.Rounded.Info, null) },
            )
        }
    }

    if (showAppPicker) {
        AppPickerDialog(
            apps      = ui.installedApps,
            selected  = ui.settings.bypassPackages.toSet(),
            onDismiss = { showAppPicker = false },
            onSave    = { selected ->
                vm.updateAppSettings(ui.settings.copy(bypassPackages = selected.sorted()))
                showAppPicker = false
            },
        )
    }
}

// ── Shared components ────────────────────────────────────────────────────────

@Composable
private fun AppPickerDialog(
    apps: List<com.palazik.vpn.data.model.InstalledApp>,
    selected: Set<String>,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit,
) {
    var query  by remember { mutableStateOf("") }
    var picked by remember(selected) { mutableStateOf(selected) }
    val filtered = remember(apps, query) {
        if (query.isBlank()) apps else apps.filter {
            it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title  = { Text("Bypass Apps") },
        icon   = { Icon(Icons.Rounded.Apps, null) },
        text   = {
            Column {
                OutlinedTextField(
                    value         = query,
                    onValueChange = { query = it },
                    label         = { Text("Search apps") },
                    modifier      = Modifier.fillMaxWidth(),
                    singleLine    = true,
                )
                Spacer(Modifier.height(10.dp))
                LazyColumn(
                    modifier            = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(filtered, key = { it.packageName }) { app ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    picked = if (app.packageName in picked) picked - app.packageName else picked + app.packageName
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked         = app.packageName in picked,
                                onCheckedChange = { checked ->
                                    picked = if (checked) picked + app.packageName else picked - app.packageName
                                },
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    app.packageName,
                                    style    = MaterialTheme.typography.bodySmall,
                                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onSave(picked.toList()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ── Routing / subscription / backup section bodies (shared by both design systems) ──

@Composable
private fun RoutingSettingsContent(vm: MainViewModel, settings: AppSettings) {
    var directDomains by remember(settings.customDirectDomains) {
        mutableStateOf(settings.customDirectDomains.joinToString(", "))
    }
    var blockedDomains by remember(settings.customBlockedDomains) {
        mutableStateOf(settings.customBlockedDomains.joinToString(", "))
    }

    SettingRow(
        title = "Block ads",
        subtitle = "Drop requests matching the ad/tracker domain list",
        checked = settings.blockAds,
        onChange = { vm.updateAppSettings(settings.copy(blockAds = it)) },
    )
    HorizontalDivider(Modifier.padding(vertical = 10.dp))
    SettingRow(
        title = "Bypass China",
        subtitle = "Route mainland China domains & IPs directly (outside the proxy)",
        checked = settings.bypassChina,
        onChange = { vm.updateAppSettings(settings.copy(bypassChina = it)) },
    )
    HorizontalDivider(Modifier.padding(vertical = 10.dp))
    SettingRow(
        title = "Route IPv6 through tunnel",
        subtitle = "Off forces IPv4-only dialling. IPv6 is always captured to prevent leaks.",
        checked = settings.enableIpv6,
        onChange = { vm.updateAppSettings(settings.copy(enableIpv6 = it)) },
    )
    HorizontalDivider(Modifier.padding(vertical = 10.dp))
    SettingRow(
        title = "Kill switch (lockdown)",
        subtitle = "Block traffic while the tunnel isn't ready. For full effect also enable Android's Always-on VPN.",
        checked = settings.lockdownMode,
        onChange = { vm.updateAppSettings(settings.copy(lockdownMode = it)) },
    )
    Spacer(Modifier.height(10.dp))
    OutlinedTextField(
        value = directDomains,
        onValueChange = { directDomains = it },
        label = { Text("Direct domains") },
        supportingText = { Text("Comma separated, e.g. geosite:google, example.com") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = blockedDomains,
        onValueChange = { blockedDomains = it },
        label = { Text("Blocked domains") },
        supportingText = { Text("Comma separated") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Button(onClick = {
            vm.updateAppSettings(settings.copy(
                customDirectDomains = directDomains.split(",", "\n").map { it.trim() }.filter { it.isNotBlank() },
                customBlockedDomains = blockedDomains.split(",", "\n").map { it.trim() }.filter { it.isNotBlank() },
            ))
        }) {
            Icon(Icons.Rounded.Save, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Save Routing")
        }
    }
}

@Composable
private fun SubscriptionUaContent(vm: MainViewModel, settings: AppSettings) {
    var ua by remember(settings.subscriptionUserAgent) { mutableStateOf(settings.subscriptionUserAgent) }
    OutlinedTextField(
        value = ua,
        onValueChange = { ua = it },
        label = { Text("Subscription User-Agent") },
        supportingText = { Text("Some providers serve configs based on this header") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Button(onClick = { vm.updateAppSettings(settings.copy(subscriptionUserAgent = ua.trim())) }) {
            Icon(Icons.Rounded.Save, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Save")
        }
    }
}

@Composable
private fun BackupSettingsContent(vm: MainViewModel) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(vm.exportProfilesText().toByteArray())
                    }
                }.isSuccess
            }
            vm.showSnack(if (ok) "Profiles exported" else "Export failed")
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) scope.launch {
            val body = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { String(it.readBytes()) }
                }.getOrNull()
            }
            if (body != null) vm.importProfilesText(body) else vm.showSnack("Could not read file")
        }
    }

    Text(
        "Export all profiles to a .txt file (palazikvpn:// links), or import them back on another device.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        OutlinedButton(onClick = { importLauncher.launch(arrayOf("text/plain", "*/*")) }) {
            Icon(Icons.Rounded.FileUpload, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Import")
        }
        Spacer(Modifier.width(8.dp))
        Button(onClick = { exportLauncher.launch("palazikvpn-profiles.txt") }) {
            Icon(Icons.Rounded.FileDownload, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Export")
        }
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                title,
                style    = MaterialTheme.typography.labelLarge,
                color    = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            content()
        }
    }
}
