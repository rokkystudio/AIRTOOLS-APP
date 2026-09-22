package fuck.system.airtools

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import fuck.system.airtools.databinding.ActivityMainBinding
import fuck.system.airtools.device.AirodumpTarget
import fuck.system.airtools.device.AirtoolsRepository
import fuck.system.airtools.device.AirtoolsStatus
import fuck.system.airtools.device.HandshakeIndexEntry
import fuck.system.airtools.device.WifiNetwork
import kotlin.concurrent.thread

class MainActivity : ThemedActivity()
{
    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: AirtoolsRepository
    private lateinit var networkAdapter: WifiNetworkAdapter

    @Volatile private var monitorGeneration = 0
    @Volatile private var commandInProgress = false
    private var consecutiveFailures = 0
    private var connectedOnce = false
    private var currentScreen = Screen.CONNECTING
    private var selectedNetwork: WifiNetwork? = null

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
        binding.aireplayTestButton.setOnClickListener { runAireplayTest() }

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
                    when
                    {
                        status.scanningNetworks -> updateScanning(generation, repository.networks())
                        status.running && status.target is AirodumpTarget.Bssid -> updateCapture(generation, status)
                        status.target is AirodumpTarget.Bssid && status.channel != null -> {
                            repository.start()
                        }
                        else -> {
                            repository.startNetworkScan()
                        }
                    }
                }
                catch (_: Throwable)
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

    private fun updateCapture(generation: Int, status: AirtoolsStatus)
    {
        val target = status.target as AirodumpTarget.Bssid
        val networks = repository.networks()
        val live = networks.firstOrNull { it.bssid.equals(target.value, ignoreCase = true) }
        val cached = selectedNetwork?.takeIf { it.bssid.equals(target.value, ignoreCase = true) }
            ?: loadSelectedNetwork()?.takeIf { it.bssid.equals(target.value, ignoreCase = true) }
        val network = mergeNetwork(live, cached, target.value, status.channel ?: cached?.channel ?: 0)
        val handshakes = repository.handshakes().second.filter { it.bssid.equals(target.value, ignoreCase = true) }
        runOnUiThread {
            if (generation != monitorGeneration) return@runOnUiThread
            selectedNetwork = network
            renderConnection(ConnectionState.CONNECTED)
            renderScreen(Screen.CAPTURE)
            renderCapture(network, handshakes.maxByOrNull { it.storedTick })
        }
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
                if (status.running && status.target is AirodumpTarget.Bssid) {
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
                val networks = repository.networks()
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

    private fun runAireplayTest()
    {
        if (commandInProgress || currentScreen != Screen.CAPTURE) return
        commandInProgress = true
        binding.aireplayTestButton.isEnabled = false
        binding.aireplayResultTextView.text = getString(R.string.aireplay_running)
        thread(name = "airtools-aireplay-test") {
            try
            {
                repository.aireplayTest()
                runOnUiThread { binding.aireplayResultTextView.text = getString(R.string.aireplay_ok) }
            }
            catch (_: Throwable)
            {
                runOnUiThread { binding.aireplayResultTextView.text = getString(R.string.operation_failed) }
            }
            finally
            {
                commandInProgress = false
                runOnUiThread { binding.aireplayTestButton.isEnabled = true }
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

    private fun renderCapture(network: WifiNetwork, handshake: HandshakeIndexEntry?)
    {
        binding.captureNetworkNameTextView.text = displayEssid(network)
        binding.captureBssidTextView.text = network.bssid
        binding.captureChannelTextView.text = getString(R.string.channel_value, network.channel)
        binding.captureSignalTextView.text = network.signalDbm?.let { getString(R.string.signal_value, it) }
            ?: getString(R.string.signal_value_unknown)
        binding.captureCountersTextView.text = getString(
            R.string.capture_counters,
            network.beacons,
            network.probes,
            network.dataFrames
        )
        if (handshake == null) {
            binding.handshakeStatusTextView.setTextColor(getColor(R.color.text_secondary))
            binding.handshakeStatusTextView.text = getString(R.string.handshake_waiting)
        } else {
            binding.handshakeStatusTextView.setTextColor(getColor(R.color.status_connected))
            binding.handshakeStatusTextView.text = getString(
                R.string.handshake_captured,
                handshake.frameCount,
                handshake.file
            )
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