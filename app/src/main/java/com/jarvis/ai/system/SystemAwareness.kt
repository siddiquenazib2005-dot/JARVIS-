package com.jarvis.ai.system

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs

/** Point-in-time device snapshot used for intelligent, local-first decisions. */
data class SystemSnapshot(
    val batteryPercent: Int?,
    val charging: Boolean?,
    val online: Boolean?,
    val lowStorage: Boolean?,
    val memoryPressurePercent: Int?
)

class SystemAwareness(private val context: Context) {

    fun snapshot(): SystemSnapshot = SystemSnapshot(
        batteryPercent = battery()?.first,
        charging = battery()?.second,
        online = isOnline(),
        lowStorage = lowStorage(),
        memoryPressurePercent = memoryPressure()
    )

    /**
     * Local-first policy: prefer cheap/local execution when the device is
     * offline or critically low on battery.
     */
    fun prefersLocal(snap: SystemSnapshot): Boolean {
        val criticalBattery = snap.batteryPercent?.let { it < 15 && snap.charging == false } == true
        return criticalBattery || snap.online == false
    }

    private fun battery(): Pair<Int, Boolean>? = runCatching {
        val intent = context.registerReceiver(
            null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
        ) ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val percent = if (level >= 0 && scale > 0) (level * 100) / scale else return null
        percent to (status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL)
    }.getOrNull()

    private fun isOnline(): Boolean? {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return null
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            null
        }
    }

    private fun lowStorage(): Boolean = runCatching {
        val stat = StatFs(Environment.getDataDirectory().path)
        val usableMb = stat.availableBytes / (1024 * 1024)
        usableMb < 500
    }.getOrDefault(false)

    private fun memoryPressure(): Int? = runCatching {
        val runtime = Runtime.getRuntime()
        val used = runtime.totalMemory() - runtime.freeMemory()
        ((used.toDouble() / runtime.maxMemory()) * 100).toInt()
    }.getOrNull()
}
