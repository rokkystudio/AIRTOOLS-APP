package fuck.system.airtools.device

import java.io.OutputStream

/** Sends AIRTOOLS commands through one device transport and returns complete responses. */
interface AirtoolsConnection
{
    /** Sends a text command and returns the complete text response. */
    fun request(command: String): AirtoolsResponse

    /** Streams a binary command response into the supplied output. */
    fun download(command: String, output: OutputStream): Long
    {
        throw UnsupportedOperationException("Binary download is not supported by this transport")
    }
}
