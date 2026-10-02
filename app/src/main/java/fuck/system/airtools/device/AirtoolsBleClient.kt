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
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Maintains a reusable BLE GATT session for AIRTOOLS commands. */
class AirtoolsBleClient(
    context: Context,
    private val timeoutMillis: Int = AirtoolsDevice.DEFAULT_BLE_TIMEOUT_MILLIS
) : AirtoolsConnection
{
    private val context = context.applicationContext
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val cachedDevice = AtomicReference<BluetoothDevice?>()
    private val sessionLock = Any()
    private var session: GattSession? = null

    /** Sends a command over one persistent BLE connection and waits for the EOF notification marker. */
    @SuppressLint("MissingPermission")
    override fun request(command: String): AirtoolsResponse
    {
        ensurePermissions()
        synchronized(sessionLock)
        {
            val active = session ?: GattSession(findDevice()).also { session = it }
            return try {
                AirtoolsResponse(command, active.request(command))
            }
            catch (error: Throwable)
            {
                active.close()
                if (session === active) session = null
                cachedDevice.set(null)
                throw error
            }
        }
    }

    /** Downloads a hex-framed PCAP response from AIRTOOLS and writes decoded bytes. */
    override fun download(command: String, output: OutputStream): Long
    {
        val response = request(command)
        require(response.ok) { response.text.trim() }

        val lines = response.text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        require(lines.size >= 2) { response.text.trim() }

        val header = lines.first()
        require(header.startsWith("OK handshake_hex ")) { header }

        val expectedBytes = HANDSHAKE_BYTES_PATTERN.find(header)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: throw IOException("Missing handshake size")
        val hex = lines.drop(1).joinToString("")
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
        cachedDevice.get()?.let { return it }

        val adapter = bluetoothManager?.adapter ?: throw IOException("Bluetooth adapter unavailable")
        if (!adapter.isEnabled) throw IOException("Bluetooth is disabled")

        adapter.bondedDevices.firstOrNull {
            runCatching { it.name == AirtoolsDevice.BLE_DEVICE_NAME }.getOrDefault(false)
        }?.let {
            cachedDevice.set(it)
            return it
        }

        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.P..Build.VERSION_CODES.R) {
            val locationManager = context.getSystemService(LocationManager::class.java)
            if (locationManager != null && !locationManager.isLocationEnabled) {
                throw IOException("Location must be enabled for BLE scan on Android 9-11")
            }
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

        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(emptyList(), settings, callback)
        try {
            latch.await(timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
        } finally {
            scanner.stopScan(callback)
        }

        val device = found.get() ?: throw IOException("AIRTOOLS BLE device unavailable")
        cachedDevice.set(device)
        return device
    }

    private fun matchesAirtools(result: ScanResult): Boolean
    {
        val uuids = result.scanRecord?.serviceUuids.orEmpty()
        return uuids.any { it.uuid == SERVICE_UUID } || result.device.name == AirtoolsDevice.BLE_DEVICE_NAME
    }

    private fun ensurePermissions()
    {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requirePermission(Manifest.permission.BLUETOOTH_SCAN)
            requirePermission(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            requirePermission(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun requirePermission(permission: String)
    {
        if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            throw IOException("Missing permission: $permission")
        }
    }

    private inner class GattSession(private val device: BluetoothDevice) : BluetoothGattCallback()
    {
        private val readyLatch = CountDownLatch(1)
        private val connectionError = AtomicReference<Throwable?>()
        private var gatt: BluetoothGatt? = null
        private var rxCharacteristic: BluetoothGattCharacteristic? = null
        private var txCharacteristic: BluetoothGattCharacteristic? = null
        private var responseLatch: CountDownLatch? = null
        private var response = ByteArrayOutputStream()

        init {
            @SuppressLint("MissingPermission")
            gatt = device.connectGatt(context, false, this, BluetoothDevice.TRANSPORT_LE)
        }

        @SuppressLint("MissingPermission")
        fun request(command: String): String
        {
            if (!readyLatch.await(timeoutMillis.toLong(), TimeUnit.MILLISECONDS)) {
                throw IOException("BLE connection timeout")
            }
            connectionError.get()?.let { throw it }

            response = ByteArrayOutputStream()
            val latch = CountDownLatch(1)
            responseLatch = latch

            val gatt = gatt ?: throw IOException("BLE session is closed")
            val rx = rxCharacteristic ?: throw IOException("AIRTOOLS BLE RX characteristic is unavailable")
            rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            rx.value = (command + "\n").toByteArray(Charsets.UTF_8)
            if (!gatt.writeCharacteristic(rx)) {
                throw IOException("BLE command write was not accepted")
            }

            if (!latch.await(timeoutMillis.toLong(), TimeUnit.MILLISECONDS)) {
                throw IOException("BLE response timeout")
            }
            connectionError.get()?.let { throw it }

            return response.toString(Charsets.UTF_8.name()).replace(EOF_MARKER, "").trimEnd().also {
                require(it.isNotEmpty()) { "Empty BLE response" }
            }
        }

        @SuppressLint("MissingPermission")
        fun close()
        {
            runCatching { gatt?.disconnect() }
            runCatching { gatt?.close() }
            gatt = null
        }

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int)
        {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail(IOException("BLE connection failed: $status"))
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> gatt.discoverServices()
                BluetoothProfile.STATE_DISCONNECTED -> fail(IOException("BLE disconnected"))
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int)
        {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail(IOException("BLE service discovery failed: $status"))
                return
            }

            val service = gatt.getService(SERVICE_UUID)
            val tx = service?.getCharacteristic(TX_UUID)
            val rx = service?.getCharacteristic(RX_UUID)
            if (service == null || tx == null || rx == null) {
                fail(IOException("AIRTOOLS BLE service is incomplete"))
                return
            }

            rxCharacteristic = rx
            txCharacteristic = tx
            if (!gatt.requestMtu(AirtoolsDevice.BLE_MTU)) enableNotifications(gatt)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int)
        {
            enableNotifications(gatt)
        }

        @SuppressLint("MissingPermission")
        private fun enableNotifications(gatt: BluetoothGatt)
        {
            val tx = txCharacteristic ?: run {
                fail(IOException("AIRTOOLS BLE TX characteristic is unavailable"))
                return
            }
            if (!gatt.setCharacteristicNotification(tx, true)) {
                fail(IOException("BLE notification subscription was rejected"))
                return
            }

            val descriptor = tx.getDescriptor(CLIENT_CONFIG_UUID)
            if (descriptor == null) {
                readyLatch.countDown()
                return
            }

            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (!gatt.writeDescriptor(descriptor)) {
                fail(IOException("BLE notification descriptor write was not accepted"))
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int)
        {
            if (status == BluetoothGatt.GATT_SUCCESS) readyLatch.countDown()
            else fail(IOException("BLE notify setup failed: $status"))
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int)
        {
            if (status != BluetoothGatt.GATT_SUCCESS) fail(IOException("BLE command write failed: $status"))
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic)
        {
            appendNotification(characteristic.value)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray)
        {
            appendNotification(value)
        }

        private fun appendNotification(value: ByteArray)
        {
            synchronized(response)
            {
                response.write(value)
                if (response.toString(Charsets.UTF_8.name()).contains(EOF_MARKER)) {
                    responseLatch?.countDown()
                }
            }
        }

        private fun fail(error: Throwable)
        {
            connectionError.compareAndSet(null, error)
            readyLatch.countDown()
            responseLatch?.countDown()
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
