package dev.dav.socksforward.util

import android.os.SystemClock
import java.net.Inet4Address
import java.net.NetworkInterface

/** A device address together with the interface it belongs to (e.g. wlan0). */
data class LocalAddress(
    val address: String,
    val interfaceName: String,
)

object NetworkAddresses {

    private const val CACHE_TTL_MS = 5_000L

    @Volatile
    private var cachedAt = 0L

    @Volatile
    private var cached: List<LocalAddress> = emptyList()

    /**
     * Best effort list of local IPv4 addresses for the "connect from LAN" hint.
     * Enumeration walks every interface, so the result is cached briefly: the
     * UI refreshes it on every stats tick without hammering netlink.
     */
    fun localIpv4(): List<String> = localIpv4Detailed().map { it.address }

    /** Same list, but each address carries its interface name for labels. */
    fun localIpv4Detailed(): List<LocalAddress> {
        val now = SystemClock.elapsedRealtime()
        if (now - cachedAt < CACHE_TTL_MS) return cached
        val fresh = enumerate()
        cached = fresh
        cachedAt = now
        return fresh
    }

    private fun enumerate(): List<LocalAddress> = runCatching {
        NetworkInterface.getNetworkInterfaces()
            .toList()
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { interface_ ->
                interface_.inetAddresses.toList().asSequence()
                    .filterIsInstance<Inet4Address>()
                    .mapNotNull { it.hostAddress }
                    .filterNot { it.startsWith("169.254.") }
                    .map { LocalAddress(it, interface_.name) }
            }
            .distinctBy { it.address }
            .toList()
    }.getOrDefault(emptyList())
}
