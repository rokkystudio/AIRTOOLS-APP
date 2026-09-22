package fuck.system.airtools

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import fuck.system.airtools.databinding.ActivityScanBinding
import fuck.system.airtools.device.AirtoolsRepository
import fuck.system.airtools.device.WifiNetwork
import java.io.IOException
import kotlin.concurrent.thread

class ScanActivity : ThemedActivity()
{
    private lateinit var binding: ActivityScanBinding
    private lateinit var repository: AirtoolsRepository
    @Volatile private var active = false
    @Volatile private var scanStarted = false
    private var networks: List<WifiNetwork> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?)
    {
        super.onCreate(savedInstanceState)
        repository = AirtoolsRepository(applicationContext)
        binding = ActivityScanBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupTopBar(binding.topBar, getString(R.string.select_wifi_network))
        binding.networksListView.setOnItemClickListener { _, _, position, _ ->
            networks.getOrNull(position)?.let(::selectNetwork)
        }
    }

    override fun onResume()
    {
        super.onResume()
        active = true
        binding.scanProgressBar.visibility = View.VISIBLE
        thread(name = "airtools-network-scan") { scanLoop() }
    }

    override fun onPause()
    {
        active = false
        if (scanStarted)
        {
            scanStarted = false
            thread(name = "airtools-scan-stop") {
                try
                {
                    repository.stopNetworkScan()
                }
                catch (_: Throwable)
                {
                }
            }
        }
        super.onPause()
    }

    private fun scanLoop()
    {
        while (active)
        {
            try
            {
                val status = repository.status()
                if (!status.scanningNetworks)
                {
                    repository.startNetworkScan()
                }
                scanStarted = true
                val current = repository.networks()
                runOnUiThread {
                    if (active) renderNetworks(current)
                }
            }
            catch (_: IOException)
            {
                runOnUiThread {
                    if (active) {
                        binding.scanProgressBar.visibility = View.GONE
                        binding.scanStatusTextView.text = getString(R.string.status_server_unavailable)
                    }
                }
            }
            catch (_: Throwable)
            {
                runOnUiThread {
                    if (active) {
                        binding.scanProgressBar.visibility = View.GONE
                        binding.scanStatusTextView.text = getString(R.string.scan_failed)
                    }
                }
            }
            try
            {
                Thread.sleep(SCAN_REFRESH_MILLIS)
            }
            catch (_: InterruptedException)
            {
                return
            }
        }
    }

    private fun renderNetworks(current: List<WifiNetwork>)
    {
        networks = current
        binding.scanProgressBar.visibility = View.GONE
        binding.scanStatusTextView.text = if (current.isEmpty()) {
            getString(R.string.no_networks_yet)
        } else {
            getString(R.string.networks_found, current.size)
        }
        val rows = current.map { network ->
            getString(R.string.network_row, displayEssid(network), network.bssid, network.channel)
        }
        binding.networksListView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, rows)
    }

    private fun displayEssid(network: WifiNetwork): String =
        if (network.essid == "<hidden/unknown>") getString(R.string.hidden_network) else network.essid

    private fun selectNetwork(network: WifiNetwork)
    {
        if (!active) return
        active = false
        binding.scanStatusTextView.text = getString(R.string.selecting_network, displayEssid(network))
        thread(name = "airtools-network-select") {
            try
            {
                if (scanStarted) repository.stopNetworkScan()
                scanStarted = false
                runOnUiThread {
                    setResult(RESULT_OK, Intent().apply {
                        putExtra(EXTRA_BSSID, network.bssid)
                        putExtra(EXTRA_CHANNEL, network.channel)
                        putExtra(EXTRA_ESSID, network.essid)
                    })
                    finish()
                }
            }
            catch (_: IOException)
            {
                active = true
                runOnUiThread {
                    binding.scanStatusTextView.text = getString(R.string.status_server_unavailable)
                }
            }
            catch (_: Throwable)
            {
                active = true
                runOnUiThread {
                    binding.scanStatusTextView.text = getString(R.string.operation_failed)
                }
            }
        }
    }

    companion object
    {
        const val EXTRA_BSSID = "bssid"
        const val EXTRA_CHANNEL = "channel"
        const val EXTRA_ESSID = "essid"
        private const val SCAN_REFRESH_MILLIS = 1000L
    }
}
