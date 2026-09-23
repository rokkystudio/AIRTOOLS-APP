package fuck.system.airtools

import android.content.ContentValues
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.Toast
import java.io.File
import java.io.OutputStream
import java.text.DateFormat
import java.util.Date
import fuck.system.airtools.databinding.ActivityMainBinding
import fuck.system.airtools.device.AirodumpTarget
import fuck.system.airtools.device.AirtoolsRepository
import fuck.system.airtools.device.AirtoolsStatus
import fuck.system.airtools.device.HandshakeIndexEntry
import fuck.system.airtools.device.DeviceMode
import fuck.system.airtools.device.WifiNetwork
import kotlin.concurrent.thread

/** Presents the device connection, network scan, capture details, and saved handshake actions. */
class MainActivity : ThemedActivity()
{
    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: AirtoolsRepository
    private lateinit var networkAdapter: WifiNetworkAdapter
    private val signalTracker = WifiSignalTracker()

    @Volatile private var monitorGeneration = 0
    @Volatile private var commandInProgress = false
    private var consecutiveFailures = 0
    private var connectedOnce = false
    private var currentScreen = Screen.CONNECTING
    private var selectedNetwork: WifiNetwork? = null
    private var replayStatusText: String? = null
    private val captureDateFormat: DateFormat by lazy { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM) }
    private val capturedHandshakes = mutableMapOf<String, HandshakeIndexEntry>()

    override fun onCreate(savedInstanceState: Bundle?)
    {
        super.onCreate(savedInstanceState)
        repository = AirtoolsRepository(applicationContext)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupTopBar(binding.topBar, getString(R.string.app_name))

        networkAdapter = WifiNetworkAdapter(this)
        binding.networksListView.adapter = networkAdapter
        binding.networksListView.setOnItemClickListener { _, _, position, _ ->
            selectNetwork(networkAdapter.getItem(position))
        }
        binding.topBar.backButton.setOnClickListener { returnToNetworkSelection() }
        binding.replayButton.setOnClickListener { startReplay() }
        binding.handshakeDownloadButton.setOnClickListener { downloadHandshake() }

        selectedNetwork = loadSelectedNetwork()
        renderConnection(ConnectionState.CONNECTING)
        renderScreen(Screen.CONNECTING)
    }

    override fun onResume()
    {
        super.onResume()
        val generation = ++monitorGeneration
        thread(name = "airtools-device-monitor") { monitorDevice(generation) }
    }

    override fun onPause()
    {
        monitorGeneration++
        super.onPause()
    }

    private fun monitorDevice(generation: Int)
    {
        while (generation == monitorGeneration)
        {
            if (!commandInProgress)
            {
                try
                {
                    val status = repository.status()
                    consecutiveFailures = 0
                    connectedOnce = true
                    runOnUiThread {
                        if (generation == monitorGeneration) {
                            renderConnection(ConnectionState.CONNECTED)
                            when (status.mode) {
                                DeviceMode.SCAN -> renderScreen(Screen.SCAN)
                                DeviceMode.CAPTURE -> renderScreen(Screen.CAPTURE)
                                DeviceMode.IDLE -> renderScreen(Screen.CONNECTING)
                            }
                        }
                    }
                    try {
                        when (status.mode)
                        {
                            DeviceMode.SCAN -> signalTracker.update(repository.networks()).let { updateScanning(generation, it) }
                            DeviceMode.CAPTURE -> updateCapture(generation, status)
                            DeviceMode.IDLE -> repository.startNetworkScan()
                        }
                    } catch (_: Throwable) {
                        // /status succeeded: keep the device connected and retry mode data next poll.
                    }
                }
                catch (error: Throwable)
                {
                    consecutiveFailures++
                    if (!connectedOnce || consecutiveFailures >= FAILURE_THRESHOLD)
                    {
                        runOnUiThread {
                            if (generation == monitorGeneration) {
                                renderConnection(ConnectionState.UNAVAILABLE)
                                renderScreen(Screen.UNAVAILABLE)
                            }
                        }
                    }
                }
            }
            try
            {
                Thread.sleep(REFRESH_MILLIS)
            }
            catch (_: InterruptedException)
            {
                return
            }
        }
    }

    private fun updateScanning(generation: Int, networks: List<WifiNetwork>)
    {
        runOnUiThread {
            if (generation != monitorGeneration) return@runOnUiThread
            renderConnection(ConnectionState.CONNECTED)
            renderScreen(Screen.SCAN)
            networkAdapter.submit(networks)
            binding.networkCountTextView.text = resources.getQuantityString(
                R.plurals.networks_found,
                networks.size,
                networks.size
            )
        }
    }

    /** Refreshes the selected network details and retains the newest handshake entry for its BSSID. */
    private fun updateCapture(generation: Int, status: AirtoolsStatus)
    {
        val target = status.target as AirodumpTarget.Bssid
        val networks = signalTracker.update(runCatching { repository.networks() }.getOrDefault(emptyList()))
        val live = networks.firstOrNull { it.bssid.equals(target.value, ignoreCase = true) }
        val cached = selectedNetwork?.takeIf { it.bssid.equals(target.value, ignoreCase = true) }
            ?: loadSelectedNetwork()?.takeIf { it.bssid.equals(target.value, ignoreCase = true) }
        val network = mergeNetwork(live, cached, target.value, status.channel ?: cached?.channel ?: 0)
        val latestHandshake = runCatching { repository.handshakes().second }.getOrDefault(emptyList())
            .filter { it.bssid.equals(target.value, ignoreCase = true) }
            .maxByOrNull { it.storedTick }
        val handshake = synchronized(capturedHandshakes) {
            val key = target.value.lowercase()
            val previous = capturedHandshakes[key]
            if (latestHandshake != null && (previous == null || latestHandshake.storedTick > previous.storedTick)) {
                capturedHandshakes[key] = rememberHandshakeTimestamp(latestHandshake)
            }
            capturedHandshakes[key]
        }
        runOnUiThread {
            if (generation != monitorGeneration) return@runOnUiThread
            selectedNetwork = network
            renderConnection(ConnectionState.CONNECTED)
            renderScreen(Screen.CAPTURE)
            renderCapture(network, handshake)
        }
    }

    /** Assigns a stable Android-side capture time when firmware cannot provide a valid wall clock. */
    private fun rememberHandshakeTimestamp(handshake: HandshakeIndexEntry): HandshakeIndexEntry
    {
        val key = "handshake_at_${handshake.bssid.lowercase()}_${handshake.storedTick}"
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val stored = prefs.getLong(key, 0L).takeIf { it > 0L }
        val capturedAt = handshake.capturedAtMillis ?: stored ?: System.currentTimeMillis()
        if (stored == null) {
            prefs.edit().putLong(key, capturedAt).apply()
        }
        return handshake.copy(capturedAtMillis = capturedAt)
    }

    private fun mergeNetwork(live: WifiNetwork?, cached: WifiNetwork?, bssid: String, channel: Int): WifiNetwork
    {
        if (live != null) {
            val essid = if (live.essid == "<hidden/unknown>" && cached != null) cached.essid else live.essid
            return live.copy(essid = essid)
        }
        return cached?.copy(bssid = bssid, channel = channel) ?: WifiNetwork(
            bssid = bssid,
            channel = channel,
            signalDbm = null,
            beacons = 0,
            probes = 0,
            dataFrames = 0,
            essid = "<hidden/unknown>"
        )
    }

    private fun selectNetwork(network: WifiNetwork)
    {
        if (commandInProgress || currentScreen != Screen.SCAN) return
        commandInProgress = true
        binding.scanProgressBar.visibility = View.VISIBLE
        thread(name = "airtools-select-network") {
            try
            {
                repository.selectNetworkAndCapture(network)
                selectedNetwork = network
                saveSelectedNetwork(network)
                val status = repository.status()
                runOnUiThread {
                    renderConnection(ConnectionState.CONNECTED)
                    renderScreen(Screen.CAPTURE)
                    renderCapture(network, null)
                }
                if (status.mode == DeviceMode.CAPTURE && status.target is AirodumpTarget.Bssid) {
                    consecutiveFailures = 0
                }
            }
            catch (_: Throwable)
            {
                runOnUiThread {
                    renderConnection(ConnectionState.UNAVAILABLE)
                    renderScreen(Screen.UNAVAILABLE)
                }
            }
            finally
            {
                commandInProgress = false
            }
        }
    }

    private fun returnToNetworkSelection()
    {
        if (commandInProgress) return
        commandInProgress = true
        binding.topBar.backButton.isEnabled = false
        thread(name = "airtools-return-to-scan") {
            try
            {
                repository.startNetworkScan()
                val networks = signalTracker.update(runCatching { repository.networks() }.getOrDefault(emptyList()))
                runOnUiThread {
                    renderConnection(ConnectionState.CONNECTED)
                    renderScreen(Screen.SCAN)
                    networkAdapter.submit(networks)
                    binding.networkCountTextView.text = resources.getQuantityString(
                        R.plurals.networks_found,
                        networks.size,
                        networks.size
                    )
                }
            }
            catch (_: Throwable)
            {
                runOnUiThread {
                    renderConnection(ConnectionState.UNAVAILABLE)
                    renderScreen(Screen.UNAVAILABLE)
                }
            }
            finally
            {
                commandInProgress = false
                runOnUiThread { binding.topBar.backButton.isEnabled = true }
            }
        }
    }

    private fun renderConnection(state: ConnectionState)
    {
        val (textRes, colorRes) = when (state)
        {
            ConnectionState.CONNECTED -> R.string.status_connected to R.color.status_connected
            ConnectionState.CONNECTING -> R.string.status_connecting to R.color.status_disconnected
            ConnectionState.UNAVAILABLE -> R.string.status_server_unavailable to R.color.status_disconnected
        }
        binding.connectionStatusTextView.setText(textRes)
        binding.connectionStatusIndicator.backgroundTintList = ColorStateList.valueOf(getColor(colorRes))
    }

    private fun renderScreen(screen: Screen)
    {
        currentScreen = screen
        binding.scanContainer.visibility = if (screen == Screen.SCAN || screen == Screen.CONNECTING) View.VISIBLE else View.GONE
        binding.captureScrollView.visibility = if (screen == Screen.CAPTURE) View.VISIBLE else View.GONE
        binding.unavailableTextView.visibility = if (screen == Screen.UNAVAILABLE) View.VISIBLE else View.GONE
        binding.topBar.backButton.visibility = if (screen == Screen.CAPTURE) View.VISIBLE else View.GONE
        binding.topBar.appIconImageView.visibility = if (screen == Screen.CAPTURE) View.GONE else View.VISIBLE
        binding.scanProgressBar.visibility = if (screen == Screen.SCAN || screen == Screen.CONNECTING) View.VISIBLE else View.GONE
        if (screen == Screen.CONNECTING) {
            binding.networkCountTextView.text = getString(R.string.waiting_for_device)
        }
    }

    /** Renders capture counters, signal details, and the available handshake action. */
    private fun renderCapture(network: WifiNetwork, handshake: HandshakeIndexEntry?)
    {
        binding.captureNetworkNameTextView.text = displayEssid(network)
        binding.captureBssidTextView.text = network.bssid
        binding.captureChannelTextView.text = getString(R.string.channel_value, network.channel)
        val signalLevel = WifiSignalLevel.from(network)
        binding.captureSignalImageView.setImageResource(signalLevel.drawableRes)
        binding.captureSignalTextView.text = when
        {
            !network.online -> getString(R.string.signal_value_offline)
            network.signalDbm != null -> getString(R.string.signal_value, network.signalDbm, signalPercent(network.signalDbm))
            else -> getString(R.string.signal_value_unknown)
        }
        binding.captureSignalImageView.contentDescription = binding.captureSignalTextView.text
        binding.captureCountersTextView.text = getString(
            R.string.capture_counters,
            network.beacons,
            network.probes,
            network.dataFrames
        )
        binding.replayButton.isEnabled = !commandInProgress
        binding.replayStatusTextView.text = replayStatusText ?: getString(R.string.replay_hint)
        binding.handshakeDownloadButton.visibility = if (handshake == null) View.GONE else View.VISIBLE
        binding.handshakeDownloadButton.isEnabled = handshake != null && !commandInProgress
        if (handshake == null) {
            binding.handshakeStatusTextView.setTextColor(getColor(R.color.text_secondary))
            binding.handshakeStatusTextView.text = getString(R.string.handshake_waiting)
        } else {
            binding.handshakeStatusTextView.setTextColor(getColor(R.color.status_connected))
            binding.handshakeStatusTextView.text = getString(
                R.string.handshake_captured,
                handshake.frameCount,
                handshake.capturedAtMillis?.let { captureDateFormat.format(Date(it)) } ?: getString(R.string.handshake_time_unknown),
                handshake.fileName
            )
        }
    }

    /** Starts the device replay command for five deauth packets on the active capture target. */
    private fun startReplay()
    {
        if (commandInProgress) return
        val network = selectedNetwork ?: return
        commandInProgress = true
        replayStatusText = getString(R.string.replay_running)
        binding.replayButton.isEnabled = false
        binding.handshakeDownloadButton.isEnabled = false
        binding.replayStatusTextView.text = replayStatusText
        thread(name = "airtools-replay") {
            try
            {
                repository.replay()
                replayStatusText = getString(R.string.replay_started, network.bssid)
            }
            catch (error: Throwable)
            {
                replayStatusText = getString(R.string.replay_failed, error.message ?: "")
            }
            finally
            {
                commandInProgress = false
                runOnUiThread {
                    val currentNetwork = selectedNetwork
                    val currentHandshake = currentNetwork?.let { item ->
                        synchronized(capturedHandshakes) { capturedHandshakes[item.bssid.lowercase()] }
                    }
                    if (currentNetwork != null) renderCapture(currentNetwork, currentHandshake)
                }
            }
        }
    }

    /** Downloads the selected handshake and reports the saved file result. */
    private fun downloadHandshake()
    {
        if (commandInProgress) return
        val network = selectedNetwork ?: return
        val handshake = synchronized(capturedHandshakes) { capturedHandshakes[network.bssid.lowercase()] } ?: return
        commandInProgress = true
        binding.replayButton.isEnabled = false
        binding.handshakeDownloadButton.isEnabled = false
        binding.handshakeStatusTextView.setTextColor(getColor(R.color.text_secondary))
        binding.handshakeStatusTextView.text = getString(R.string.handshake_downloading)
        thread(name = "airtools-handshake-download") {
            val fileName = handshake.fileName
            try
            {
                saveHandshakeFile(fileName) { output -> repository.downloadHandshake(fileName, output) }
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.handshake_downloaded, fileName), Toast.LENGTH_SHORT).show()
                }
            }
            catch (error: Throwable)
            {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        getString(R.string.handshake_download_failed, error.message ?: ""),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            finally
            {
                commandInProgress = false
                runOnUiThread {
                    val currentNetwork = selectedNetwork
                    val currentHandshake = currentNetwork?.let { item ->
                        synchronized(capturedHandshakes) { capturedHandshakes[item.bssid.lowercase()] }
                    }
                    if (currentNetwork != null) renderCapture(currentNetwork, currentHandshake)
                    else binding.handshakeDownloadButton.isEnabled = true
                }
            }
        }
    }

    /** Saves a streamed handshake PCAP in Downloads/Airtools or app-specific Downloads on older Android. */
    private fun saveHandshakeFile(fileName: String, writer: (OutputStream) -> Long): Long
    {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/vnd.tcpdump.pcap")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Airtools")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Could not create the Downloads file")
            try
            {
                val bytes = contentResolver.openOutputStream(uri)?.use(writer)
                    ?: throw IllegalStateException("Could not open the Downloads file")
                contentResolver.update(uri, ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }, null, null)
                return bytes
            }
            catch (error: Throwable)
            {
                contentResolver.delete(uri, null, null)
                throw error
            }
        } else {
            val directory = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Airtools")
            check(directory.mkdirs() || directory.isDirectory) { "Could not create the Downloads folder" }
            return File(directory, fileName).outputStream().use(writer)
        }
    }

    private fun displayEssid(network: WifiNetwork): String =
        if (network.essid == "<hidden/unknown>") getString(R.string.hidden_network) else network.essid

    private fun saveSelectedNetwork(network: WifiNetwork)
    {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString("bssid", network.bssid)
            .putString("essid", network.essid)
            .putInt("channel", network.channel)
            .putInt("signal", network.signalDbm ?: 0)
            .apply()
    }

    private fun loadSelectedNetwork(): WifiNetwork?
    {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val bssid = prefs.getString("bssid", null) ?: return null
        val channel = prefs.getInt("channel", 0)
        if (channel !in 1..14) return null
        return WifiNetwork(
            bssid = bssid,
            channel = channel,
            signalDbm = prefs.getInt("signal", 0).takeIf { it in -127..-1 },
            beacons = 0,
            probes = 0,
            dataFrames = 0,
            essid = prefs.getString("essid", "<hidden/unknown>") ?: "<hidden/unknown>"
        )
    }

    private enum class Screen { CONNECTING, SCAN, CAPTURE, UNAVAILABLE }
    private enum class ConnectionState { CONNECTED, CONNECTING, UNAVAILABLE }

    companion object
    {
        private const val PREFS = "airtools"
        private const val REFRESH_MILLIS = 1000L
        private const val FAILURE_THRESHOLD = 3
    }
}