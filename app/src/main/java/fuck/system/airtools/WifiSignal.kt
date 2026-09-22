package fuck.system.airtools

import android.os.SystemClock
import fuck.system.airtools.device.WifiNetwork
import kotlin.math.roundToInt

enum class WifiSignalLevel(val drawableRes: Int)
{
    EXCELLENT(R.drawable.ic_signal_excellent),
    VERY_GOOD(R.drawable.ic_signal_very_good),
    GOOD(R.drawable.ic_signal_good),
    FAIR(R.drawable.ic_signal_fair),
    POOR(R.drawable.ic_signal_poor),
    OFFLINE(R.drawable.ic_signal_offline);

    companion object
    {
        fun from(network: WifiNetwork): WifiSignalLevel
        {
            if (!network.online) return OFFLINE
            return when (network.signalDbm)
            {
                null -> POOR
                in -50..-1 -> EXCELLENT
                in -60..-51 -> VERY_GOOD
                in -67..-61 -> GOOD
                in -75..-68 -> FAIR
                else -> POOR
            }
        }
    }
}

class WifiSignalTracker
{
    private data class State(
        var network: WifiNetwork,
        val samples: ArrayDeque<Int> = ArrayDeque(),
        var lastActivityCount: Long = -1,
        var lastActivityAt: Long = 0
    )

    private val states = linkedMapOf<String, State>()

    @Synchronized
    fun update(networks: List<WifiNetwork>, now: Long = SystemClock.elapsedRealtime()): List<WifiNetwork>
    {
        val present = HashSet<String>()
        networks.forEach { raw ->
            val key = raw.bssid.uppercase()
            present += key
            val activityCount = raw.beacons + raw.probes + raw.dataFrames
            val state = states.getOrPut(key) {
                State(raw, lastActivityCount = activityCount, lastActivityAt = now)
            }
            val activityChanged = activityCount != state.lastActivityCount
            if (activityChanged || state.samples.isEmpty()) {
                raw.signalDbm?.let { signal ->
                    state.samples.addLast(signal)
                    while (state.samples.size > SAMPLE_WINDOW) state.samples.removeFirst()
                }
            }
            if (activityChanged || state.lastActivityAt == 0L) state.lastActivityAt = now
            state.lastActivityCount = activityCount
            val averagedSignal = state.samples.takeIf { it.isNotEmpty() }?.average()?.roundToInt()
            state.network = raw.copy(signalDbm = averagedSignal, online = now - state.lastActivityAt < OFFLINE_AFTER_MILLIS)
        }

        states.forEach { (key, state) ->
            if (key !in present && now - state.lastActivityAt >= OFFLINE_AFTER_MILLIS) {
                state.network = state.network.copy(online = false)
            }
        }

        return states.values
            .map { state ->
                if (now - state.lastActivityAt >= OFFLINE_AFTER_MILLIS) state.network.copy(online = false)
                else state.network
            }
            .sortedWith(
                compareByDescending<WifiNetwork> { it.online }
                    .thenByDescending { it.signalDbm ?: Int.MIN_VALUE }
                    .thenBy { it.essid.lowercase() }
                    .thenBy { it.bssid }
            )
    }

    @Synchronized
    fun reset()
    {
        states.clear()
    }

    companion object
    {
        private const val SAMPLE_WINDOW = 5
        private const val OFFLINE_AFTER_MILLIS = 12_000L
    }
}

fun signalPercent(dbm: Int): Int = ((dbm + 100).coerceIn(0, 70) * 100 / 70)