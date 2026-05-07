package com.palazik.vpn.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.palazik.vpn.data.model.PingMode
import com.palazik.vpn.ui.theme.AppTheme
import com.palazik.vpn.ui.theme.DarkModePreference
import com.palazik.vpn.ui.viewmodel.MainViewModel

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val ui by vm.ui.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))

        // ── Appearance ────────────────────────────────────────────────────────
        SettingsSection(title = "Appearance") {
            Text(
                "Dark Mode",
                style    = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                DarkModePreference.values().forEachIndexed { idx, pref ->
                    SegmentedButton(
                        shape    = SegmentedButtonDefaults.itemShape(idx, DarkModePreference.values().size),
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
                AppTheme.values().forEach { theme ->
                    ThemeRow(theme, isSelected = ui.appTheme == theme) {
                        vm.setAppTheme(theme)
                    }
                }
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
                "GET / HEAD — HTTP request to Cloudflare to measure internet latency.",
                style    = MaterialTheme.typography.bodySmall,
                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                PingMode.values().forEachIndexed { idx, mode ->
                    SegmentedButton(
                        shape    = SegmentedButtonDefaults.itemShape(idx, PingMode.values().size),
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

        Spacer(Modifier.height(8.dp))

        SettingsSection(title = "Split Tunneling") {
            var packages by remember(ui.settings.bypassPackages) {
                mutableStateOf(ui.settings.bypassPackages.joinToString("\n"))
            }
            OutlinedTextField(
                value = packages,
                onValueChange = { packages = it },
                label = { Text("Bypass package names") },
                supportingText = { Text("One package per line; app itself is always bypassed") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(onClick = {
                    vm.updateAppSettings(
                        ui.settings.copy(
                            bypassPackages = packages.split("\n", ",").map { it.trim() }.filter { it.isNotBlank() },
                        )
                    )
                }) {
                    Icon(Icons.Rounded.Save, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Save Apps")
                }
            }
        }

        Spacer(Modifier.height(8.dp))

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
        }

        Spacer(Modifier.height(8.dp))

        // ── About ─────────────────────────────────────────────────────────────
        SettingsSection(title = "About") {
            ListItem(
                headlineContent   = { Text("palazikVPN") },
                supportingContent = { Text("V1.0.0 • by palaziks") },
                leadingContent    = { Icon(Icons.Rounded.Info, null) },
            )
        }
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

@Composable
private fun ThemeRow(theme: AppTheme, isSelected: Boolean, onClick: () -> Unit) {
    val label = when (theme) {
        AppTheme.CYBER   -> "⚡ Cyber (Dark-first)"
        AppTheme.OCEAN   -> "🌊 Ocean"
        AppTheme.FOREST  -> "🌿 Forest"
        AppTheme.SUNSET  -> "🔥 Sunset"
        AppTheme.DYNAMIC -> "🎨 Dynamic (Android 12+)"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        RadioButton(selected = isSelected, onClick = onClick)
    }
}
