package fuck.system.airtools.device

import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket

/** Blocking TCP request/response client for the embedded /bin/airtools service. */
class AirtoolsTcpClient(
    private val host: String = AirtoolsDevice.DEFAULT_HOST,
    private val port: Int = AirtoolsDevice.DEFAULT_PORT,
    private val timeoutMillis: Int = AirtoolsDevice.DEFAULT_TIMEOUT_MILLIS
)
{
    fun request(command: String): AirtoolsResponse
    {
        Socket().use { socket ->
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

    companion object
    {
        private const val MAX_RESPONSE_BYTES = 64 * 1024
    }
}