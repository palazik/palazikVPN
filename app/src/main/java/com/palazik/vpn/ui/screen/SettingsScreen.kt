package com.palazik.vpn.ui.screen

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.palazik.vpn.R
import com.palazik.vpn.data.model.PingMode
import com.palazik.vpn.ui.theme.AppTheme
import com.palazik.vpn.ui.theme.DarkModePreference
import com.palazik.vpn.ui.viewmodel.MainViewModel

private val DarkModeOptions = DarkModePreference.values().toList()
private val AppThemeOptions = AppTheme.values().toList()
private val PingModeOptions = PingMode.values().toList()
private val SubscriptionIntervalOptions = listOf(2L, 6L, 12L, 24L)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: MainViewModel) {
    val ui by vm.ui.collectAsState()
    val clipboard = LocalClipboardManager.current
    var showAppPicker by remember { mutableStateOf(false) }

    ScreenColumn(
        modifier = Modifier
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PageHeader(
            title = "Settings",
            subtitle = "Connection, appearance, and diagnostics",
        )

        // ── Appearance ────────────────────────────────────────────────────────
        SettingsSection(title = "Appearance") {
            Text(
                "Dark Mode",
                style    = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                DarkModeOptions.forEachIndexed { idx, pref ->
                    SegmentedButton(
                        shape    = SegmentedButtonDefaults.itemShape(idx, DarkModeOptions.size),
                        selected = ui.darkMode == pref,
                        onClick  = { vm.setDarkMode(pref) },
                        label    = {
                            Text(when (pref) {
                                DarkModePreference.SYSTEM       -> "System"
                                DarkModePreference.ALWAYS_LIGHT -> "Light"
                                DarkModePreference.ALWAYS_DARK  -> "Dark"
                            })
                        },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Text(
                "Color Theme",
                style    = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AppThemeOptions.forEach { theme ->
                    ThemeRow(theme, isSelected = ui.appTheme == theme) {
                        vm.setAppTheme(theme)
                    }
                }
            }
        }

        // ── Connection ────────────────────────────────────────────────────────
        SettingsSection(title = "Connection") {
            Text(
                "Ping Test Mode",
                style    = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Text(
                "TCP for direct checks. GET and HEAD use the running VPN.",
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

        SettingsSection(title = "DNS") {
            var tunDns by remember(ui.settings.dnsServers) {
                mutableStateOf(ui.settings.dnsServers.joinToString(", "))
            }
            var remoteDns by remember(ui.settings.remoteDns) { mutableStateOf(ui.settings.remoteDns) }
            var directDns by remember(ui.settings.directDns) { mutableStateOf(ui.settings.directDns) }

            OutlinedTextField(
                value = tunDns,
                onValueChange = { tunDns = it },
                label = { Text("VPN DNS servers") },
                supportingText = { Text("Comma separated") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = remoteDns,
                onValueChange = { remoteDns = it },
                label = { Text("Remote DNS") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = directDns,
                onValueChange = { directDns = it },
                label = { Text("Direct DNS") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(onClick = {
                    vm.updateAppSettings(
                        ui.settings.copy(
                            dnsServers = tunDns.split(",", "\n").map { it.trim() }.filter { it.isNotBlank() },
                            remoteDns = remoteDns,
                            directDns = directDns,
                        )
                    )
                }) {
                    Icon(Icons.Rounded.Save, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Save DNS")
                }
            }
        }

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
                            onClick = {},
                            label = {
                                Text(pkg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        vm.updateAppSettings(
                                            ui.settings.copy(
                                                bypassPackages = ui.settings.bypassPackages - pkg,
                                            )
                                        )
                                    },
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
                verticalArrangement = Arrangement.spacedBy(8.dp),
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

        SettingsSection(title = "Startup") {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
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
                    checked = ui.settings.startOnBoot,
                    onCheckedChange = { enabled ->
                        vm.updateAppSettings(ui.settings.copy(startOnBoot = enabled))
                    },
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
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
                    checked = ui.settings.autoUpdateSubscriptions,
                    onCheckedChange = { enabled ->
                        vm.updateAppSettings(ui.settings.copy(autoUpdateSubscriptions = enabled))
                    },
                )
            }
            AnimatedVisibility(visible = ui.settings.autoUpdateSubscriptions) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Update interval",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SubscriptionIntervalOptions.forEach { hours ->
                            FilterChip(
                                selected = ui.settings.subscriptionUpdateIntervalHours == hours,
                                onClick = {
                                    vm.updateAppSettings(
                                        ui.settings.copy(subscriptionUpdateIntervalHours = hours),
                                    )
                                },
                                label = { Text("${hours}h") },
                                leadingIcon = if (ui.settings.subscriptionUpdateIntervalHours == hours) {
                                    { Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)) }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }
            }
        }

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
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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

        // ── About ─────────────────────────────────────────────────────────────
        SettingsSection(title = "About") {
            ListItem(
                headlineContent   = { Text("palazikVPN") },
                supportingContent = { Text("V${stringResource(R.string.app_version)} / by palaziks") },
                leadingContent    = { Icon(Icons.Rounded.Info, null) },
            )
        }
    }

    if (showAppPicker) {
        AppPickerDialog(
            apps = ui.installedApps,
            selected = ui.settings.bypassPackages.toSet(),
            onDismiss = { showAppPicker = false },
            onSave = { selected ->
                vm.updateAppSettings(ui.settings.copy(bypassPackages = selected.sorted()))
                showAppPicker = false
            },
        )
    }
}

@Composable
private fun AppPickerDialog(
    apps: List<com.palazik.vpn.data.model.InstalledApp>,
    selected: Set<String>,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var picked by remember(selected) { mutableStateOf(selected) }
    val filtered = remember(apps, query) {
        if (query.isBlank()) apps else apps.filter {
            it.label.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bypass Apps") },
        icon = { Icon(Icons.Rounded.Apps, null) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search apps") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(10.dp))
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(filtered, key = { it.packageName }) { app ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    picked = if (app.packageName in picked) {
                                        picked - app.packageName
                                    } else {
                                        picked + app.packageName
                                    }
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = app.packageName in picked,
                                onCheckedChange = { checked ->
                                    picked = if (checked) picked + app.packageName else picked - app.packageName
                                },
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    app.packageName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(picked.toList()) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    MinimalCard {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        content()
    }
}

@Composable
private fun ThemeRow(theme: AppTheme, isSelected: Boolean, onClick: () -> Unit) {
    val label = when (theme) {
        AppTheme.CYBER   -> "Signal"
        AppTheme.OCEAN   -> "Ocean"
        AppTheme.FOREST  -> "Forest"
        AppTheme.SUNSET  -> "Sunset"
        AppTheme.DYNAMIC -> "Dynamic"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                color = when (theme) {
                    AppTheme.CYBER -> MaterialTheme.colorScheme.primaryContainer
                    AppTheme.OCEAN -> com.palazik.vpn.ui.theme.OceanSoft
                    AppTheme.FOREST -> com.palazik.vpn.ui.theme.ForestSoft
                    AppTheme.SUNSET -> com.palazik.vpn.ui.theme.SunsetSoft
                    AppTheme.DYNAMIC -> MaterialTheme.colorScheme.surfaceVariant
                },
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.size(28.dp),
            ) {}
            Spacer(Modifier.width(10.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
        RadioButton(selected = isSelected, onClick = onClick)
    }
}
