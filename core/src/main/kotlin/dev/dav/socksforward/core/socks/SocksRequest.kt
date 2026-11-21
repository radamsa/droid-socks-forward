package dev.dav.socksforward.core.socks

import java.net.InetAddress

/**
 * Parsed CONNECT request.
 *
 * [address] is set for IP based requests and null for domain names, so the
 * caller can resolve the name on the device itself (socks5h behaviour) instead
 * of relying on the client.
 */
data class SocksRequest(
    val host: String,
    val port: Int,
    val address: InetAddress?,
) {
    val display: String
        get() = if (host.contains(':')) "[$host]:$port" else "$host:$port"
}
