package dev.amatv.web

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket

/** Lifecycle of the phone configuration web server. */
internal enum class PhoneServerPhase { STOPPED, STARTING, READY, FAILED }

internal data class PhoneServerEndpoint(
    val phase: PhoneServerPhase = PhoneServerPhase.STOPPED,
    val port: Int = 0,
    val address: String = "",
    val networkConnected: Boolean = false,
)

/**
 * Bind and retain the listener; a separate availability probe would race other apps.
 * 8081 is reserved for a future diagnostic stream.
 */
internal object PhoneServerSocket {
    private val candidates = listOf(8080) + (8082..8090) + 0

    fun bind(open: (Int) -> ServerSocket = { ServerSocket(it) }): ServerSocket {
        var last: java.net.BindException? = null
        for (port in candidates) {
            try {
                return open(port)
            } catch (e: java.net.BindException) {
                last = e
                if (port == 0) throw e
            }
        }
        throw last ?: java.net.BindException("No phone configuration port available")
    }
}

internal data class PhoneLanAddress(val host: String = "", val connected: Boolean = false)
internal data class PhoneLanCandidate(
    val key: String, val wifi: Boolean, val ethernet: Boolean,
    val vpn: Boolean, val addresses: List<InetAddress>,
)

/** Only physical LAN (Wi-Fi / Ethernet, non-VPN) IPv4 addresses are advertised. */
internal fun selectPhoneLan(candidates: List<PhoneLanCandidate>, defaultKey: String?): PhoneLanAddress {
    val physical = candidates.filter { (it.wifi || it.ethernet) && !it.vpn }
    val usable = physical.mapNotNull { candidate ->
        val host = candidate.addresses.filterIsInstance<Inet4Address>()
            .filter {
                !it.isAnyLocalAddress && !it.isLoopbackAddress && !it.isMulticastAddress &&
                    !it.isLinkLocalAddress && (it.address.getOrNull(0)?.toInt() ?: 0) != 0 &&
                    it.hostAddress != "255.255.255.255"
            }
            .mapNotNull { it.hostAddress }.sorted().firstOrNull()
        host?.let { candidate to it }
    }
    val chosen = usable.firstOrNull { it.first.key == defaultKey }
        ?: usable.sortedWith(
            compareBy<Pair<PhoneLanCandidate, String>> { !it.first.ethernet }
                .thenBy { it.first.key }.thenBy { it.second }
        ).firstOrNull()
    return PhoneLanAddress(chosen?.second.orEmpty(), physical.isNotEmpty())
}

/** Discovers the TV's LAN address via ConnectivityManager callbacks. */
internal class PhoneNetworkAddress(
    context: Context,
    private val changed: (PhoneLanAddress) -> Unit,
) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val networks = mutableMapOf<Network, Snapshot>()
    private var defaultNetwork: Network? = null
    private var started = false

    private data class Snapshot(val caps: NetworkCapabilities?, val links: LinkProperties?)

    private val physicalCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            synchronized(this) { networks[network] = Snapshot(null, null) }
            publish()
        }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            synchronized(this) {
                val cur = networks[network] ?: Snapshot(null, null)
                networks[network] = cur.copy(caps = caps)
            }
            publish()
        }
        override fun onLinkPropertiesChanged(network: Network, links: LinkProperties) {
            synchronized(this) {
                val cur = networks[network] ?: Snapshot(null, null)
                networks[network] = cur.copy(links = links)
            }
            publish()
        }
        override fun onLost(network: Network) {
            synchronized(this) { networks.remove(network) }
            publish()
        }
    }

    private val defaultCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            defaultNetwork = network
            publish()
        }
        override fun onLost(network: Network) {
            if (defaultNetwork == network) defaultNetwork = null
            publish()
        }
    }

    fun start() {
        if (started) return
        started = true
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        runCatching { cm.registerNetworkCallback(request, physicalCallback) }
        runCatching { cm.registerDefaultNetworkCallback(defaultCallback) }
        publish()
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { cm.unregisterNetworkCallback(physicalCallback) }
        runCatching { cm.unregisterNetworkCallback(defaultCallback) }
    }

    private fun publish() {
        val candidates = synchronized(this) {
            networks.map { (network, snap) ->
                val caps = snap.caps ?: runCatching { cm.getNetworkCapabilities(network) }.getOrNull()
                val links = snap.links ?: runCatching { cm.getLinkProperties(network) }.getOrNull()
                PhoneLanCandidate(
                    key = network.toString(),
                    wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
                    ethernet = caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true,
                    vpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true ||
                        caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == false,
                    addresses = links?.linkAddresses?.map { it.address }.orEmpty(),
                )
            }
        }
        changed(selectPhoneLan(candidates, defaultNetwork?.toString()))
    }
}
