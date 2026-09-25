package fuck.system.airtools.device

import android.content.Context
import java.io.OutputStream

/** Chooses BLE AIRTOOLS-ESP32 when available and falls back to the legacy TCP device. */
class AirtoolsAutoClient(context: Context) : AirtoolsConnection
{
    private val bleClient = AirtoolsBleClient(context.applicationContext)
    private val tcpClient = AirtoolsTcpClient(context.applicationContext)
    @Volatile private var preferredClient: AirtoolsConnection? = null

    /** Sends a command through the last working transport, BLE, or finally legacy TCP. */
    override fun request(command: String): AirtoolsResponse
    {
        preferredClient?.let { client ->
            runCatching { return client.request(command) }
                .onFailure { preferredClient = null }
        }

        runCatching {
            val response = bleClient.request(command)
            preferredClient = bleClient
            return response
        }

        val response = tcpClient.request(command)
        preferredClient = tcpClient
        return response
    }

    /** Downloads binary data through BLE AIRTOOLS-ESP32 or the legacy TCP device. */
    override fun download(command: String, output: OutputStream): Long
    {
        when (preferredClient)
        {
            bleClient -> runCatching {
                val bytes = bleClient.download(command, output)
                preferredClient = bleClient
                return bytes
            }.onFailure { preferredClient = null }

            tcpClient -> {
                val bytes = tcpClient.download(command, output)
                preferredClient = tcpClient
                return bytes
            }
        }

        runCatching {
            val bytes = bleClient.download(command, output)
            preferredClient = bleClient
            return bytes
        }

        val bytes = tcpClient.download(command, output)
        preferredClient = tcpClient
        return bytes
    }
}
