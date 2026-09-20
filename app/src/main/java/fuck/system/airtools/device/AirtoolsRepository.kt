package fuck.system.airtools.device

import java.net.URLEncoder

/** High-level Android-side facade for configuring capture and launching actions. */
class AirtoolsRepository(
    private val client: AirtoolsUdpClient = AirtoolsUdpClient()
)
{
    fun status(): AirtoolsResponse = client.request("/status")

    fun wifiStatus(): AirtoolsResponse = client.request("/wifi/status")

    fun setWifi(config: WifiConfig): AirtoolsResponse
    {
        AirtoolsWifiValidator.validate(config)
        return client.request(buildString {
            append("/wifi/set?ssid=")
            append(query(config.ssid))
            append("&pass=")
            append(query(config.password))
        })
    }

    fun setWifi(ssid: String, password: String): AirtoolsResponse
    {
        return setWifi(WifiConfig(ssid = ssid, password = password))
    }

    fun start(): AirtoolsResponse = client.request("/start")

    fun stop(): AirtoolsResponse = client.request("/stop")

    fun setAirodump(config: AirodumpConfig): AirtoolsResponse
    {
        return when (val target = config.target)
        {
            AirodumpTarget.All -> setAll(config.channel)
            is AirodumpTarget.Bssid -> setBssid(target.value, config.channel)
        }
    }

    fun setAll(channel: Int? = 11): AirtoolsResponse
    {
        return client.request(buildString {
            append("/set?mode=all")
            appendChannel(channel)
        })
    }

    fun setBssid(bssid: String, channel: Int? = 11): AirtoolsResponse
    {
        return client.request(buildString {
            append("/set?mode=bssid&bssid=")
            append(query(bssid))
            appendChannel(channel)
        })
    }

    fun handshakes(): Pair<AirtoolsResponse, List<HandshakeIndexEntry>>
    {
        val response = client.request("/handshakes")
        return response to AirtoolsProtocol.parseHandshakeIndex(response.text)
    }

    fun aireplay(request: AireplayRequest): AirtoolsResponse
    {
        return when (request)
        {
            is AireplayRequest.Test -> aireplayTest(request.count)
            is AireplayRequest.Probe -> aireplayProbe(request.count, request.ssid)
            is AireplayRequest.Deauth -> aireplayDeauth(
                count = request.count,
                bssid = request.bssid,
                station = request.station
            )
        }
    }

    fun aireplayTest(count: Int = 1): AirtoolsResponse
    {
        return client.request("/aireplay?mode=test&count=${safeCount(count)}")
    }

    fun aireplayProbe(count: Int = 1, ssid: String? = null): AirtoolsResponse
    {
        return client.request(buildString {
            append("/aireplay?mode=probe&count=")
            append(safeCount(count))
            if (!ssid.isNullOrEmpty())
            {
                append("&ssid=")
                append(query(ssid))
            }
        })
    }

    fun aireplayDeauth(count: Int, bssid: String, station: String? = null): AirtoolsResponse
    {
        return client.request(buildString {
            append("/aireplay?mode=-0&count=")
            append(safeCount(count))
            append("&bssid=")
            append(query(bssid))
            if (!station.isNullOrEmpty())
            {
                append("&station=")
                append(query(station))
            }
        })
    }

    private fun StringBuilder.appendChannel(channel: Int?)
    {
        if (channel != null)
        {
            append("&channel=")
            append(channel.coerceIn(1, 14))
        }
    }

    private fun safeCount(count: Int): Int = count.coerceIn(1, 128)

    private fun query(value: String): String = URLEncoder.encode(value, "UTF-8")
}
