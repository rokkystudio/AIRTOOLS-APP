package fuck.system.airtools

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteOrder

/**
 * Converts classic libpcap captures containing IEEE 802.11 traffic into Hashcat
 * mode 22000 EAPOL records.
 *
 * The parser accepts LINKTYPE_IEEE802_11 and LINKTYPE_IEEE802_11_RADIOTAP captures,
 * learns non-empty SSIDs from beacon, probe-response, association-request and
 * reassociation-request frames, and emits only M1+M2 pairs whose AP, client and
 * replay counter match exactly.
 */
class CapToHc22000Converter
{
    /**
     * Describes the completed conversion and the number of records that could not
     * be emitted because the capture contained no known SSID for their BSSID.
     */
    data class Result(
        val lines: List<String>,
        val packetCount: Int,
        val matchedPairCount: Int,
        val skippedWithoutSsidCount: Int
    )

    private data class SessionKey(
        val ap: String,
        val client: String,
        val replayCounter: String
    )

    private data class Message1(
        val anonce: ByteArray
    )

    private data class Message2(
        val mic: ByteArray,
        val eapol: ByteArray
    )

    private data class MacPair(
        val ap: String,
        val client: String
    )

    /**
     * Reads one classic pcap stream to EOF and returns hc22000 WPA*02 records for
     * every exact M1+M2 replay-counter match with a known non-empty SSID.
     *
     * @throws IOException when the stream is truncated or uses an unsupported
     * capture container or link type.
     */
    fun convert(input: InputStream): Result {
        val globalHeader = readRequired(input, PCAP_GLOBAL_HEADER_SIZE)
        val byteOrder = detectByteOrder(globalHeader)
        val linkType = readUInt32(globalHeader, 20, byteOrder).toInt()

        if (linkType != LINKTYPE_IEEE802_11 && linkType != LINKTYPE_IEEE802_11_RADIOTAP) {
            throw IOException("Неподдерживаемый pcap linktype: $linkType.")
        }

        val ssidsByBssid = LinkedHashMap<String, ByteArray>()
        val message1BySession = LinkedHashMap<SessionKey, Message1>()
        val message2BySession = LinkedHashMap<SessionKey, Message2>()

        var packetCount = 0

        while (true) {
            val packetHeader = readOptional(input, PCAP_PACKET_HEADER_SIZE) ?: break
            val capturedLength = readUInt32(packetHeader, 8, byteOrder)

            if (capturedLength > MAX_PACKET_SIZE) {
                throw IOException("Слишком большой pcap-пакет: $capturedLength байт.")
            }

            val packet = readRequired(input, capturedLength.toInt())
            packetCount++

            val frame = unwrapLinkLayer(packet, linkType) ?: continue

            parseManagementFrame(frame, ssidsByBssid)
            parseEapolFrame(frame, message1BySession, message2BySession)
        }

        val lines = LinkedHashSet<String>()
        var matchedPairCount = 0
        var skippedWithoutSsidCount = 0

        message2BySession.forEach { (session, message2) ->
            val message1 = message1BySession[session] ?: return@forEach
            matchedPairCount++

            val ssid = ssidsByBssid[session.ap]

            if (ssid == null || ssid.isEmpty()) {
                skippedWithoutSsidCount++
                return@forEach
            }

            lines += buildHc22000Line(
                session = session,
                ssid = ssid,
                message1 = message1,
                message2 = message2
            )
        }

        return Result(
            lines = lines.toList(),
            packetCount = packetCount,
            matchedPairCount = matchedPairCount,
            skippedWithoutSsidCount = skippedWithoutSsidCount
        )
    }

    /**
     * Removes the capture-specific link header and returns a raw IEEE 802.11
     * frame. Malformed Radiotap packets are skipped.
     */
    private fun unwrapLinkLayer(packet: ByteArray, linkType: Int): ByteArray? {
        if (linkType == LINKTYPE_IEEE802_11) {
            return packet
        }

        if (packet.size < RADIOTAP_MIN_HEADER_SIZE) {
            return null
        }

        val radiotapLength = readUInt16LittleEndian(packet, 2)

        if (radiotapLength < RADIOTAP_MIN_HEADER_SIZE || radiotapLength > packet.size) {
            return null
        }

        return packet.copyOfRange(radiotapLength, packet.size)
    }

    /**
     * Extracts non-empty SSID information elements from management frames whose
     * address 3 field identifies a concrete BSSID.
     */
    private fun parseManagementFrame(
        frame: ByteArray,
        ssidsByBssid: MutableMap<String, ByteArray>
    ) {
        if (frame.size < IEEE80211_BASE_HEADER_SIZE) {
            return
        }

        val frameControl = readUInt16LittleEndian(frame, 0)
        val type = (frameControl ushr 2) and 0x03

        if (type != FRAME_TYPE_MANAGEMENT) {
            return
        }

        val subtype = (frameControl ushr 4) and 0x0f
        val informationElementsOffset = when (subtype) {
            SUBTYPE_ASSOCIATION_REQUEST -> IEEE80211_BASE_HEADER_SIZE + 4
            SUBTYPE_REASSOCIATION_REQUEST -> IEEE80211_BASE_HEADER_SIZE + 10
            SUBTYPE_PROBE_RESPONSE,
            SUBTYPE_BEACON -> IEEE80211_BASE_HEADER_SIZE + 12
            else -> return
        }

        if (informationElementsOffset > frame.size) {
            return
        }

        val bssid = frame.copyOfRange(16, 22).toHex()

        if (bssid == BROADCAST_MAC || bssid == ZERO_MAC) {
            return
        }

        val ssid = findSsidInformationElement(frame, informationElementsOffset) ?: return

        if (ssid.isNotEmpty()) {
            ssidsByBssid[bssid] = ssid
        }
    }

    /**
     * Parses an infrastructure data frame carrying an EAPOL-Key packet and stores
     * M1 or M2 data indexed by AP, client and replay counter.
     */
    private fun parseEapolFrame(
        frame: ByteArray,
        message1BySession: MutableMap<SessionKey, Message1>,
        message2BySession: MutableMap<SessionKey, Message2>
    ) {
        if (frame.size < IEEE80211_BASE_HEADER_SIZE) {
            return
        }

        val frameControl = readUInt16LittleEndian(frame, 0)
        val type = (frameControl ushr 2) and 0x03

        if (type != FRAME_TYPE_DATA || frameControl and FRAME_CONTROL_PROTECTED != 0) {
            return
        }

        val toDs = frameControl and FRAME_CONTROL_TO_DS != 0
        val fromDs = frameControl and FRAME_CONTROL_FROM_DS != 0

        if (toDs == fromDs) {
            return
        }

        val macPair = if (toDs) {
            MacPair(
                ap = frame.copyOfRange(4, 10).toHex(),
                client = frame.copyOfRange(10, 16).toHex()
            )
        } else {
            MacPair(
                ap = frame.copyOfRange(10, 16).toHex(),
                client = frame.copyOfRange(4, 10).toHex()
            )
        }

        val subtype = (frameControl ushr 4) and 0x0f
        var headerLength = IEEE80211_BASE_HEADER_SIZE

        if (subtype and DATA_SUBTYPE_QOS_BIT != 0) {
            headerLength += QOS_CONTROL_SIZE

            if (frameControl and FRAME_CONTROL_ORDER != 0) {
                headerLength += HT_CONTROL_SIZE
            }
        }

        if (frame.size < headerLength + LLC_SNAP_EAPOL.size + EAPOL_MIN_KEY_FRAME_SIZE) {
            return
        }

        for (index in LLC_SNAP_EAPOL.indices) {
            if (frame[headerLength + index] != LLC_SNAP_EAPOL[index]) {
                return
            }
        }

        val eapolOffset = headerLength + LLC_SNAP_EAPOL.size
        val eapolBodyLength = readUInt16BigEndian(frame, eapolOffset + 2)
        val eapolLength = EAPOL_HEADER_SIZE + eapolBodyLength

        if (
            frame[eapolOffset + 1].toInt() and 0xff != EAPOL_TYPE_KEY ||
            eapolBodyLength < EAPOL_KEY_BODY_MIN_SIZE ||
            eapolOffset + eapolLength > frame.size
        ) {
            return
        }

        val eapol = frame.copyOfRange(eapolOffset, eapolOffset + eapolLength)
        val keyInfo = readUInt16BigEndian(eapol, EAPOL_KEY_INFO_OFFSET)

        if (keyInfo and KEY_INFO_PAIRWISE == 0) {
            return
        }

        val ack = keyInfo and KEY_INFO_ACK != 0
        val micPresent = keyInfo and KEY_INFO_MIC != 0
        val secure = keyInfo and KEY_INFO_SECURE != 0
        val replayCounter = eapol.copyOfRange(
            EAPOL_REPLAY_COUNTER_OFFSET,
            EAPOL_REPLAY_COUNTER_OFFSET + EAPOL_REPLAY_COUNTER_SIZE
        ).toHex()

        val session = SessionKey(
            ap = macPair.ap,
            client = macPair.client,
            replayCounter = replayCounter
        )

        when {
            ack && !micPresent -> {
                val anonce = eapol.copyOfRange(
                    EAPOL_NONCE_OFFSET,
                    EAPOL_NONCE_OFFSET + EAPOL_NONCE_SIZE
                )

                message1BySession[session] = Message1(anonce)
            }

            !ack && micPresent && !secure -> {
                val mic = eapol.copyOfRange(
                    EAPOL_MIC_OFFSET,
                    EAPOL_MIC_OFFSET + EAPOL_MIC_SIZE
                )

                val eapolWithoutMic = eapol.copyOf()
                eapolWithoutMic.fill(
                    0,
                    EAPOL_MIC_OFFSET,
                    EAPOL_MIC_OFFSET + EAPOL_MIC_SIZE
                )

                message2BySession[session] = Message2(
                    mic = mic,
                    eapol = eapolWithoutMic
                )
            }
        }
    }

    /**
     * Reads the SSID information element beginning at the supplied information
     * element region and returns its raw bytes when the element is well formed.
     */
    private fun findSsidInformationElement(
        frame: ByteArray,
        startOffset: Int
    ): ByteArray? {
        var offset = startOffset

        while (offset + 2 <= frame.size) {
            val id = frame[offset].toInt() and 0xff
            val length = frame[offset + 1].toInt() and 0xff
            val valueOffset = offset + 2
            val nextOffset = valueOffset + length

            if (nextOffset > frame.size) {
                return null
            }

            if (id == INFORMATION_ELEMENT_SSID) {
                if (length > MAX_SSID_LENGTH) {
                    return null
                }

                return frame.copyOfRange(valueOffset, nextOffset)
            }

            offset = nextOffset
        }

        return null
    }

    /**
     * Formats one exact M1+M2 pair as the text representation consumed by
     * Hashcat mode 22000. Message-pair value 00 denotes M1+M2 with EAPOL from M2.
     */
    private fun buildHc22000Line(
        session: SessionKey,
        ssid: ByteArray,
        message1: Message1,
        message2: Message2
    ): String {
        return buildString {
            append("WPA*02*")
            append(message2.mic.toHex())
            append('*')
            append(session.ap)
            append('*')
            append(session.client)
            append('*')
            append(ssid.toHex())
            append('*')
            append(message1.anonce.toHex())
            append('*')
            append(message2.eapol.toHex())
            append("*00")
        }
    }

    /**
     * Detects classic pcap byte order from the global magic value and rejects
     * pcapng explicitly so callers do not receive a misleading empty result.
     */
    private fun detectByteOrder(globalHeader: ByteArray): ByteOrder {
        return when {
            globalHeader.startsWith(PCAP_MAGIC_LE_MICROSECONDS) -> ByteOrder.LITTLE_ENDIAN
            globalHeader.startsWith(PCAP_MAGIC_BE_MICROSECONDS) -> ByteOrder.BIG_ENDIAN
            globalHeader.startsWith(PCAP_MAGIC_LE_NANOSECONDS) -> ByteOrder.LITTLE_ENDIAN
            globalHeader.startsWith(PCAP_MAGIC_BE_NANOSECONDS) -> ByteOrder.BIG_ENDIAN
            globalHeader.startsWith(PCAPNG_MAGIC) -> {
                throw IOException("pcapng пока не поддерживается. Нужен классический .cap/.pcap.")
            }

            else -> throw IOException("Файл не является поддерживаемым классическим pcap.")
        }
    }

    /**
     * Reads exactly the requested byte count and throws when EOF occurs before
     * the complete block is available.
     */
    private fun readRequired(input: InputStream, size: Int): ByteArray {
        return readOptional(input, size)
            ?: throw EOFException("Неожиданный конец pcap-файла.")
    }

    /**
     * Reads one fixed-size block, returns null only when EOF is reached before any
     * byte is read, and throws for a partially truncated block.
     */
    private fun readOptional(input: InputStream, size: Int): ByteArray? {
        val data = ByteArray(size)
        var offset = 0

        while (offset < size) {
            val read = input.read(data, offset, size - offset)

            if (read < 0) {
                if (offset == 0) {
                    return null
                }

                throw EOFException("Неожиданный конец pcap-файла.")
            }

            offset += read
        }

        return data
    }

    /**
     * Reads an unsigned 32-bit integer in the capture byte order.
     */
    private fun readUInt32(
        data: ByteArray,
        offset: Int,
        byteOrder: ByteOrder
    ): Long {
        val b0 = data[offset].toLong() and 0xff
        val b1 = data[offset + 1].toLong() and 0xff
        val b2 = data[offset + 2].toLong() and 0xff
        val b3 = data[offset + 3].toLong() and 0xff

        return if (byteOrder == ByteOrder.LITTLE_ENDIAN) {
            b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
        } else {
            (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
        }
    }

    /**
     * Reads an unsigned little-endian 16-bit integer.
     */
    private fun readUInt16LittleEndian(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xff) or
            ((data[offset + 1].toInt() and 0xff) shl 8)
    }

    /**
     * Reads an unsigned big-endian 16-bit integer.
     */
    private fun readUInt16BigEndian(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xff) shl 8) or
            (data[offset + 1].toInt() and 0xff)
    }

    /**
     * Returns true when this byte array begins with the supplied byte sequence.
     */
    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) {
            return false
        }

        return prefix.indices.all { index ->
            this[index] == prefix[index]
        }
    }

    /**
     * Encodes bytes as lowercase hexadecimal without separators.
     */
    private fun ByteArray.toHex(): String {
        val chars = CharArray(size * 2)
        var outputIndex = 0

        for (byte in this) {
            val value = byte.toInt() and 0xff
            chars[outputIndex++] = HEX[value ushr 4]
            chars[outputIndex++] = HEX[value and 0x0f]
        }

        return String(chars)
    }

    private companion object {
        const val PCAP_GLOBAL_HEADER_SIZE = 24
        const val PCAP_PACKET_HEADER_SIZE = 16
        const val MAX_PACKET_SIZE = 16L * 1024L * 1024L

        const val LINKTYPE_IEEE802_11 = 105
        const val LINKTYPE_IEEE802_11_RADIOTAP = 127

        const val RADIOTAP_MIN_HEADER_SIZE = 8
        const val IEEE80211_BASE_HEADER_SIZE = 24
        const val QOS_CONTROL_SIZE = 2
        const val HT_CONTROL_SIZE = 4

        const val FRAME_TYPE_MANAGEMENT = 0
        const val FRAME_TYPE_DATA = 2

        const val SUBTYPE_ASSOCIATION_REQUEST = 0
        const val SUBTYPE_REASSOCIATION_REQUEST = 2
        const val SUBTYPE_PROBE_RESPONSE = 5
        const val SUBTYPE_BEACON = 8
        const val DATA_SUBTYPE_QOS_BIT = 0x08

        const val FRAME_CONTROL_TO_DS = 0x0100
        const val FRAME_CONTROL_FROM_DS = 0x0200
        const val FRAME_CONTROL_PROTECTED = 0x4000
        const val FRAME_CONTROL_ORDER = 0x8000

        const val INFORMATION_ELEMENT_SSID = 0
        const val MAX_SSID_LENGTH = 32

        const val EAPOL_HEADER_SIZE = 4
        const val EAPOL_TYPE_KEY = 3
        const val EAPOL_KEY_BODY_MIN_SIZE = 95
        const val EAPOL_MIN_KEY_FRAME_SIZE = EAPOL_HEADER_SIZE + EAPOL_KEY_BODY_MIN_SIZE
        const val EAPOL_KEY_INFO_OFFSET = 5
        const val EAPOL_REPLAY_COUNTER_OFFSET = 9
        const val EAPOL_REPLAY_COUNTER_SIZE = 8
        const val EAPOL_NONCE_OFFSET = 17
        const val EAPOL_NONCE_SIZE = 32
        const val EAPOL_MIC_OFFSET = 81
        const val EAPOL_MIC_SIZE = 16

        const val KEY_INFO_PAIRWISE = 0x0008
        const val KEY_INFO_ACK = 0x0080
        const val KEY_INFO_MIC = 0x0100
        const val KEY_INFO_SECURE = 0x0200

        const val BROADCAST_MAC = "ffffffffffff"
        const val ZERO_MAC = "000000000000"

        val PCAP_MAGIC_LE_MICROSECONDS = byteArrayOf(0xd4.toByte(), 0xc3.toByte(), 0xb2.toByte(), 0xa1.toByte())
        val PCAP_MAGIC_BE_MICROSECONDS = byteArrayOf(0xa1.toByte(), 0xb2.toByte(), 0xc3.toByte(), 0xd4.toByte())
        val PCAP_MAGIC_LE_NANOSECONDS = byteArrayOf(0x4d.toByte(), 0x3c.toByte(), 0xb2.toByte(), 0xa1.toByte())
        val PCAP_MAGIC_BE_NANOSECONDS = byteArrayOf(0xa1.toByte(), 0xb2.toByte(), 0x3c.toByte(), 0x4d.toByte())
        val PCAPNG_MAGIC = byteArrayOf(0x0a, 0x0d, 0x0d, 0x0a)

        val LLC_SNAP_EAPOL = byteArrayOf(
            0xaa.toByte(),
            0xaa.toByte(),
            0x03,
            0x00,
            0x00,
            0x00,
            0x88.toByte(),
            0x8e.toByte()
        )

        val HEX = "0123456789abcdef".toCharArray()
    }
}
