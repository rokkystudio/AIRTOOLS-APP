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
    override fun isEnabled(position: Int): Boolean = getItem(position).online

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View
    {
        val binding = if (convertView == null) {
            ItemWifiNetworkBinding.inflate(inflater, parent, false)
        } else {
            ItemWifiNetworkBinding.bind(convertView)
        }
        val network = getItem(position)
        binding.root.alpha = if (network.online) 1.0f else 0.62f
        binding.networkNameTextView.text = if (network.essid == "<hidden/unknown>") {
            context.getString(R.string.hidden_network)
        } else {
            network.essid
        }
        binding.networkBssidTextView.text = network.bssid
        binding.networkMetaTextView.text = context.getString(R.string.network_meta, network.channel)

        val level = WifiSignalLevel.from(network)
        binding.networkSignalImageView.setImageResource(level.drawableRes)
        binding.networkSignalTextView.text = when
        {
            !network.online -> context.getString(R.string.signal_offline)
            network.signalDbm != null -> context.getString(
                R.string.signal_dbm,
                network.signalDbm,
                signalPercent(network.signalDbm)
            )
            else -> context.getString(R.string.signal_unknown)
        }
        binding.networkSignalImageView.contentDescription = binding.networkSignalTextView.text
        return binding.root
    }
}