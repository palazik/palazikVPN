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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.palazik.vpn.data.model.VpnState
import com.palazik.vpn.ui.viewmodel.MainViewModel
import java.text.DecimalFormat

@Composable
fun HomeScreen(
    vm: MainViewModel,
    permLauncher: ActivityResultLauncher<Intent>,
) {
    val ui by vm.ui.collectAsState()
    val vpnState  = ui.vpnState
    val isConnected  = vpnState == VpnState.CONNECTED
    val isConnecting = vpnState == VpnState.CONNECTING || vpnState == VpnState.DISCONNECTING

    // Pulsing animation for connecting state
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 1f, targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ), label = "pulse_scale"
    )

    val buttonScale = if (isConnecting) pulse else 1f

    val statusColor = when (vpnState) {
        VpnState.CONNECTED    -> MaterialTheme.colorScheme.primary
        VpnState.CONNECTING,
        VpnState.DISCONNECTING-> MaterialTheme.colorScheme.tertiary
        else                  -> MaterialTheme.colorScheme.outline
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {

        // ── Header ────────────────────────────────────────────────────────────
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text  = "palazikVPN",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text  = ui.activeProfile?.name ?: "No profile selected",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }

        // ── Big connect button ────────────────────────────────────────────────
        Box(contentAlignment = Alignment.Center) {
            // Glow ring
            if (isConnected) {
                Box(
                    Modifier
                        .size(220.dp)
                        .background(
                            Brush.radialGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                                    Color.Transparent,
                                )
                            ), CircleShape
                        )
                )
            }
            Button(
                onClick = { vm.toggleVpn(permLauncher) },
                modifier = Modifier
                    .size(160.dp)
                    .scale(buttonScale),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isConnected)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.surfaceVariant,
                ),
                elevation = ButtonDefaults.buttonElevation(
                    defaultElevation = if (isConnected) 12.dp else 4.dp,
                ),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = if (isConnected) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = if (isConnected)
                            MaterialTheme.colorScheme.onPrimary
                        else
                            MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = when (vpnState) {
                            VpnState.CONNECTED     -> "Disconnect"
                            VpnState.CONNECTING    -> "Connecting"
                            VpnState.DISCONNECTING -> "Stopping"
                            else                   -> "Connect"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isConnected)
                            MaterialTheme.colorScheme.onPrimary
                        else
                            MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        // ── Status badge ──────────────────────────────────────────────────────
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = statusColor.copy(alpha = 0.12f),
            modifier = Modifier.padding(vertical = 8.dp),
        ) {
            Row(
                Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(statusColor, CircleShape)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text  = vpnState.name.replace('_', ' '),
                    style = MaterialTheme.typography.labelLarge,
                    color = statusColor,
                )
            }
        }

        // ── Traffic stats ─────────────────────────────────────────────────────
        AnimatedVisibility(visible = isConnected) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                TrafficCard("↓ Down", ui.bytesIn)
                TrafficCard("↑ Up",   ui.bytesOut)
            }
        }

        // ── Quick ping ────────────────────────────────────────────────────────
        ui.activeProfile?.let { profile ->
            OutlinedButton(
                onClick = { vm.pingProfile(profile) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.Speed, null)
                Spacer(Modifier.width(8.dp))
                Text("Ping ${profile.name}")
                if (profile.latencyMs >= 0) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${profile.latencyMs}ms",
                        color = latencyColor(profile.latencyMs),
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun TrafficCard(label: String, bytes: Long) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.width(140.dp),
    ) {
        Column(
            Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            Spacer(Modifier.height(4.dp))
            Text(formatBytes(bytes), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun latencyColor(ms: Long): Color = when {
    ms < 150  -> Color(0xFF4CAF50)
    ms < 400  -> Color(0xFFFFC107)
    else      -> Color(0xFFF44336)
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
