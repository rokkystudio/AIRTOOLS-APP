package fuck.system.airtools.device

import android.content.Context
import java.io.OutputStream
import java.net.URLEncoder

/** Provides typed access to the AIRTOOLS TCP API, including handshake PCAP downloads. */
class AirtoolsRepository(context: Context)
{
    private val client = AirtoolsTcpClient(context.applicationContext)
    private val handshakeFilePattern = Regex("^[0-9A-Fa-f]{12}\\.pcap$")

    fun status(): AirtoolsStatus
    {
        val response = client.request("/status")
        require(response.ok) { response.text.trim() }
        return AirtoolsProtocol.parseStatus(response.text)
    }

    fun selectNetworkAndCapture(network: WifiNetwork): AirtoolsResponse = checked(
        "/select?bssid=${query(network.bssid)}&channel=${network.channel}"
    )

    fun start(): AirtoolsResponse = checked("/start")
    fun startNetworkScan(): AirtoolsResponse = checked("/scan/start")

    fun networks(): List<WifiNetwork>
    {
        val response = client.request("/networks")
        require(response.ok) { response.text.trim() }
        return AirtoolsProtocol.parseNetworks(response.text)
    }

    fun handshakes(): Pair<AirtoolsResponse, List<HandshakeIndexEntry>>
    {
        val response = checked("/handshakes")
        return response to AirtoolsProtocol.parseHandshakeIndex(response.text)
    }

    /** Streams the PCAP file indexed for the selected handshake entry into the caller's output. */
    fun downloadHandshake(file: String, output: OutputStream): Long
    {
        require(handshakeFilePattern.matches(file)) { "Invalid handshake file name" }
        return client.download("/handshake/download?file=${query(file)}", output)
    }

    /** Runs five deauth replay packets against the device's current capture BSSID. */
    fun replay(): AirtoolsResponse = checked("/replay")

    fun aireplayTest(count: Int = 1): AirtoolsResponse =
        checked("/aireplay?mode=test&count=${count.coerceIn(1, 128)}")

    private fun checked(command: String): AirtoolsResponse
    {
        val response = client.request(command)
        require(response.ok) { response.text.trim() }
        return response
    }

    private fun query(value: String): String = URLEncoder.encode(value, "UTF-8")
}