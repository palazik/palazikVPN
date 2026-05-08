package com.palazik.vpn.ui.screen

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.palazik.vpn.data.model.Subscription
import com.palazik.vpn.ui.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SubscriptionsScreen(vm: MainViewModel) {
    val ui by vm.ui.collectAsState()

    var showAdd by remember { mutableStateOf(false) }
    var subName by remember { mutableStateOf("") }
    var subUrl  by remember { mutableStateOf("") }
    var deleteSub by remember { mutableStateOf<Subscription?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {

        Column(
            Modifier.fillMaxWidth(),
        ) {
            Text("Subscriptions", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            AnimatedVisibility(visible = ui.subscriptions.isNotEmpty()) {
                Text(
                    "${ui.subscriptions.size} active",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }
            Spacer(Modifier.height(8.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                AnimatedVisibility(visible = ui.subscriptions.isNotEmpty()) {
                    OutlinedButton(
                        onClick = { vm.updateAllSubscriptions() },
                        enabled = !ui.isUpdatingSubscriptions,
                    ) {
                        Icon(Icons.Rounded.Refresh, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Update All")
                    }
                }
                FilledTonalButton(onClick = { showAdd = true }) {
                    Icon(Icons.Rounded.Add, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add", color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        AnimatedContent(
            targetState = ui.subscriptions.isEmpty(),
            transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) },
            label = "subs_content",
        ) { isEmpty ->
            if (isEmpty) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Rounded.Subscriptions, null,
                            Modifier.size(72.dp),
                            tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "No subscriptions yet",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Add a subscription URL to import profiles automatically.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        Spacer(Modifier.height(24.dp))
                        FilledTonalButton(onClick = { showAdd = true }) {
                            Icon(Icons.Rounded.Add, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Add Subscription", color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ui.subscriptions, key = { it.id }) { sub ->
                        // Fade only — no slide
                        AnimatedVisibility(
                            visible = true,
                            enter   = fadeIn(tween(200)),
                        ) {
                            SubscriptionCard(
                                sub      = sub,
                                isUpdating = ui.isUpdatingSubscriptions,
                                autoUpdateEnabled = ui.settings.autoUpdateSubscriptions,
                                onUpdate = { vm.updateSubscription(sub) },
                                onDelete = { deleteSub = sub },
                            )
                        }
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
            }
        }
    }

    // ── Add dialog ───────────────────────────────────────────────────────────
    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false; subName = ""; subUrl = "" },
            title = { Text("Add Subscription") },
            icon  = { Icon(Icons.Rounded.Subscriptions, null) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value         = subName,
                        onValueChange = { subName = it },
                        label         = { Text("Name") },
                        placeholder   = { Text("My Server") },
                        modifier      = Modifier.fillMaxWidth(),
                        singleLine    = true,
                    )
                    OutlinedTextField(
                        value         = subUrl,
                        onValueChange = { subUrl = it },
                        label         = { Text("Subscription URL") },
                        placeholder   = { Text("https://…") },
                        modifier      = Modifier.fillMaxWidth(),
                        singleLine    = true,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick  = {
                        if (subUrl.isNotBlank()) {
                            vm.addSubscription(subName.ifBlank { "Subscription" }, subUrl.trim())
                            showAdd = false; subName = ""; subUrl = ""
                        }
                    },
                    enabled = subUrl.isNotBlank(),
                ) { Text("Add & Fetch") }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false; subName = ""; subUrl = "" }) { Text("Cancel") }
            },
        )
    }

    deleteSub?.let { sub ->
        AlertDialog(
            onDismissRequest = { deleteSub = null },
            title = { Text("Delete Subscription") },
            icon = { Icon(Icons.Rounded.Delete, null) },
            text = { Text("Delete \"${sub.name}\" and all profiles from it?") },
            confirmButton = {
                Button(
                    onClick = {
                        vm.removeSubscription(sub.id)
                        deleteSub = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteSub = null }) { Text("Cancel") }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubscriptionCard(
    sub: Subscription,
    isUpdating: Boolean,
    autoUpdateEnabled: Boolean,
    onUpdate: () -> Unit,
    onDelete: () -> Unit,
) {
    val sdf = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape    = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    shape = CircleShape,
                ) {
                    Icon(
                        Icons.Rounded.Subscriptions,
                        null,
                        Modifier.padding(8.dp).size(20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(sub.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        sub.url,
                        style    = MaterialTheme.typography.bodySmall,
                        color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        maxLines = 1,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    shape = CircleShape,
                ) {
                    Text(
                        "${sub.profileCount} profiles",
                        modifier   = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style      = MaterialTheme.typography.labelSmall,
                        color      = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                    )
                }
                if (sub.lastUpdated > 0) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = CircleShape,
                    ) {
                        Text(
                            "Updated ${sdf.format(Date(sub.lastUpdated))}",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            style    = MaterialTheme.typography.labelSmall,
                            color    = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Surface(
                    color = if (autoUpdateEnabled) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.13f)
                        else MaterialTheme.colorScheme.surfaceVariant,
                    shape = CircleShape,
                ) {
                    Row(
                        Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Rounded.Schedule,
                            null,
                            Modifier.size(13.dp),
                            tint = if (autoUpdateEnabled) MaterialTheme.colorScheme.tertiary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (autoUpdateEnabled) "Auto 2h" else "Manual",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (autoUpdateEnabled) MaterialTheme.colorScheme.tertiary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(
                color     = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                thickness = 0.5.dp,
            )
            Spacer(Modifier.height(2.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = onUpdate,
                    enabled = !isUpdating,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    if (isUpdating) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Rounded.Refresh, null, Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(4.dp))
                    Text("Update", style = MaterialTheme.typography.labelMedium)
                }
                TextButton(
                    onClick        = onDelete,
                    enabled        = !isUpdating,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    colors         = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Rounded.Delete, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Delete", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
