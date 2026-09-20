package fuck.system.airtools.device

/** Target selection for the device-side airodump collector. */
sealed class AirodumpTarget
{
    object All : AirodumpTarget()
    data class Bssid(val value: String) : AirodumpTarget()
}

/** Collector mode requested from Android and applied by /bin/airtools. */
data class AirodumpConfig(
    val target: AirodumpTarget = AirodumpTarget.All,
    val channel: Int? = 11
)

/** Management AP configuration accepted by device-side airtools. */
data class WifiConfig(
    val ssid: String = AirtoolsDevice.MANAGEMENT_SSID,
    val password: String = AirtoolsDevice.DEFAULT_WIFI_PASSWORD
)

object AirtoolsWifiValidator
{
    private val alnumRegex = Regex("^[A-Za-z0-9]+$")

    fun validateSsid(ssid: String)
    {
        require(ssid.length in 1..32) { "SSID must be 1..32 characters" }
        require(alnumRegex.matches(ssid)) { "SSID must contain only A-Z, a-z and 0-9" }
    }

    fun validatePassword(password: String)
    {
        require(password.length in 8..63) { "Wi-Fi password must be 8..63 characters" }
        require(alnumRegex.matches(password)) { "Wi-Fi password must contain only A-Z, a-z and 0-9" }
    }

    fun validate(config: WifiConfig)
    {
        validateSsid(config.ssid)
        validatePassword(config.password)
    }
}

/** Raw command result returned by the UDP control service. */
data class AirtoolsResponse(
    val command: String,
    val text: String
)
{
    val ok: Boolean get() = text.startsWith("OK")
}

/** One line from /tmp/airhs/index.txt. */
data class HandshakeIndexEntry(
    val bssid: String,
    val storedTick: Long,
    val frameCount: Int,
    val file: String,
    val essid: String
)

/** Modes exposed by the device-side aireplay helper. */
sealed class AireplayRequest
{
    data class Test(val count: Int = 1) : AireplayRequest()
    data class Probe(val count: Int = 1, val ssid: String? = null) : AireplayRequest()
    data class Deauth(val count: Int, val bssid: String, val station: String? = null) : AireplayRequest()
}

object AirtoolsProtocol
{
    fun parseHandshakeIndex(text: String): List<HandshakeIndexEntry>
    {
        return text.lineSequence()
            .filter { line -> line.isNotBlank() && !line.startsWith("OK ") && !line.startsWith("#") }
            .mapNotNull { line -> parseHandshakeLine(line) }
            .toList()
    }

    private fun parseHandshakeLine(line: String): HandshakeIndexEntry?
    {
        val parts = line.split(',', limit = 5)
        if (parts.size < 5)
        {
            return null
        }

        return HandshakeIndexEntry(
            bssid = parts[0],
            storedTick = parts[1].toLongOrNull() ?: return null,
            frameCount = parts[2].toIntOrNull() ?: return null,
            file = parts[3],
            essid = parts[4]
        )
    }
}
