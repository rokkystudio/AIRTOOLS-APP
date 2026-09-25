package fuck.system.airtools.device

sealed class AirodumpTarget
{
    object All : AirodumpTarget()
    data class Bssid(val value: String) : AirodumpTarget()
}

data class AirodumpConfig(val target: AirodumpTarget, val channel: Int)

enum class DeviceMode { IDLE, SCAN, CAPTURE }

data class AirtoolsStatus(
    val mode: DeviceMode,
    val target: AirodumpTarget,
    val channel: Int?
)

data class WifiNetwork(
    val bssid: String,
    val channel: Int,
    val signalDbm: Int?,
    val beacons: Long,
    val probes: Long,
    val dataFrames: Long,
    val essid: String,
    val online: Boolean = true
)

/** One client station observed exchanging data frames with an access point. */
data class WifiClient(
    val bssid: String,
    val station: String,
    val signalDbm: Int?,
    val frames: Long,
    val lastSeen: Long
)

data class AirtoolsResponse(val command: String, val text: String)
{
    val ok: Boolean get() = text.startsWith("OK")
}

/** One saved WPA handshake entry reported by the device index. */
data class HandshakeIndexEntry(
    val bssid: String,
    val storedTick: Long,
    val generation: Int = 1,
    val frameCount: Int,
    val file: String,
    val essid: String,
    val capturedAtMillis: Long? = null
)
{
    /** Safe file name used for the download endpoint and phone storage. */
    val fileName: String get() = file.substringAfterLast('/')
}

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
        require(lines.size >= 2) { "Unexpected /status response" }
        val first = lines[0]
        require(first.startsWith("OK airtools=1 ")) { "Malformed /status response" }

        return if (first.contains(" transport=ble ")) {
            parseEsp32Status(lines[1])
        } else {
            parseLegacyStatus(lines)
        }
    }

    private fun parseEsp32Status(line: String): AirtoolsStatus
    {
        val values = line.split(Regex("\\s+"))
            .filter { it.contains("=") }
            .associate { part ->
                val pieces = part.split("=", limit = 2)
                pieces[0] to pieces[1]
            }
        val mode = when (values["mode"])
        {
            "scan" -> DeviceMode.SCAN
            "capture" -> DeviceMode.CAPTURE
            "idle" -> DeviceMode.IDLE
            else -> throw IllegalArgumentException("Invalid device mode")
        }
        val channel = values["channel"]?.toIntOrNull()
        require(channel == null || channel in 1..14) { "Invalid channel" }
        val bssid = values["bssid"]
        val target = if (bssid != null) {
            require(macRegex.matches(bssid)) { "Invalid BSSID" }
            AirodumpTarget.Bssid(bssid)
        } else {
            AirodumpTarget.All
        }
        return AirtoolsStatus(mode, target, channel)
    }

    private fun parseLegacyStatus(lines: List<String>): AirtoolsStatus
    {
        require(lines.size == 2) { "Unexpected /status response" }
        val first = lines[0]
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
        val mode = when (first.substringAfter(" mode=", missingDelimiterValue = "").substringBefore(' '))
        {
            "scan" -> DeviceMode.SCAN
            "capture" -> DeviceMode.CAPTURE
            "idle" -> DeviceMode.IDLE
            "" -> if (scanning == 1) DeviceMode.SCAN else if (pid > 0 && target is AirodumpTarget.Bssid) DeviceMode.CAPTURE else DeviceMode.IDLE
            else -> throw IllegalArgumentException("Invalid device mode")
        }
        require(lines[1].startsWith("state_path=") && lines[1].length > 11) { "Missing state path" }
        return AirtoolsStatus(mode, target, channel)
    }

    fun parseNetworks(text: String): List<WifiNetwork>
    {
        return text.lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("OK ") && !it.startsWith("#") && !it.startsWith("ERR ") }
            .mapNotNull(::parseNetworkLine)
            .sortedWith(
                compareByDescending<WifiNetwork> { it.signalDbm ?: Int.MIN_VALUE }
                    .thenByDescending { it.beacons + it.probes + it.dataFrames }
                    .thenBy { it.essid }
            )
            .toList()
    }

    private fun parseNetworkLine(line: String): WifiNetwork?
    {
        val parts = line.split(',', limit = 7)
        if (parts.size != 7 || !macRegex.matches(parts[0])) return null
        val channel = parts[1].toIntOrNull() ?: return null
        if (channel !in 1..14) return null
        val rawSignal = parts[2].toIntOrNull() ?: return null
        val signal = rawSignal.takeIf { it in -127..-1 }
        return WifiNetwork(
            bssid = parts[0],
            channel = channel,
            signalDbm = signal,
            beacons = parts[3].toLongOrNull() ?: return null,
            probes = parts[4].toLongOrNull() ?: return null,
            dataFrames = parts[5].toLongOrNull() ?: return null,
            essid = parts[6]
        )
    }

    /** Parses client station rows from /clients. */
    fun parseClients(text: String): List<WifiClient>
    {
        return text.lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("OK ") && !it.startsWith("#") && !it.startsWith("ERR ") }
            .mapNotNull(::parseClientLine)
            .sortedWith(compareByDescending<WifiClient> { it.frames }.thenBy { it.station })
            .toList()
    }

    private fun parseClientLine(line: String): WifiClient?
    {
        val parts = line.split(',', limit = 5)
        if (parts.size != 5 || !macRegex.matches(parts[0]) || !macRegex.matches(parts[1])) return null
        val rawSignal = parts[2].toIntOrNull() ?: return null
        return WifiClient(
            bssid = parts[0],
            station = parts[1],
            signalDbm = rawSignal.takeIf { it in -127..-1 },
            frames = parts[3].toLongOrNull() ?: return null,
            lastSeen = parts[4].toLongOrNull() ?: return null
        )
    }

    /** Parses legacy, timestamped, and generation-aware handshake indexes. */
    fun parseHandshakeIndex(text: String): List<HandshakeIndexEntry>
    {
        return text.lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("OK ") && !it.startsWith("#") }
            .mapNotNull { line ->
                val parts = line.split(',', limit = 7)
                when
                {
                    parts.size >= 7 -> {
                        val capturedEpoch = parts[2].toLongOrNull() ?: return@mapNotNull null
                        HandshakeIndexEntry(
                            bssid = parts[0],
                            storedTick = parts[1].toLongOrNull() ?: return@mapNotNull null,
                            generation = parts[3].toIntOrNull()?.coerceAtLeast(1) ?: return@mapNotNull null,
                            frameCount = parts[4].toIntOrNull() ?: return@mapNotNull null,
                            file = parts[5].substringAfterLast('/'),
                            essid = parts[6],
                            capturedAtMillis = capturedEpoch.takeIf { it > 0L }?.times(1000L)
                        )
                    }
                    parts.size >= 6 -> {
                        val capturedEpoch = parts[2].toLongOrNull() ?: return@mapNotNull null
                        HandshakeIndexEntry(
                            bssid = parts[0],
                            storedTick = parts[1].toLongOrNull() ?: return@mapNotNull null,
                            frameCount = parts[3].toIntOrNull() ?: return@mapNotNull null,
                            file = parts[4].substringAfterLast('/'),
                            essid = parts[5],
                            capturedAtMillis = capturedEpoch.takeIf { it > 0L }?.times(1000L)
                        )
                    }
                    parts.size >= 5 -> HandshakeIndexEntry(
                        bssid = parts[0],
                        storedTick = parts[1].toLongOrNull() ?: return@mapNotNull null,
                        frameCount = parts[2].toIntOrNull() ?: return@mapNotNull null,
                        file = parts[3].substringAfterLast('/'),
                        essid = parts[4]
                    )
                    else -> null
                }
            }
            .toList()
    }
}
