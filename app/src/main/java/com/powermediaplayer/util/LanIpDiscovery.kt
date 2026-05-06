package com.powermediaplayer.util

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Returns the phone's first non-loopback IPv4 address (typically the
 * Wi-Fi interface). The Cast receiver lives on a separate device on the
 * same Wi-Fi LAN and reaches our embedded HTTP relay via this address.
 *
 * Returns null if no non-loopback IPv4 is available — caller should
 * surface a clear error ("connect to Wi-Fi to cast").
 */
object LanIpDiscovery {
    fun firstWifiIpv4(): String? {
        return runCatching {
            NetworkInterface.getNetworkInterfaces()
                ?.toList()
                ?.asSequence()
                ?.filter { it.isUp && !it.isLoopback && !it.isVirtual }
                ?.flatMap { it.inetAddresses.toList().asSequence() }
                ?.filterIsInstance<Inet4Address>()
                ?.filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                ?.map { it.hostAddress }
                ?.firstOrNull()
        }.getOrNull()
    }
}
