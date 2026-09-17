package com.jarvis.ai.ui.components

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jarvis.ai.data.model.LatencyInfo
import com.jarvis.ai.ui.theme.CyanGlow
import com.jarvis.ai.ui.theme.ErrorRed

private data class BatteryState(val percent: Int?, val charging: Boolean)

@Composable
fun TelemetryRow(
    latency: LatencyInfo,
    providerLabel: String,
    modelLabel: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var battery by remember { mutableStateOf(readBattery(context)) }
    var online by remember { mutableStateOf(true) }

    DisposableEffect(context) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                battery = readBattery(ctx ?: return)
            }
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val registered = runCatching {
            context.registerReceiver(receiver, filter)
        }.isSuccess

        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) { online = true }
            override fun onLost(network: android.net.Network) { online = false }
        }
        val callbackRegistered = manager != null && runCatching {
            manager.registerDefaultNetworkCallback(callback)
        }.isSuccess
        if (!callbackRegistered) {
            online = isDefaultNetworkOnline(manager)
        }

        onDispose {
            if (registered) runCatching { context.unregisterReceiver(receiver) }
            if (callbackRegistered) runCatching { manager?.unregisterNetworkCallback(callback) }
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TelemetryChip(
            text = if (online) "UPLINK" else "OFFLINE",
            dotColor = if (online) CyanGlow else ErrorRed
        )
        TelemetryChip(text = "$providerLabel · ${modelLabel.truncate(18)}")
        latency.firstTokenMs?.let { first ->
            TelemetryChip(
                text = if (latency.totalMs != null) "T+${formatMs(first)} · ${formatMs(latency.totalMs!!)}"
                else "${formatMs(first)}…"
            )
        }
        Box(Modifier.weight(1f))
        battery.percent?.let { percent ->
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(
                                when {
                                    battery.charging -> Color(0xFF69F0AE)
                                    percent <= 15 -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                CircleShape
                            )
                    )
                    Text(
                        text = buildString {
                            append(percent).append('%')
                            if (battery.charging) append(" CHG")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun TelemetryChip(text: String, dotColor: Color? = null) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            if (dotColor != null) {
                Box(
                    Modifier
                        .size(7.dp)
                        .background(dotColor, CircleShape)
                )
            }
            Text(
                text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun readBattery(context: Context): BatteryState {
    return runCatching {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return BatteryState(null, false)
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val percent =
            if (level >= 0 && scale > 0) (level * 100) / scale else null
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        BatteryState(percent, charging)
    }.getOrDefault(BatteryState(null, false))
}

private fun isDefaultNetworkOnline(manager: android.net.ConnectivityManager?): Boolean {
    if (manager == null) return true
    return runCatching {
        val network = manager.activeNetwork ?: return true
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(true)
}

private fun formatMs(ms: Long): String =
    if (ms >= 10_000) "${"%.1f".format(ms / 1000f)}s" else "${ms}ms"

private fun String.truncate(max: Int): String =
    if (length <= max) this else take(max - 1) + "…"
