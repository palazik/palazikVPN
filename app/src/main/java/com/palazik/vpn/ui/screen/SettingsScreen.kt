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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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

private val PingModeOptions             = PingMode.values().toList()
private val SubscriptionIntervalOptions = listOf(2L, 6L, 12L, 24L)

// ── Routes for the settings sub-screens ─────────────────────────────────────
object SettingsRoutes {
    const val STYLE        = "style"
    const val CONNECTION   = "settings/connection"
    const val DNS          = "settings/dns"
    const val ROUTING      = "settings/routing"
    const val SUBSCRIPTION  = "settings/subscriptions"
    const val SPLIT        = "settings/split"
    const val BACKUP       = "settings/backup"
    const val STARTUP      = "settings/startup"
    const val DIAGNOSTICS  = "settings/diagnostics"
    const val ABOUT        = "settings/about"
}

private data class SettingsEntry(
    val route: String,
    val title: String,
    val summary: String,
    val icon: ImageVector,
)

private data class SettingsGroup(val title: String, val entries: List<SettingsEntry>)

private val SettingsGroups = listOf(
    SettingsGroup("Appearance", listOf(
        SettingsEntry(SettingsRoutes.STYLE, "Style", "Design system, dark mode, color theme", Icons.Rounded.Palette),
    )),
    SettingsGroup("Connection", listOf(
        SettingsEntry(SettingsRoutes.CONNECTION, "Connection", "Ping test mode", Icons.Rounded.NetworkCheck),
        SettingsEntry(SettingsRoutes.DNS, "DNS", "VPN, remote and direct DNS", Icons.Rounded.Dns),
        SettingsEntry(SettingsRoutes.ROUTING, "Routing & Privacy", "Ad block, bypass China, IPv6, kill switch", Icons.Rounded.AltRoute),
    )),
    SettingsGroup("Profiles & data", listOf(
        SettingsEntry(SettingsRoutes.SUBSCRIPTION, "Subscriptions", "Auto-update and User-Agent", Icons.Rounded.Subscriptions),
        SettingsEntry(SettingsRoutes.SPLIT, "Split Tunneling", "Apps that bypass the VPN", Icons.Rounded.Apps),
        SettingsEntry(SettingsRoutes.BACKUP, "Backup", "Export / import profiles", Icons.Rounded.Backup),
    )),
    SettingsGroup("System", listOf(
        SettingsEntry(SettingsRoutes.STARTUP, "Startup", "Auto-connect on boot", Icons.Rounded.PowerSettingsNew),
        SettingsEntry(SettingsRoutes.DIAGNOSTICS, "Diagnostics", "Connection log", Icons.Rounded.BugReport),
        SettingsEntry(SettingsRoutes.ABOUT, "About", "Version and info", Icons.Rounded.Info),
    )),
)

// ─────────────────────────────────────────────────────────────────────────────
// Settings hub
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun SettingsScreen(vm: MainViewModel, onNavigate: (String) -> Unit) {
    val designSystem = LocalDesignSystem.current
    if (designSystem == DesignSystem.MIUIX) MiuixSettingsHub(onNavigate)
    else Md3SettingsHub(onNavigate)
}

@Composable
private fun MiuixSettingsHub(onNavigate: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        SmallTitle(text = "Settings")
        SettingsGroups.forEach { group ->
            SmallTitle(text = group.title)
            MiuixCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    group.entries.forEach { entry ->
                        ArrowPreference(
                            title = entry.title,
                            summary = entry.summary,
                            onClick = { onNavigate(entry.route) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        Spacer(Modifier.height(8.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Md3SettingsHub(onNavigate: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        SettingsGroups.forEach { group ->
            Text(
                group.title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp),
            )
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column {
                    group.entries.forEachIndexed { index, entry ->
                        if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                        ListItem(
                            headlineContent   = { Text(entry.title) },
                            supportingContent = { Text(entry.summary) },
                            leadingContent    = { Icon(entry.icon, null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent   = { Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                            modifier = Modifier.clickable { onNavigate(entry.route) },
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Sub-screen scaffold (shared by both design systems)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SettingsScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
            }
            Text(title, style = MaterialTheme.typography.headlineSmall)
        }
        Column(
            Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Sub-screens
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionSettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsState()
    SettingsScaffold("Connection", onBack) {
        SettingsCard {
            Text("Ping Test Mode", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 4.dp))
            Text(
                "TCP — raw socket connect (fastest, most accurate, default).\n" +
                "GET / HEAD — Cloudflare request through the running VPN.",
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
}

@Composable
fun DnsSettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsState()
    SettingsScaffold("DNS", onBack) {
        SettingsCard {
            var tunDns    by remember(ui.settings.dnsServers) { mutableStateOf(ui.settings.dnsServers.joinToString(", ")) }
            var remoteDns by remember(ui.settings.remoteDns)  { mutableStateOf(ui.settings.remoteDns) }
            var directDns by remember(ui.settings.directDns)  { mutableStateOf(ui.settings.directDns) }

            OutlinedTextField(
                value = tunDns, onValueChange = { tunDns = it },
                label = { Text("VPN DNS servers") },
                supportingText = { Text("Comma separated") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = remoteDns, onValueChange = { remoteDns = it },
                label = { Text("Remote DNS") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = directDns, onValueChange = { directDns = it },
                label = { Text("Direct DNS") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(onClick = {
                    vm.updateAppSettings(ui.settings.copy(
                        dnsServers = tunDns.split(",", "\n").map { it.trim() }.filter { it.isNotBlank() },
                        remoteDns  = remoteDns,
                        directDns  = directDns,
                    ))
                }) {
                    Icon(Icons.Rounded.Save, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Save DNS")
                }
            }
        }
    }
}

@Composable
fun RoutingSettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsState()
    SettingsScaffold("Routing & Privacy", onBack) {
        SettingsCard { RoutingSettingsContent(vm, ui.settings) }
    }
}

@Composable
fun SubscriptionSettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsState()
    SettingsScaffold("Subscriptions", onBack) {
        SettingsCard { StartupAutoUpdateContent(vm, ui.settings) }
        SettingsCard { SubscriptionUaContent(vm, ui.settings) }
    }
}

@Composable
fun SplitTunnelSettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsState()
    SettingsScaffold("Split Tunneling", onBack) {
        SettingsCard { SplitTunnelContent(vm, ui.settings, ui.installedApps) }
    }
}

@Composable
fun BackupSettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    SettingsScaffold("Backup", onBack) {
        SettingsCard { BackupSettingsContent(vm) }
    }
}

@Composable
fun StartupSettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsState()
    SettingsScaffold("Startup", onBack) {
        SettingsCard {
            SettingRow(
                title = "Auto-connect on boot",
                subtitle = "Starts the selected profile after reboot when VPN permission is already granted.",
                checked = ui.settings.startOnBoot,
                onChange = { vm.updateAppSettings(ui.settings.copy(startOnBoot = it)) },
            )
        }
        SettingsCard { StartupAutoUpdateContent(vm, ui.settings) }
    }
}

@Composable
fun DiagnosticsSettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val diagnostics by vm.diagnostics.collectAsState()
    val clipboard = LocalClipboardManager.current
    SettingsScaffold("Diagnostics", onBack) {
        SettingsCard {
            if (diagnostics.isEmpty()) {
                Text("No connection events yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    diagnostics.forEach { line ->
                        Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(
                    onClick = { clipboard.setText(AnnotatedString(diagnostics.joinToString("\n"))) },
                    enabled = diagnostics.isNotEmpty(),
                ) {
                    Icon(Icons.Rounded.ContentCopy, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Copy Logs")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutSettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    SettingsScaffold("About", onBack) {
        SettingsCard {
            ListItem(
                headlineContent   = { Text("palazikVPN") },
                supportingContent = { Text("V${stringResource(R.string.app_version)} • by palaziks") },
                leadingContent    = { Icon(Icons.Rounded.Info, null) },
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Shared section content
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SplitTunnelContent(
    vm: MainViewModel,
    settings: AppSettings,
    installedApps: List<com.palazik.vpn.data.model.InstalledApp>,
) {
    var showAppPicker by remember { mutableStateOf(false) }

    Text(
        "${settings.bypassPackages.size} apps bypass VPN",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    if (settings.bypassPackages.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            settings.bypassPackages.take(6).forEach { pkg ->
                AssistChip(
                    onClick = {},
                    label = { Text(pkg, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingIcon = {
                        IconButton(
                            onClick = { vm.updateAppSettings(settings.copy(bypassPackages = settings.bypassPackages - pkg)) },
                            modifier = Modifier.size(24.dp),
                        ) { Icon(Icons.Rounded.Close, null, Modifier.size(16.dp)) }
                    },
                )
            }
            if (settings.bypassPackages.size > 6) {
                Text("+${settings.bypassPackages.size - 6} more", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(onClick = { vm.updateAppSettings(settings.copy(bypassPackages = emptyList())) }) {
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

    if (showAppPicker) {
        AppPickerDialog(
            apps = installedApps,
            selected = settings.bypassPackages.toSet(),
            onDismiss = { showAppPicker = false },
            onSave = { selected ->
                vm.updateAppSettings(settings.copy(bypassPackages = selected.sorted()))
                showAppPicker = false
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StartupAutoUpdateContent(vm: MainViewModel, settings: AppSettings) {
    SettingRow(
        title = "Auto-update subscriptions",
        subtitle = "Refreshes subscriptions every ${settings.subscriptionUpdateIntervalHours}h when network is available.",
        checked = settings.autoUpdateSubscriptions,
        onChange = { vm.updateAppSettings(settings.copy(autoUpdateSubscriptions = it)) },
    )
    AnimatedVisibility(visible = settings.autoUpdateSubscriptions) {
        Column {
            Spacer(Modifier.height(12.dp))
            Text("Update interval", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SubscriptionIntervalOptions.forEach { hours ->
                    FilterChip(
                        selected = settings.subscriptionUpdateIntervalHours == hours,
                        onClick = { vm.updateAppSettings(settings.copy(subscriptionUpdateIntervalHours = hours)) },
                        label = { Text("${hours}h") },
                        leadingIcon = if (settings.subscriptionUpdateIntervalHours == hours) {
                            { Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)) }
                        } else null,
                    )
                }
            }
        }
    }
}

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
    Text("Subscription fetch", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 8.dp))
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
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

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
                    value = query, onValueChange = { query = it },
                    label = { Text("Search apps") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                )
                Spacer(Modifier.height(10.dp))
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
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
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
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
