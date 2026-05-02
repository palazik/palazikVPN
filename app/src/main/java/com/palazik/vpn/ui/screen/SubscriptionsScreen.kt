package com.palazik.vpn.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.palazik.vpn.data.model.Subscription
import com.palazik.vpn.ui.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun SubscriptionsScreen(vm: MainViewModel) {
    val ui by vm.ui.collectAsState()

    var showAdd    by remember { mutableStateOf(false) }
    var subName    by remember { mutableStateOf("") }
    var subUrl     by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(16.dp)) {

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("Subscriptions", style = MaterialTheme.typography.headlineSmall)
            Row {
                OutlinedButton(onClick = { vm.updateAllSubscriptions() }) {
                    Icon(Icons.Rounded.Refresh, null)
                    Spacer(Modifier.width(4.dp))
                    Text("Update All")
                }
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(onClick = { showAdd = true }) {
                    Icon(Icons.Rounded.Add, null)
                    Spacer(Modifier.width(4.dp))
                    Text("Add")
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        if (ui.subscriptions.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.Subscriptions, null, Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(12.dp))
                    Text("No subscriptions yet", style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(8.dp))
                    Text("Add a subscription URL to import profiles automatically.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline)
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ui.subscriptions, key = { it.id }) { sub ->
                    SubscriptionCard(sub,
                        onUpdate = { vm.updateSubscription(sub) },
                        onDelete = { vm.removeSubscription(sub.id) },
                    )
                }
            }
        }
    }

    // ── Add subscription dialog ───────────────────────────────────────────────
    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false; subName = ""; subUrl = "" },
            title = { Text("Add Subscription") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = subName, onValueChange = { subName = it },
                        label = { Text("Name") }, modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = subUrl, onValueChange = { subUrl = it },
                        label = { Text("Subscription URL") }, modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (subUrl.isNotBlank()) {
                        vm.addSubscription(subName.ifBlank { "Subscription" }, subUrl)
                        showAdd = false; subName = ""; subUrl = ""
                    }
                }) { Text("Add & Fetch") }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false; subName = ""; subUrl = "" }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SubscriptionCard(
    sub: Subscription,
    onUpdate: () -> Unit,
    onDelete: () -> Unit,
) {
    val sdf = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(sub.name, style = MaterialTheme.typography.titleSmall)
                Text(sub.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    maxLines = 1,
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = {}, label = { Text("${sub.profileCount} profiles") })
                    if (sub.lastUpdated > 0) {
                        AssistChip(onClick = {}, label = {
                            Text("Updated ${sdf.format(Date(sub.lastUpdated))}")
                        })
                    }
                }
            }
            IconButton(onClick = onUpdate) { Icon(Icons.Rounded.Refresh, "Update") }
            IconButton(onClick = onDelete) {
                Icon(Icons.Rounded.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}
