package dev.dav.socksforward.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dev.dav.socksforward.R
import dev.dav.socksforward.core.model.ConnectionInfo
import dev.dav.socksforward.core.model.ConnectionState
import dev.dav.socksforward.databinding.ItemConnectionBinding
import dev.dav.socksforward.util.formatBytes

class ConnectionsAdapter :
    ListAdapter<ConnectionInfo, ConnectionsAdapter.Holder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemConnectionBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    class Holder(private val binding: ItemConnectionBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ConnectionInfo) {
            val context = binding.root.context
            binding.targetText.text = item.target.ifEmpty { "…" }
            binding.clientText.text = item.clientAddress
            binding.bytesText.text = context.getString(
                R.string.connection_bytes,
                formatBytes(item.bytesDown),
                formatBytes(item.bytesUp),
            )
            binding.stateText.text = context.getString(
                when (item.state) {
                    ConnectionState.ACTIVE -> R.string.connection_active
                    ConnectionState.CONNECTING -> R.string.connection_connecting
                    ConnectionState.FAILED -> R.string.connection_failed
                },
            )
            binding.stateText.setTextColor(
                ContextCompat.getColor(
                    context,
                    when (item.state) {
                        ConnectionState.ACTIVE -> R.color.status_running
                        ConnectionState.CONNECTING -> R.color.status_connecting
                        ConnectionState.FAILED -> R.color.status_error
                    },
                ),
            )
        }
    }

    private object Diff : DiffUtil.ItemCallback<ConnectionInfo>() {
        override fun areItemsTheSame(oldItem: ConnectionInfo, newItem: ConnectionInfo): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: ConnectionInfo, newItem: ConnectionInfo): Boolean =
            oldItem == newItem
    }
}
