package fuck.system.airtools.device

import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/** Blocking UDP client for the embedded /bin/airtools service. */
class AirtoolsUdpClient(
    private val host: String = AirtoolsDevice.DEFAULT_HOST,
    private val port: Int = AirtoolsDevice.DEFAULT_PORT,
    private val timeoutMillis: Int = AirtoolsDevice.DEFAULT_TIMEOUT_MILLIS
) : Closeable
{
    private val address: InetAddress by lazy { InetAddress.getByName(host) }

    fun request(command: String): AirtoolsResponse
    {
        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMillis

            val requestBytes = command.toByteArray(Charsets.US_ASCII)
            val requestPacket = DatagramPacket(
                requestBytes,
                requestBytes.size,
                address,
                port
            )
            socket.send(requestPacket)

            val responseBytes = ByteArray(MAX_RESPONSE_BYTES)
            val responsePacket = DatagramPacket(responseBytes, responseBytes.size)

            try
            {
                socket.receive(responsePacket)
            }
            catch (error: SocketTimeoutException)
            {
                error("airtools timeout for $command at $host:$port")
            }

            return AirtoolsResponse(
                command = command,
                text = String(
                    responsePacket.data,
                    responsePacket.offset,
                    responsePacket.length,
                    Charsets.UTF_8
                )
            )
        }
    }

    override fun close()
    {
    }

    private companion object
    {
        const val MAX_RESPONSE_BYTES = 8192
    }
}
