package com.palazik.vpn.ui.screen

import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.ReportProblem
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.palazik.vpn.data.model.VpnState
import com.palazik.vpn.ui.viewmodel.MainViewModel
import java.text.DecimalFormat

private val EaseOut = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
private val EaseInOut = CubicBezierEasing(0.65f, 0f, 0.35f, 1f)

@Composable
fun HomeScreen(
    vm: MainViewModel,
    permLauncher: ActivityResultLauncher<Intent>,
) {
    val ui by vm.ui.collectAsState()
    val vpnState = ui.vpnState
    val isConnected = vpnState == VpnState.CONNECTED
    val isTransition = vpnState == VpnState.CONNECTING || vpnState == VpnState.DISCONNECTING
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(isConnected) {
        while (isConnected) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000)
        }
    }

    ScreenColumn(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PageHeader(
            title = "palazikVPN",
            subtitle = when (vpnState) {
                VpnState.CONNECTED -> "Secure tunnel is running"
                VpnState.CONNECTING -> "Preparing secure tunnel"
                VpnState.DISCONNECTING -> "Stopping tunnel"
                VpnState.ERROR -> "Connection needs attention"
                else -> "Ready when you are"
            },
        ) {
            StatusChip(
                text = vpnState.name.lowercase().replace('_', ' '),
                color = stateColor(vpnState),
            )
        }

        ActiveProfilePanel(
            name = ui.activeProfile?.name,
            endpoint = ui.activeProfile?.let { "${it.address}:${it.port}" },
            latency = ui.activeProfile?.latencyMs ?: -1L,
            onPing = { ui.activeProfile?.let(vm::pingProfile) },
        )

        ConnectionConsole(
            state = vpnState,
            onToggle = { vm.toggleVpn(permLauncher) },
        )

        AnimatedVisibility(
            visible = vpnState == VpnState.ERROR,
            enter = fadeIn(tween(180)) + expandVertically(),
            exit = fadeOut(tween(120)) + shrinkVertically(),
        ) {
            ErrorPanel(
                message = ui.lastError ?: "Connection failed",
                onRetry = { vm.toggleVpn(permLauncher) },
            )
        }

        AnimatedVisibility(
            visible = isConnected,
            enter = fadeIn(tween(220)) + expandVertically(spring(Spring.DampingRatioNoBouncy)),
            exit = fadeOut(tween(140)) + shrinkVertically(tween(180)),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                MinimalCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.VerifiedUser, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                ui.activeProfile?.name ?: "Active profile",
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "Connected for ${formatDuration((now - ui.connectedSince).coerceAtLeast(0L))}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        StatusChip(
                            text = formatBytes(ui.bytesIn + ui.bytesOut),
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricTile(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Rounded.ArrowDownward,
                        label = "Download",
                        value = formatBytes(ui.bytesIn),
                    )
                    MetricTile(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Rounded.ArrowUpward,
                        label = "Upload",
                        value = formatBytes(ui.bytesOut),
                    )
                }
            }
        }
    }
}

@Composable
private fun ActiveProfilePanel(
    name: String?,
    endpoint: String?,
    latency: Long,
    onPing: () -> Unit,
) {
    MinimalCard(accent = name != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                color = if (name == null) MaterialTheme.colorScheme.tertiaryContainer
                    else MaterialTheme.colorScheme.surface,
                shape = CircleShape,
            ) {
                Icon(
                    if (name == null) Icons.Rounded.ReportProblem else Icons.Rounded.Dns,
                    null,
                    Modifier.padding(10.dp).size(20.dp),
                    tint = if (name == null) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    name ?: "No profile selected",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    endpoint ?: "Import or select a profile first",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            AnimatedVisibility(visible = name != null) {
                OutlinedButton(
                    onClick = onPing,
                    shape = CircleShape,
                    contentPadding = compactButtonPadding(),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Icon(Icons.Rounded.NetworkCheck, null, Modifier.size(16.dp))
                    if (latency >= 0) {
                        Spacer(Modifier.width(6.dp))
                        Text("${latency}ms", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionConsole(
    state: VpnState,
    onToggle: () -> Unit,
) {
    val isConnected = state == VpnState.CONNECTED
    val isTransition = state == VpnState.CONNECTING || state == VpnState.DISCONNECTING
    val rotation = if (isTransition) {
        val transition = rememberInfiniteTransition(label = "connection_rotation")
        val value by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing)),
            label = "ring_rotation",
        )
        value
    } else {
        0f
    }
    val breathe = if (isTransition) {
        val transition = rememberInfiniteTransition(label = "connection_breathe")
        val value by transition.animateFloat(
            initialValue = 0.96f,
            targetValue = 1.04f,
            animationSpec = infiniteRepeatable(tween(1100, easing = EaseInOut), RepeatMode.Reverse),
            label = "button_breathe",
        )
        value
    } else {
        1f
    }
    val buttonScale by animateFloatAsState(
        targetValue = if (isTransition) breathe else if (isConnected) 1.02f else 1f,
        animationSpec = spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMediumLow),
        label = "connection_button_scale",
    )
    val color = stateColor(state)

    MinimalCard {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1.12f),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .fillMaxSize(0.78f)
                    .graphicsLayer {
                        alpha = if (isTransition || isConnected) 1f else 0.35f
                        rotationZ = if (isTransition) rotation else 0f
                    }
                    .background(
                        Brush.sweepGradient(
                            listOf(Color.Transparent, color.copy(alpha = 0.65f), Color.Transparent),
                        ),
                        CircleShape,
                    ),
            )
            Surface(
                color = MaterialTheme.colorScheme.background,
                shape = CircleShape,
                modifier = Modifier.fillMaxSize(0.68f),
            ) {}
            Button(
                onClick = onToggle,
                modifier = Modifier
                    .fillMaxSize(0.52f)
                    .scale(buttonScale),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isConnected || isTransition) color
                        else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (isConnected || isTransition) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurface,
                ),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = if (isConnected) 8.dp else 2.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AnimatedContent(
                        targetState = state,
                        transitionSpec = {
                            scaleIn(tween(160, easing = EaseOut)) + fadeIn(tween(120)) togetherWith
                                scaleOut(tween(90)) + fadeOut(tween(80))
                        },
                        label = "connection_icon",
                    ) { current ->
                        Icon(
                            when (current) {
                                VpnState.CONNECTED -> Icons.Rounded.Lock
                                VpnState.CONNECTING, VpnState.DISCONNECTING -> Icons.Rounded.PowerSettingsNew
                                else -> Icons.Rounded.LockOpen
                            },
                            null,
                            Modifier.size(34.dp),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    AnimatedContent(
                        targetState = state,
                        transitionSpec = {
                            fadeIn(tween(120)) + slideInVertically(tween(160)) { it / 3 } togetherWith
                                fadeOut(tween(80)) + slideOutVertically(tween(100)) { -it / 3 }
                        },
                        label = "connection_label",
                    ) { current ->
                        Text(
                            when (current) {
                                VpnState.CONNECTED -> "Disconnect"
                                VpnState.CONNECTING -> "Connecting"
                                VpnState.DISCONNECTING -> "Stopping"
                                else -> "Connect"
                            },
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorPanel(message: String, onRetry: () -> Unit) {
    MinimalCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Connection error", style = MaterialTheme.typography.titleSmall)
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = onRetry, contentPadding = compactButtonPadding()) {
                Icon(Icons.Rounded.Refresh, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Retry")
            }
        }
    }
}

@Composable
private fun MetricTile(
    modifier: Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
) {
    MinimalCard(modifier = modifier) {
        Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(10.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AnimatedContent(
            targetState = value,
            transitionSpec = {
                fadeIn(tween(110)) + slideInVertically(tween(140)) { -it / 2 } togetherWith
                    fadeOut(tween(80)) + slideOutVertically(tween(100)) { it / 2 }
            },
            label = "metric_$label",
        ) { nextValue ->
            Text(nextValue, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun stateColor(state: VpnState): Color = when (state) {
    VpnState.CONNECTED -> MaterialTheme.colorScheme.primary
    VpnState.CONNECTING, VpnState.DISCONNECTING -> MaterialTheme.colorScheme.tertiary
    VpnState.ERROR -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun formatBytes(bytes: Long): String {
    val df = DecimalFormat("#.##")
    return when {
        bytes >= 1_073_741_824 -> "${df.format(bytes / 1_073_741_824.0)} GB"
        bytes >= 1_048_576 -> "${df.format(bytes / 1_048_576.0)} MB"
        bytes >= 1_024 -> "${df.format(bytes / 1_024.0)} KB"
        else -> "$bytes B"
    }
}

private fun formatDuration(ms: Long): String {
    val seconds = ms / 1000
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
