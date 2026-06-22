package com.orbit.remote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orbit.remote.domain.model.AgentState
import com.orbit.remote.domain.model.ConnectionState

private val OrbitColors = darkColorScheme(
    primary = Color(0xFF5B8CFF),
    secondary = Color(0xFF7B5BFF),
    background = Color(0xFF0A0D14),
    surface = Color(0xFF141A26),
    onBackground = Color(0xFFE8EDF6),
    onSurface = Color(0xFFE8EDF6)
)

@Composable
fun AgentScreen(
    state: AgentState,
    onEnableRemote: () -> Unit,
    onStop: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onIgnoreBattery: () -> Unit,
    onOpenAutostart: () -> Unit
) {
    MaterialTheme(colorScheme = OrbitColors) {
        Surface(modifier = Modifier.fillMaxSize(), color = OrbitColors.background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Spacer(Modifier.size(8.dp))
                Text(
                    "Orbit Remote",
                    color = OrbitColors.onBackground,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold
                )
                StatusRow(state.connection)

                IdentityCard(state)

                PermissionCard(
                    title = "Screen capture",
                    granted = state.mediaProjectionGranted,
                    actionLabel = if (state.connection == ConnectionState.IN_SESSION) "Active" else "Enable remote control",
                    onAction = onEnableRemote
                )
                PermissionCard(
                    title = "Accessibility (remote control)",
                    granted = state.accessibilityEnabled,
                    actionLabel = "Open settings",
                    onAction = onOpenAccessibility
                )
                PermissionCard(
                    title = "Ignore battery optimization",
                    granted = state.batteryOptimizationIgnored,
                    actionLabel = "Allow",
                    onAction = onIgnoreBattery
                )

                OutlinedButton(onClick = onOpenAutostart, modifier = Modifier.fillMaxWidth()) {
                    Text("Open manufacturer autostart settings")
                }

                Spacer(Modifier.size(8.dp))
                Button(
                    onClick = onStop,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Stop agent") }

                state.errorMessage?.let {
                    Text("Error: $it", color = Color(0xFFFF6B6B))
                }
            }
        }
    }
}

@Composable
private fun StatusRow(connection: ConnectionState) {
    val (label, color) = when (connection) {
        ConnectionState.DISCONNECTED -> "Disconnected" to Color(0xFF9AA7BD)
        ConnectionState.CONNECTING -> "Connecting…" to Color(0xFFFFC857)
        ConnectionState.REGISTERED -> "Online — ready" to Color(0xFF4ADE80)
        ConnectionState.IN_SESSION -> "Remote session active" to Color(0xFF5B8CFF)
        ConnectionState.ERROR -> "Error" to Color(0xFFFF6B6B)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(modifier = Modifier.size(10.dp).clip(CircleShape), color = color) {}
        Text(label, color = OrbitColors.onBackground, fontSize = 16.sp)
    }
}

@Composable
private fun IdentityCard(state: AgentState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = OrbitColors.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Device ID", color = Color(0xFF9AA7BD), fontSize = 13.sp)
            Text(
                state.identity?.deviceId ?: "—",
                color = OrbitColors.onSurface,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.size(4.dp))
            Text("Connection code", color = Color(0xFF9AA7BD), fontSize = 13.sp)
            Text(
                state.identity?.code ?: "—",
                color = OrbitColors.primary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun PermissionCard(
    title: String,
    granted: Boolean,
    actionLabel: String,
    onAction: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = OrbitColors.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = OrbitColors.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(
                    if (granted) "Granted" else "Not granted",
                    color = if (granted) Color(0xFF4ADE80) else Color(0xFF9AA7BD),
                    fontSize = 13.sp
                )
            }
            if (!granted || actionLabel == "Active") {
                Button(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}
