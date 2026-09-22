package fuck.system.airtools

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import fuck.system.airtools.databinding.ActivityMainBinding
import fuck.system.airtools.device.AirtoolsRepository
import fuck.system.airtools.device.AirtoolsStatus
import fuck.system.airtools.device.WifiNetwork
import java.io.IOException
import kotlin.concurrent.thread

class MainActivity : ThemedActivity()
{
    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: AirtoolsRepository
    @Volatile private var busy = false
    @Volatile private var monitorGeneration = 0
    private var connected = false
    private var status: AirtoolsStatus? = null
    private var selectedNetwork: WifiNetwork? = null

    override fun onCreate(savedInstanceState: Bundle?)
    {
        super.onCreate(savedInstanceState)
        repository = AirtoolsRepository(applicationContext)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupTopBar(binding.topBar, getString(R.string.app_name))

        selectedNetwork = loadSelectedNetwork()
        renderSelectedNetwork()
        renderConnectionState(ConnectionState.CONNECTING)
        binding.scanButton.setOnClickListener { openNetworkScan() }
        binding.startStopButton.setOnClickListener { toggleCapture() }
        binding.handshakesButton.setOnClickListener { loadHandshakes() }
        binding.aireplayTestButton.setOnClickListener {
            runAction(getString(R.string.aireplay_test)) { repository.aireplayTest() }
        }
        renderControls()
    }

    override fun onResume()
    {
        super.onResume()
        renderConnectionState(ConnectionState.CONNECTING)
        val generation = ++monitorGeneration
        thread(name = "airtools-connection") { monitorService(generation) }
    }

    override fun onPause()
    {
        monitorGeneration++
        super.onPause()
    }

    @Deprecated("Deprecated in Android API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?)
    {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_SCAN || resultCode != RESULT_OK || data == null) return
        val bssid = data.getStringExtra(ScanActivity.EXTRA_BSSID) ?: return
        val essid = data.getStringExtra(ScanActivity.EXTRA_ESSID) ?: "<hidden/unknown>"
        val channel = data.getIntExtra(ScanActivity.EXTRA_CHANNEL, 0)
        if (channel !in 1..14) return
        selectedNetwork = WifiNetwork(bssid, channel, 0, 0, 0, essid)
        saveSelectedNetwork(selectedNetwork!!)
        renderSelectedNetwork()
        renderControls()
    }

    private fun monitorService(generation: Int)
    {
        while (generation == monitorGeneration)
        {
            if (!busy)
            {
                try
                {
                    val current = repository.status()
                    runOnUiThread {
                        if (generation != monitorGeneration) return@runOnUiThread
                        connected = true
                        status = current
                        renderConnectionState(ConnectionState.CONNECTED)
                        renderControls()
                    }
                }
                catch (_: IOException)
                {
                    runOnUiThread {
                        if (generation != monitorGeneration) return@runOnUiThread
                        connected = false
                        status = null
                        renderConnectionState(ConnectionState.UNAVAILABLE)
                        renderControls()
                    }
                }
                catch (_: Throwable)
                {
                    runOnUiThread {
                        if (generation != monitorGeneration) return@runOnUiThread
                        connected = false
                        status = null
                        renderConnectionState(ConnectionState.UNAVAILABLE)
                        renderControls()
                    }
                }
            }
            try
            {
                Thread.sleep(CONNECTION_RETRY_MILLIS)
            }
            catch (_: InterruptedException)
            {
                return
            }
        }
    }

    private fun openNetworkScan()
    {
        if (!connected || status?.running == true || busy) return
        startActivityForResult(Intent(this, ScanActivity::class.java), REQUEST_SCAN)
    }

    private fun toggleCapture()
    {
        if (!connected || busy) return
        if (status?.running == true)
        {
            runAction(getString(R.string.stopping)) { repository.stop() }
            return
        }

        val network = selectedNetwork ?: run {
            binding.outputTextView.text = getString(R.string.select_network_first)
            return
        }
        runAction(getString(R.string.starting)) {
            repository.selectNetwork(network)
            repository.start()
        }
    }

    private fun loadHandshakes()
    {
        if (!connected || busy) return
        setBusy(true)
        thread(name = "airtools-handshakes") {
            try
            {
                val (_, entries) = repository.handshakes()
                val text = if (entries.isEmpty()) {
                    getString(R.string.no_handshakes)
                } else {
                    entries.joinToString("\n\n") { entry ->
                        getString(
                            R.string.handshake_row,
                            entry.essid.ifEmpty { getString(R.string.hidden_network) },
                            entry.bssid,
                            entry.frameCount,
                            entry.file
                        )
                    }
                }
                runOnUiThread {
                    binding.outputTextView.text = text
                    setBusy(false)
                }
            }
            catch (_: IOException)
            {
                runOnUiThread {
                    connected = false
                    status = null
                    renderConnectionState(ConnectionState.UNAVAILABLE)
                    binding.outputTextView.text = getString(R.string.operation_failed)
                    setBusy(false)
                }
            }
            catch (_: Throwable)
            {
                runOnUiThread {
                    binding.outputTextView.text = getString(R.string.operation_failed)
                    setBusy(false)
                }
            }
        }
    }

    private fun runAction(label: String, action: () -> Any)
    {
        if (busy) return
        setBusy(true)
        binding.outputTextView.text = getString(R.string.action_progress, label)
        thread(name = "airtools-action") {
            try
            {
                action()
                val current = repository.status()
                runOnUiThread {
                    connected = true
                    status = current
                    renderConnectionState(ConnectionState.CONNECTED)
                    binding.outputTextView.text = getString(R.string.ok)
                    setBusy(false)
                }
            }
            catch (_: IOException)
            {
                runOnUiThread {
                    connected = false
                    status = null
                    renderConnectionState(ConnectionState.UNAVAILABLE)
                    binding.outputTextView.text = getString(R.string.operation_failed)
                    setBusy(false)
                }
            }
            catch (_: Throwable)
            {
                runOnUiThread {
                    binding.outputTextView.text = getString(R.string.operation_failed)
                    setBusy(false)
                }
            }
        }
    }

    private fun renderConnectionState(state: ConnectionState)
    {
        val (textRes, colorRes) = when (state)
        {
            ConnectionState.CONNECTED -> R.string.status_connected to R.color.status_connected
            ConnectionState.CONNECTING -> R.string.status_connecting to R.color.status_connecting
            ConnectionState.UNAVAILABLE -> R.string.status_server_unavailable to R.color.status_disconnected
        }
        binding.connectionStatusTextView.setText(textRes)
        binding.connectionStatusIndicator.backgroundTintList = ColorStateList.valueOf(getColor(colorRes))
    }

    private fun renderSelectedNetwork()
    {
        val network = selectedNetwork
        binding.selectedNetworkTextView.text = if (network == null) {
            getString(R.string.no_network_selected)
        } else {
            getString(R.string.network_details, displayEssid(network), network.bssid, network.channel)
        }
    }

    private fun displayEssid(network: WifiNetwork): String =
        if (network.essid == "<hidden/unknown>") getString(R.string.hidden_network) else network.essid

    private fun renderControls()
    {
        val running = status?.running == true
        binding.startStopButton.text = getString(if (running) R.string.stop else R.string.start)
        binding.scanButton.isEnabled = connected && !running && !busy
        binding.startStopButton.isEnabled = connected && !busy && (running || selectedNetwork != null)
        binding.handshakesButton.isEnabled = connected && !busy
        binding.aireplayTestButton.isEnabled = connected && !busy
    }

    private fun setBusy(value: Boolean)
    {
        busy = value
        binding.progressBar.visibility = if (value) View.VISIBLE else View.GONE
        renderControls()
    }

    private fun saveSelectedNetwork(network: WifiNetwork)
    {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString("bssid", network.bssid)
            .putString("essid", network.essid)
            .putInt("channel", network.channel)
            .apply()
    }

    private fun loadSelectedNetwork(): WifiNetwork?
    {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val bssid = prefs.getString("bssid", null) ?: return null
        val channel = prefs.getInt("channel", 0)
        if (channel !in 1..14) return null
        return WifiNetwork(
            bssid,
            channel,
            0,
            0,
            0,
            prefs.getString("essid", "<hidden/unknown>") ?: "<hidden/unknown>"
        )
    }

    private enum class ConnectionState
    {
        CONNECTED,
        CONNECTING,
        UNAVAILABLE
    }

    companion object
    {
        private const val REQUEST_SCAN = 100
        private const val PREFS = "airtools"
        private const val CONNECTION_RETRY_MILLIS = 1000L
    }
}