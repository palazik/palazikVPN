package com.palazik.vpn.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.palazik.vpn.ui.theme.AppTheme
import com.palazik.vpn.ui.theme.DarkModePreference
import com.palazik.vpn.ui.viewmodel.MainViewModel

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val ui by vm.ui.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))

        // ── Dark mode ─────────────────────────────────────────────────────────
        SettingsSection(title = "Appearance") {
            Text("Dark Mode", style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = 8.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                DarkModePreference.values().forEachIndexed { idx, pref ->
                    SegmentedButton(
                        shape    = SegmentedButtonDefaults.itemShape(idx, DarkModePreference.values().size),
                        selected = ui.darkMode == pref,
                        onClick  = { vm.setDarkMode(pref) },
                        label    = { Text(pref.name.replace('_', ' ')) },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── Theme palette ─────────────────────────────────────────────────
            Text("Color Theme", style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = 8.dp))
            val themes = AppTheme.values()
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                themes.forEach { theme ->
                    ThemeRow(theme, isSelected = ui.appTheme == theme) {
                        vm.setAppTheme(theme)
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // ── About ─────────────────────────────────────────────────────────────
        SettingsSection(title = "About") {
            ListItem(
                headlineContent = { Text("palazikVPN") },
                supportingContent = { Text("Built by palazik • kernel dev edition") },
                leadingContent = { Icon(Icons.Rounded.Info, null) },
            )
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            content()
        }
    }
}

@Composable
private fun ThemeRow(theme: AppTheme, isSelected: Boolean, onClick: () -> Unit) {
    val themeLabel = when (theme) {
        AppTheme.CYBER   -> "⚡ Cyber (Dark-first)"
        AppTheme.OCEAN   -> "🌊 Ocean"
        AppTheme.FOREST  -> "🌿 Forest"
        AppTheme.SUNSET  -> "🔥 Sunset"
        AppTheme.DYNAMIC -> "🎨 Dynamic (Android 12+)"
    }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(themeLabel, style = MaterialTheme.typography.bodyMedium)
        RadioButton(selected = isSelected, onClick = onClick)
    }
}
