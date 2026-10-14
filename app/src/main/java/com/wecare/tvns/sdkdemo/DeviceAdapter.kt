package com.wecare.tvns.sdkdemo

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.wecare.tvns.sdkdemo.databinding.ItemDeviceBinding
import com.wecare.tvns.sdk.model.TvnsScanResult

/**
 * 扫描结果列表。
 *
 * 扫描回调会把同一台设备反复报上来（每收到一次广播就是一次），所以按 MAC 归并：
 * 没见过的插一行，见过的只刷新信号强度，否则列表几秒钟就会被同一台设备刷满。
 */
class DeviceAdapter(private val onPick: (TvnsScanResult) -> Unit) :
    RecyclerView.Adapter<DeviceAdapter.Holder>() {

    class Holder(val binding: ItemDeviceBinding) : RecyclerView.ViewHolder(binding.root)

    private val items = ArrayList<TvnsScanResult>()
    private val indexByMac = HashMap<String, Int>()
    private var connectedMac: String? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.binding.tvName.text = item.name
        holder.binding.tvMac.text = item.mac
        holder.binding.tvRssi.text = "${item.rssi}dBm"
        holder.binding.tvTag.visibility = if (item.mac == connectedMac) View.VISIBLE else View.GONE
        holder.binding.root.setOnClickListener { onPick(item) }
    }

    fun upsert(result: TvnsScanResult) {
        val existing = indexByMac[result.mac]
        if (existing == null) {
            indexByMac[result.mac] = items.size
            items.add(result)
            notifyItemInserted(items.size - 1)
        } else {
            items[existing] = result
            notifyItemChanged(existing)
        }
    }

    fun clear() {
        val size = items.size
        items.clear()
        indexByMac.clear()
        if (size > 0) {
            notifyItemRangeRemoved(0, size)
        }
    }

    /** 只有涉及的两行会变，不必整体刷新 —— 那会让正在滚动的列表跳一下。 */
    fun setConnectedMac(mac: String?) {
        if (connectedMac == mac) {
            return
        }
        val affected = ArrayList<Int>(2)
        connectedMac?.let { indexByMac[it]?.let(affected::add) }
        mac?.let { indexByMac[it]?.let(affected::add) }
        connectedMac = mac
        affected.forEach { notifyItemChanged(it) }
    }
}
