package fuck.system.airtools.device

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Blocking BLE GATT client for AIRTOOLS-ESP32 command notifications. */
class AirtoolsBleClient(
    context: Context,
    private val timeoutMillis: Int = AirtoolsDevice.DEFAULT_BLE_TIMEOUT_MILLIS
) : AirtoolsConnection
{
    private val context = context.applicationContext
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)

    /** Scans for AIRTOOLS-ESP32, sends one command through BLE, and collects notifications until EOF. */
    @SuppressLint("MissingPermission")
    override fun request(command: String): AirtoolsResponse
    {
        ensurePermissions()
        val device = findDevice()
        val text = GattRequest(command).execute(device)
        return AirtoolsResponse(command, text)
    }

    /** Downloads a hex-framed PCAP response from AIRTOOLS-ESP32 and writes decoded bytes. */
    override fun download(command: String, output: OutputStream): Long
    {
        val response = request(command)
        require(response.ok) { response.text.trim() }

        val lines = response.text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        require(lines.size >= 2) { response.text.trim() }

        val header = lines.first()
        require(header.startsWith("OK handshake_hex ")) { header }

        val expectedBytes = HANDSHAKE_BYTES_PATTERN.find(header)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: throw IOException("Missing handshake size")
        val hex = lines.drop(1).joinToString(separator = "")
        require(hex.length == expectedBytes * 2) { "Handshake hex size mismatch" }

        val bytes = ByteArray(expectedBytes)
        for (index in bytes.indices) {
            val offset = index * 2
            bytes[index] = ((hexValue(hex[offset]) shl 4) or hexValue(hex[offset + 1])).toByte()
        }

        output.write(bytes)
        output.flush()
        return bytes.size.toLong()
    }

    @SuppressLint("MissingPermission")
    private fun findDevice(): BluetoothDevice
    {
        val adapter = bluetoothManager?.adapter ?: throw IOException("Bluetooth adapter unavailable")
        if (!adapter.isEnabled) {
            throw IOException("Bluetooth is disabled")
        }

        val scanner = adapter.bluetoothLeScanner ?: throw IOException("BLE scanner unavailable")
        val found = AtomicReference<BluetoothDevice?>()
        val latch = CountDownLatch(1)
        val callback = object : ScanCallback()
        {
            override fun onScanResult(callbackType: Int, result: ScanResult)
            {
                if (matchesAirtools(result)) {
                    found.compareAndSet(null, result.device)
                    latch.countDown()
                }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>)
            {
                results.firstOrNull(::matchesAirtools)?.let {
                    found.compareAndSet(null, it.device)
                    latch.countDown()
                }
            }

            override fun onScanFailed(errorCode: Int)
            {
                latch.countDown()
            }
        }

        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build())
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(filters, settings, callback)
        try {
            latch.await(timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
        } finally {
            scanner.stopScan(callback)
        }

        return found.get() ?: throw IOException("AIRTOOLS-ESP32 BLE device unavailable")
    }

    private fun matchesAirtools(result: ScanResult): Boolean
    {
        val uuids = result.scanRecord?.serviceUuids.orEmpty()
        return uuids.any { it.uuid == SERVICE_UUID } || result.device.name == AirtoolsDevice.BLE_DEVICE_NAME
    }

    private fun ensurePermissions()
    {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        {
            requirePermission(Manifest.permission.BLUETOOTH_SCAN)
            requirePermission(Manifest.permission.BLUETOOTH_CONNECT)
        }
        else {
            requirePermission(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun requirePermission(permission: String)
    {
        if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            throw IOException("Missing permission: $permission")
        }
    }

    private inner class GattRequest(private val command: String) : BluetoothGattCallback()
    {
        private val readyLatch = CountDownLatch(1)
        private val responseLatch = CountDownLatch(1)
        private val error = AtomicReference<Throwable?>()
        private val response = ByteArrayOutputStream()
        private var gatt: BluetoothGatt? = null
        private var rxCharacteristic: BluetoothGattCharacteristic? = null

        @SuppressLint("MissingPermission")
        fun execute(device: BluetoothDevice): String
        {
            gatt = device.connectGatt(context, false, this, BluetoothDevice.TRANSPORT_LE)
            try
            {
                if (!readyLatch.await(timeoutMillis.toLong(), TimeUnit.MILLISECONDS)) {
                    throw IOException("BLE connection timeout")
                }
                error.get()?.let { throw it }

                if (!responseLatch.await(timeoutMillis.toLong(), TimeUnit.MILLISECONDS)) {
                    throw IOException("BLE response timeout")
                }
                error.get()?.let { throw it }

                val text = response.toString(Charsets.UTF_8.name())
                    .replace(EOF_MARKER, "")
                    .trimEnd()
                require(text.isNotEmpty()) { "Empty BLE response" }
                return text
            }
            finally
            {
                runCatching { gatt?.disconnect() }
                runCatching { gatt?.close() }
                gatt = null
            }
        }

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int)
        {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                error.set(IOException("BLE connection failed: $status"))
                readyLatch.countDown()
                responseLatch.countDown()
                return
            }

            when (newState)
            {
                BluetoothProfile.STATE_CONNECTED -> gatt.discoverServices()
                BluetoothProfile.STATE_DISCONNECTED -> {
                    error.compareAndSet(null, IOException("BLE disconnected"))
                    readyLatch.countDown()
                    responseLatch.countDown()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int)
        {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                error.set(IOException("BLE service discovery failed: $status"))
                readyLatch.countDown()
                responseLatch.countDown()
                return
            }

            val service = gatt.getService(SERVICE_UUID)
            val tx = service?.getCharacteristic(TX_UUID)
            val rx = service?.getCharacteristic(RX_UUID)
            if (service == null || tx == null || rx == null) {
                error.set(IOException("AIRTOOLS BLE service is incomplete"))
                readyLatch.countDown()
                responseLatch.countDown()
                return
            }

            rxCharacteristic = rx
            gatt.requestMtu(AirtoolsDevice.BLE_MTU)
            gatt.setCharacteristicNotification(tx, true)

            val descriptor = tx.getDescriptor(CLIENT_CONFIG_UUID)
            if (descriptor == null) {
                readyLatch.countDown()
                writeCommand(gatt)
            } else {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                if (!gatt.writeDescriptor(descriptor)) {
                    readyLatch.countDown()
                    writeCommand(gatt)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int)
        {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                readyLatch.countDown()
                writeCommand(gatt)
            } else {
                error.set(IOException("BLE notify setup failed: $status"))
                readyLatch.countDown()
                responseLatch.countDown()
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int)
        {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                error.set(IOException("BLE command write failed: $status"))
                responseLatch.countDown()
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic)
        {
            appendNotification(characteristic.value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        )
        {
            appendNotification(value)
        }

        @SuppressLint("MissingPermission")
        private fun writeCommand(gatt: BluetoothGatt)
        {
            val rx = rxCharacteristic ?: return
            rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            rx.value = (command + "\n").toByteArray(Charsets.UTF_8)
            if (!gatt.writeCharacteristic(rx)) {
                error.set(IOException("BLE command write was not accepted"))
                responseLatch.countDown()
            }
        }

        private fun appendNotification(value: ByteArray)
        {
            synchronized(response) {
                response.write(value)
                if (response.toString(Charsets.UTF_8.name()).contains(EOF_MARKER)) {
                    responseLatch.countDown()
                }
            }
        }
    }

    companion object
    {
        private val SERVICE_UUID: UUID = UUID.fromString("7b459c40-6a5a-4e4c-9ec7-a17001500001")
        private val RX_UUID: UUID = UUID.fromString("7b459c41-6a5a-4e4c-9ec7-a17001500001")
        private val TX_UUID: UUID = UUID.fromString("7b459c42-6a5a-4e4c-9ec7-a17001500001")
        private val CLIENT_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val HANDSHAKE_BYTES_PATTERN = Regex("\\bbytes=(\\d+)\\b")
        private const val EOF_MARKER = "--airtools-eof--"

        private fun hexValue(value: Char): Int = when (value)
        {
            in '0'..'9' -> value - '0'
            in 'a'..'f' -> value - 'a' + 10
            in 'A'..'F' -> value - 'A' + 10
            else -> throw IllegalArgumentException("Invalid handshake hex")
        }
    }
}
