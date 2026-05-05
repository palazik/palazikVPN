package com.palazik.vpn.ui.screen

import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.palazik.vpn.data.model.VpnState
import com.palazik.vpn.ui.viewmodel.MainViewModel
import java.text.DecimalFormat

@Composable
fun HomeScreen(
    vm: MainViewModel,
    permLauncher: ActivityResultLauncher<Intent>,
) {
    val ui by vm.ui.collectAsState()
    val vpnState     = ui.vpnState
    val isConnected  = vpnState == VpnState.CONNECTED
    val isTransition = vpnState == VpnState.CONNECTING || vpnState == VpnState.DISCONNECTING

    // Smooth infinite pulse for connecting/disconnecting
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue  = 1.10f,
        animationSpec = infiniteRepeatable(
            animation  = tween(900, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse_scale",
    )
    // Glow ring alpha breathes when connected
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.18f,
        targetValue  = 0.38f,
        animationSpec = infiniteRepeatable(
            animation  = tween(1600, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glow_alpha",
    )

    val buttonScale by animateFloatAsState(
        targetValue   = if (isTransition) pulseScale else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label         = "button_scale",
    )

    val statusColor = when (vpnState) {
        VpnState.CONNECTED                         -> MaterialTheme.colorScheme.primary
        VpnState.CONNECTING, VpnState.DISCONNECTING -> MaterialTheme.colorScheme.tertiary
        else                                       -> MaterialTheme.colorScheme.outline
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp)
            .padding(top = 40.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {

        // ── Header ────────────────────────────────────────────────────────────
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text  = "palazikVPN",
                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(6.dp))
            AnimatedContent(
                targetState  = ui.activeProfile?.name ?: "No profile selected",
                transitionSpec = {
                    fadeIn(tween(200)) togetherWith fadeOut(tween(200))
                },
                label = "profile_name",
            ) { name ->
                Text(
                    text  = name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }

        // ── Big connect button ────────────────────────────────────────────────
        Box(contentAlignment = Alignment.Center) {
            // Outer glow — always rendered but alpha animated
            if (isConnected || isTransition) {
                Box(
                    Modifier
                        .size(260.dp)
                        .graphicsLayer { alpha = if (isConnected) glowAlpha else glowAlpha * 0.5f }
                        .background(
                            Brush.radialGradient(
                                listOf(
                                    statusColor.copy(alpha = 1f),
                                    Color.Transparent,
                                )
                            ),
                            CircleShape,
                        )
                        .blur(32.dp)
                )
            }
            // Inner ring — animate via graphicsLayer to avoid ColumnScope receiver requirement
            val innerRingAlpha by animateFloatAsState(
                targetValue   = if (isConnected) 1f else 0f,
                animationSpec = spring(Spring.DampingRatioMediumBouncy),
                label         = "inner_ring_alpha",
            )
            val innerRingScale by animateFloatAsState(
                targetValue   = if (isConnected) 1f else 0.6f,
                animationSpec = spring(Spring.DampingRatioMediumBouncy),
                label         = "inner_ring_scale",
            )
            Box(
                Modifier
                    .size(196.dp)
                    .graphicsLayer { alpha = innerRingAlpha; scaleX = innerRingScale; scaleY = innerRingScale }
                    .background(
                        Brush.radialGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                Color.Transparent,
                            )
                        ),
                        CircleShape,
                    )
            )

            Button(
                onClick  = { vm.toggleVpn(permLauncher) },
                modifier = Modifier
                    .size(164.dp)
                    .scale(buttonScale),
                shape  = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = when {
                        isConnected  -> MaterialTheme.colorScheme.primary
                        isTransition -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.85f)
                        else         -> MaterialTheme.colorScheme.surfaceVariant
                    },
                ),
                elevation = ButtonDefaults.buttonElevation(
                    defaultElevation = if (isConnected) 16.dp else 4.dp,
                ),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AnimatedContent(
                        targetState  = isConnected,
                        transitionSpec = {
                            scaleIn(spring(Spring.DampingRatioMediumBouncy)) +
                                fadeIn(tween(150)) togetherWith
                                scaleOut(tween(100)) + fadeOut(tween(100))
                        },
                        label = "lock_icon",
                    ) { connected ->
                        Icon(
                            imageVector = if (connected) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = if (connected || isTransition)
                                MaterialTheme.colorScheme.onPrimary
                            else
                                MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    AnimatedContent(
                        targetState = vpnState,
                        transitionSpec = {
                            fadeIn(tween(150)) togetherWith fadeOut(tween(100))
                        },
                        label = "button_label",
                    ) { state ->
                        Text(
                            text = when (state) {
                                VpnState.CONNECTED     -> "Disconnect"
                                VpnState.CONNECTING    -> "Connecting"
                                VpnState.DISCONNECTING -> "Stopping"
                                else                   -> "Connect"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = if (isConnected || isTransition)
                                MaterialTheme.colorScheme.onPrimary
                            else
                                MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }

        // ── Status pill ───────────────────────────────────────────────────────
        AnimatedContent(
            targetState = vpnState,
            transitionSpec = {
                fadeIn(tween(200)) + slideInVertically { it / 2 } togetherWith
                    fadeOut(tween(150)) + slideOutVertically { -it / 2 }
            },
            label = "status_badge",
        ) { state ->
            Surface(
                shape = CircleShape,
                color = statusColor.copy(alpha = 0.14f),
            ) {
                Row(
                    Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Animated dot
                    val dotScale by animateFloatAsState(
                        targetValue   = if (isTransition) pulseScale else 1f,
                        animationSpec = spring(),
                        label         = "dot_scale",
                    )
                    Box(
                        Modifier
                            .size(8.dp)
                            .scale(dotScale)
                            .background(statusColor, CircleShape)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text  = state.name.replace('_', ' '),
                        style = MaterialTheme.typography.labelLarge,
                        color = statusColor,
                    )
                }
            }
        }

        // ── Traffic stats ─────────────────────────────────────────────────────
        AnimatedVisibility(
            visible = isConnected,
            enter   = expandVertically(spring(Spring.DampingRatioLowBouncy)) + fadeIn(tween(300)),
            exit    = shrinkVertically(tween(200)) + fadeOut(tween(150)),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TrafficCard(modifier = Modifier.weight(1f), label = "↓  Download", bytes = ui.bytesIn)
                TrafficCard(modifier = Modifier.weight(1f), label = "↑  Upload",   bytes = ui.bytesOut)
            }
        }

        // ── Quick ping ────────────────────────────────────────────────────────
        AnimatedVisibility(
            visible = ui.activeProfile != null,
            enter   = fadeIn(tween(200)) + expandVertically(),
            exit    = fadeOut(tween(150)) + shrinkVertically(),
        ) {
            ui.activeProfile?.let { profile ->
                OutlinedButton(
                    onClick  = { vm.pingProfile(profile) },
                    modifier = Modifier.fillMaxWidth(),
                    shape    = MaterialTheme.shapes.medium,
                ) {
                    Icon(Icons.Rounded.NetworkCheck, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Ping ${profile.name}")
                    if (profile.latencyMs >= 0) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            color = latencyColor(profile.latencyMs).copy(alpha = 0.15f),
                            shape = CircleShape,
                        ) {
                            Text(
                                text     = "${profile.latencyMs}ms",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style    = MaterialTheme.typography.labelMedium,
                                color    = latencyColor(profile.latencyMs),
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrafficCard(modifier: Modifier = Modifier, label: String, bytes: Long) {
    ElevatedCard(
        modifier = modifier,
        shape    = MaterialTheme.shapes.large,
    ) {
        Column(
            Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(6.dp))
            AnimatedContent(
                targetState  = formatBytes(bytes),
                transitionSpec = { fadeIn(tween(120)) togetherWith fadeOut(tween(80)) },
                label = "traffic_value",
            ) { value ->
                Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun latencyColor(ms: Long): Color = when {
    ms < 150 -> MaterialTheme.colorScheme.primary
    ms < 400 -> Color(0xFFFFC107)
    else     -> MaterialTheme.colorScheme.error
}

private fun formatBytes(b: Long): String {
    val df = DecimalFormat("#.##")
    return when {
        b >= 1_073_741_824 -> "${df.format(b / 1_073_741_824.0)} GB"
        b >= 1_048_576     -> "${df.format(b / 1_048_576.0)} MB"
        b >= 1024          -> "${df.format(b / 1024.0)} KB"
        else               -> "$b B"
    }
}

private val EaseInOutSine = CubicBezierEasing(0.37f, 0f, 0.63f, 1f)
