package dev.dav.socksforward.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

object VpnStatus {

    /**
     * True when the system currently has a VPN network. Our own sockets are
     * routed into it by the kernel, so this is also the answer to "will the
     * proxied traffic go through the VPN?".
     */
    fun isVpnActive(context: Context): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        return runCatching {
            manager.allNetworks.any { network ->
                manager.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
        }.getOrDefault(false)
    }
}
