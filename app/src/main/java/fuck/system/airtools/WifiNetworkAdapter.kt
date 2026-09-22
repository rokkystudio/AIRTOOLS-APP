package fuck.system.airtools

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import fuck.system.airtools.databinding.ItemWifiNetworkBinding
import fuck.system.airtools.device.WifiNetwork

class WifiNetworkAdapter(private val context: Context) : BaseAdapter()
{
    private val inflater = LayoutInflater.from(context)
    private var items: List<WifiNetwork> = emptyList()

    fun submit(networks: List<WifiNetwork>)
    {
        items = networks
        notifyDataSetChanged()
    }

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): WifiNetwork = items[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View
    {
        val binding = if (convertView == null) {
            ItemWifiNetworkBinding.inflate(inflater, parent, false)
        } else {
            ItemWifiNetworkBinding.bind(convertView)
        }
        val network = getItem(position)
        binding.networkNameTextView.text = if (network.essid == "<hidden/unknown>") {
            context.getString(R.string.hidden_network)
        } else {
            network.essid
        }
        binding.networkBssidTextView.text = network.bssid
        binding.networkMetaTextView.text = context.getString(
            R.string.network_meta,
            network.channel
        )
        val signal = network.signalDbm
        if (signal != null) {
            val percent = signalPercent(signal)
            binding.networkSignalTextView.text = context.getString(R.string.signal_dbm, signal, percent)
            binding.networkSignalProgressBar.progress = percent
            binding.networkSignalProgressBar.visibility = View.VISIBLE
        } else {
            binding.networkSignalTextView.text = context.getString(R.string.signal_unknown)
            binding.networkSignalProgressBar.progress = 0
            binding.networkSignalProgressBar.visibility = View.INVISIBLE
        }
        return binding.root
    }

    private fun signalPercent(dbm: Int): Int = ((dbm + 100).coerceIn(0, 70) * 100 / 70)

}
