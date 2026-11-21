package dev.dav.socksforward.util

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkAddresses {

    /** Best effort list of local IPv4 addresses for the "connect from LAN" hint. */
    fun localIpv4(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces()
            .toList()
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
            .filterNot { it.startsWith("169.254.") }
            .distinct()
            .toList()
    }.getOrDefault(emptyList())
}
