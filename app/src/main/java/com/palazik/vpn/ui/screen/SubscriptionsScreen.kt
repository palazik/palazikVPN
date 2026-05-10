package com.palazik.vpn.ui.screen

import androidx.compose.animation.*
import androidx.compose.animation.core.*
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
import androidx.compose.ui.graphics.graphicsLayer
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

    ScreenColumn {
        PageHeader(
            title = "Subscriptions",
            subtitle = if (ui.subscriptions.isEmpty()) "No subscription sources" else "${ui.subscriptions.size} active sources",
        ) {
                AnimatedVisibility(visible = ui.subscriptions.isNotEmpty()) {
                    OutlinedButton(
                        onClick = { vm.updateAllSubscriptions() },
                        enabled = !ui.isUpdatingSubscriptions,
                        contentPadding = compactButtonPadding(),
                    ) {
                        Icon(Icons.Rounded.Refresh, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Update")
                    }
                }
                FilledTonalButton(onClick = { showAdd = true }, contentPadding = compactButtonPadding()) {
                    Icon(Icons.Rounded.Add, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add")
                }
        }

        AnimatedContent(
            targetState = ui.subscriptions.isEmpty(),
            transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) },
            label = "subs_content",
        ) { isEmpty ->
            if (isEmpty) {
                EmptyState(
                    icon = Icons.Rounded.Subscriptions,
                    title = "No subscriptions yet",
                    body = "Add a URL to sync profile lists automatically.",
                ) {
                    FilledTonalButton(onClick = { showAdd = true }) {
                        Icon(Icons.Rounded.Add, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Add Subscription")
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ui.subscriptions, key = { it.id }) { sub ->
                        // Fade only, no slide.
                        AnimatedVisibility(
                            visible = true,
                            enter   = fadeIn(tween(200)),
                        ) {
                            SubscriptionCard(
                                sub      = sub,
                                isUpdating = ui.isUpdatingSubscriptions || sub.id in ui.updatingSubscriptionIds,
                                autoUpdateEnabled = ui.settings.autoUpdateSubscriptions,
                                updateIntervalHours = ui.settings.subscriptionUpdateIntervalHours,
                                onUpdate = { vm.updateSubscription(sub) },
                                onChooseBest = { vm.chooseBestProfileForSubscription(sub) },
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
                        placeholder   = { Text("https://...") },
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
    updateIntervalHours: Long,
    onUpdate: () -> Unit,
    onChooseBest: () -> Unit,
    onDelete: () -> Unit,
) {
    val sdf = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }
    val cardColor by animateColorAsState(
        targetValue = if (isUpdating) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surface,
        animationSpec = tween(220),
        label = "subscription_card_color_${sub.id}",
    )
    val refreshRotation = if (isUpdating) {
        val rotation by rememberInfiniteTransition(label = "subscription_refresh_${sub.id}")
            .animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(900, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "subscription_refresh_rotation_${sub.id}",
        )
        rotation
    } else {
        0f
    }

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(tween(240)),
        colors = CardDefaults.elevatedCardColors(containerColor = cardColor),
        shape    = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
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
                AnimatedContent(
                    targetState = sub.profileCount,
                    transitionSpec = { fadeIn(tween(140)) + scaleIn() togetherWith fadeOut(tween(90)) + scaleOut() },
                    label = "sub_profile_count_${sub.id}",
                ) { count ->
                    StatusChip("$count profiles", MaterialTheme.colorScheme.primary)
                }
                if (sub.lastUpdated > 0) {
                    StatusChip("Updated ${sdf.format(Date(sub.lastUpdated))}", MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusChip(
                    if (autoUpdateEnabled) "Auto ${updateIntervalHours}h" else "Manual",
                    if (autoUpdateEnabled) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                    Icons.Rounded.Schedule,
                )
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(
                color     = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                thickness = 0.5.dp,
            )
            Spacer(Modifier.height(2.dp))

            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(
                    onClick = onChooseBest,
                    enabled = !isUpdating && sub.profileCount > 0,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Icon(Icons.Rounded.Speed, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Best", style = MaterialTheme.typography.labelMedium)
                }
                TextButton(
                    onClick = onUpdate,
                    enabled = !isUpdating,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    AnimatedContent(
                        targetState = isUpdating,
                        transitionSpec = { fadeIn(tween(120)) + scaleIn() togetherWith fadeOut(tween(90)) + scaleOut() },
                        label = "sub_update_icon_${sub.id}",
                    ) { updating ->
                        if (updating) {
                            Icon(
                                Icons.Rounded.Refresh,
                                null,
                                Modifier
                                    .size(16.dp)
                                    .graphicsLayer { rotationZ = refreshRotation },
                            )
                        } else {
                            Icon(Icons.Rounded.Refresh, null, Modifier.size(16.dp))
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                    AnimatedContent(
                        targetState = isUpdating,
                        transitionSpec = { fadeIn(tween(120)) togetherWith fadeOut(tween(90)) },
                        label = "sub_update_label_${sub.id}",
                    ) { updating ->
                        Text(if (updating) "Updating" else "Update", style = MaterialTheme.typography.labelMedium)
                    }
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
