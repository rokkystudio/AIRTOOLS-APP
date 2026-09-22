package fuck.system.airtools.device

sealed class AirodumpTarget
{
    object All : AirodumpTarget()
    data class Bssid(val value: String) : AirodumpTarget()
}

data class AirodumpConfig(val target: AirodumpTarget, val channel: Int)

data class AirtoolsStatus(
    val running: Boolean,
    val scanningNetworks: Boolean,
    val pid: Int,
    val target: AirodumpTarget,
    val channel: Int?,
    val statePath: String
)

data class WifiNetwork(
    val bssid: String,
    val channel: Int,
    val beacons: Long,
    val probes: Long,
    val dataFrames: Long,
    val essid: String
)

data class AirtoolsResponse(val command: String, val text: String)
{
    val ok: Boolean get() = text.startsWith("OK")
}

data class HandshakeIndexEntry(
    val bssid: String,
    val storedTick: Long,
    val frameCount: Int,
    val file: String,
    val essid: String
)

sealed class AireplayRequest
{
    data class Test(val count: Int = 1) : AireplayRequest()
    data class Probe(val count: Int = 1, val ssid: String? = null) : AireplayRequest()
    data class Deauth(val count: Int, val bssid: String, val station: String? = null) : AireplayRequest()
}

object AirtoolsProtocol
{
    private val macRegex = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")

    fun parseStatus(text: String): AirtoolsStatus
    {
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        require(lines.size == 2) { "Unexpected /status response" }
        val first = lines[0]
        require(first.startsWith("OK airtools=1 pid=")) { "Malformed /status response" }
        val pid = first.substringAfter("pid=").substringBefore(' ').toIntOrNull()
            ?: throw IllegalArgumentException("Invalid pid")
        val scanning = first.substringAfter(" scan=").substringBefore(' ').toIntOrNull()
            ?: throw IllegalArgumentException("Invalid scan state")
        require(scanning == 0 || scanning == 1) { "Invalid scan state" }
        val query = first.substringAfter(" ?", missingDelimiterValue = "")
        require(query.isNotEmpty()) { "Missing capture state" }
        val values = query.split('&').associate { part ->
            val pieces = part.split('=', limit = 2)
            require(pieces.size == 2) { "Malformed capture state" }
            pieces[0] to pieces[1]
        }
        val channel = values["channel"]?.toIntOrNull()
        require(channel == null || channel in 1..14) { "Invalid channel" }
        val target = when (values["mode"])
        {
            "all" -> AirodumpTarget.All
            "bssid" -> {
                val bssid = values["bssid"] ?: throw IllegalArgumentException("Missing BSSID")
                require(macRegex.matches(bssid)) { "Invalid BSSID" }
                AirodumpTarget.Bssid(bssid)
            }
            else -> throw IllegalArgumentException("Invalid capture mode")
        }
        require(lines[1].startsWith("state_path=") && lines[1].length > 11) { "Missing state path" }
        return AirtoolsStatus(pid > 0, scanning == 1, pid, target, channel, lines[1].substringAfter("state_path="))
    }

    fun parseNetworks(text: String): List<WifiNetwork>
    {
        return text.lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("OK ") && !it.startsWith("#") && !it.startsWith("ERR ") }
            .mapNotNull(::parseNetworkLine)
            .sortedWith(compareByDescending<WifiNetwork> { it.beacons + it.probes + it.dataFrames }.thenBy { it.essid })
            .toList()
    }

    private fun parseNetworkLine(line: String): WifiNetwork?
    {
        val parts = line.split(',', limit = 6)
        if (parts.size != 6 || !macRegex.matches(parts[0])) return null
        val channel = parts[1].toIntOrNull() ?: return null
        if (channel !in 1..14) return null
        return WifiNetwork(
            bssid = parts[0],
            channel = channel,
            beacons = parts[2].toLongOrNull() ?: return null,
            probes = parts[3].toLongOrNull() ?: return null,
            dataFrames = parts[4].toLongOrNull() ?: return null,
            essid = parts[5]
        )
    }

    fun parseHandshakeIndex(text: String): List<HandshakeIndexEntry>
    {
        return text.lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("OK ") && !it.startsWith("#") }
            .mapNotNull { line ->
                val parts = line.split(',', limit = 5)
                if (parts.size < 5) null else HandshakeIndexEntry(
                    parts[0],
                    parts[1].toLongOrNull() ?: return@mapNotNull null,
                    parts[2].toIntOrNull() ?: return@mapNotNull null,
                    parts[3],
                    parts[4]
                )
            }
            .toList()
    }
}