package fuck.system.airtools.device

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress

/** Blocking TCP request/response client for the embedded /bin/airtools service. */
class AirtoolsTcpClient(
    context: Context,
    private val host: String = AirtoolsDevice.DEFAULT_HOST,
    private val port: Int = AirtoolsDevice.DEFAULT_PORT,
    private val timeoutMillis: Int = AirtoolsDevice.DEFAULT_TIMEOUT_MILLIS
) : AirtoolsConnection
{
    private val connectivityManager =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    /** Sends one text command through the active non-VPN Wi-Fi network. */
    override fun request(command: String): AirtoolsResponse
    {
        val network = wifiNetwork()
        network.socketFactory.createSocket().use { socket ->
            socket.connect(InetSocketAddress(host, port), timeoutMillis)
            socket.soTimeout = timeoutMillis
            socket.getOutputStream().apply {
                write((command + "\n").toByteArray(Charsets.US_ASCII))
                flush()
            }

            val input = socket.getInputStream()
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(2048)
            while (output.size() < MAX_RESPONSE_BYTES)
            {
                val count = input.read(buffer, 0, minOf(buffer.size, MAX_RESPONSE_BYTES - output.size()))
                if (count < 0) break
                if (count > 0) output.write(buffer, 0, count)
            }
            val text = output.toString(Charsets.UTF_8.name())
            require(text.isNotEmpty()) { "Empty server response" }
            return AirtoolsResponse(command, text)
        }
    }

    /** Reads a text status line and streams the size-framed binary response body. */
    override fun download(command: String, output: OutputStream): Long
    {
        val network = wifiNetwork()
        network.socketFactory.createSocket().use { socket ->
            socket.connect(InetSocketAddress(host, port), timeoutMillis)
            socket.soTimeout = timeoutMillis
            socket.getOutputStream().apply {
                write((command + "\n").toByteArray(Charsets.US_ASCII))
                flush()
            }

            val input = socket.getInputStream()
            val header = ByteArrayOutputStream()
            while (header.size() < MAX_HEADER_BYTES)
            {
                val value = input.read()
                if (value < 0) break
                if (value == 10) break
                header.write(value)
            }
            val status = header.toString(Charsets.UTF_8.name()).trim()
            val expectedBytes = status.removePrefix("OK handshake bytes=")
                .takeIf { it != status }
                ?.toLongOrNull()
            require(status == "OK handshake" || expectedBytes != null) {
                status.ifEmpty { "Empty server response" }
            }

            val buffer = ByteArray(2048)
            var total = 0L
            if (expectedBytes != null)
            {
                while (total < expectedBytes)
                {
                    val count = input.read(buffer, 0, minOf(buffer.size, (expectedBytes - total).toInt()))
                    if (count < 0) break
                    if (count > 0) {
                        output.write(buffer, 0, count)
                        total += count.toLong()
                    }
                }
                require(total == expectedBytes) { "Handshake download was interrupted" }
            } else {
                while (true)
                {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) {
                        output.write(buffer, 0, count)
                        total += count.toLong()
                    }
                }
            }
            output.flush()
            require(total > PCAP_HEADER_BYTES) { "Handshake file is empty or incomplete" }
            return total
        }
    }

    private fun wifiNetwork(): Network
    {
        return connectivityManager.allNetworks.firstOrNull { network ->
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN).not() &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        } ?: throw IOException("Wi-Fi network unavailable")
    }

    companion object
    {
        private const val MAX_RESPONSE_BYTES = 64 * 1024
        private const val MAX_HEADER_BYTES = 128
        private const val PCAP_HEADER_BYTES = 24
    }
}
