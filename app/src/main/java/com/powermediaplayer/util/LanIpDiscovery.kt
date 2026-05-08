package com.powermediaplayer.util

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Returns the phone's first **routable LAN** IPv4 address — the one
 * the Cast receiver on the same Wi-Fi can reach.
 *
 * BUG (2026-05-09): the original implementation just took the first
 * non-loopback / non-link-local IPv4. On Android with an IPv6-only or
 * dual-stack carrier, that picked the clat464 NAT64 interface's
 * `192.0.0.x` address (RFC 7335). The Cast receiver on the LAN cannot
 * route to that address — it's local to the phone's IPv6→IPv4
 * translation pipeline. Result: relay listens on a useless IP and the
 * receiver bails fetching the URL.
 *
 * The fix walks every UP non-loopback non-virtual interface and picks
 * an IPv4 address that:
 *  1. is in a private RFC1918 LAN range
 *     (10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16), AND
 *  2. doesn't sit on the clat464 reserved 192.0.0.0/29 block
 *     (192.0.0.0..192.0.0.7).
 *
 * Wi-Fi interfaces are typically named `wlan0` / `wlan1`; the loop
 * sorts them ahead of `radio0`, `rmnet*` etc. so a phone with both
 * Wi-Fi and a hotspot still picks the Wi-Fi side first.
 */
object LanIpDiscovery {
    fun firstWifiIpv4(): String? = runCatching {
        val ifaces = NetworkInterface.getNetworkInterfaces()
            ?.toList()
            ?.filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .orEmpty()
            // wlan first, then everything else
            .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
        for (iface in ifaces) {
            for (addr in iface.inetAddresses.toList()) {
                if (addr !is Inet4Address) continue
                if (addr.isLoopbackAddress || addr.isLinkLocalAddress) continue
                val host = addr.hostAddress ?: continue
                if (isClat464(host)) continue
                if (!isRfc1918(host)) continue
                return@runCatching host
            }
        }
        null
    }.getOrNull()

    private fun isClat464(host: String): Boolean {
        // RFC 7335 reserved /29 for clat464 internal use.
        return host.startsWith("192.0.0.")
    }

    private fun isRfc1918(host: String): Boolean {
        val parts = host.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return false
        val a = parts[0]; val b = parts[1]
        return when {
            a == 10 -> true
            a == 172 && b in 16..31 -> true
            a == 192 && b == 168 -> true
            else -> false
        }
    }
}
