package fuck.system.airtools.device

import android.content.Context

import java.net.URLEncoder

class AirtoolsRepository(context: Context)
{
    private val client = AirtoolsTcpClient(context.applicationContext)
    fun status(): AirtoolsStatus
    {
        val response = client.request("/status")
        require(response.ok) { response.text.trim() }
        return AirtoolsProtocol.parseStatus(response.text)
    }

    fun selectNetwork(network: WifiNetwork): AirtoolsResponse
    {
        val response = client.request(
            "/set?mode=bssid&bssid=${query(network.bssid)}&channel=${network.channel}"
        )
        require(response.ok) { response.text.trim() }
        return response
    }

    fun start(): AirtoolsResponse = checked("/start")
    fun stop(): AirtoolsResponse = checked("/stop")
    fun startNetworkScan(): AirtoolsResponse = checked("/scan/start")
    fun stopNetworkScan(): AirtoolsResponse = checked("/scan/stop")

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