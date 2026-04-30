package com.aman.browser.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Continuously monitors VPN status using ConnectivityManager.NetworkCallback.
 *
 * If a VPN is active, [vpnActive] emits `true` and the app blocks all browsing.
 * The check cannot be disabled by the user.
 */
class VpnDetector(context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _vpnActive = MutableStateFlow(false)
    val vpnActive: StateFlow<Boolean> = _vpnActive.asStateFlow()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(
            network: Network,
            caps: NetworkCapabilities,
        ) {
            updateVpnState()
        }

        override fun onAvailable(network: Network) {
            updateVpnState()
        }

        override fun onLost(network: Network) {
            updateVpnState()
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    fun start() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            connectivityManager.registerNetworkCallback(request, networkCallback)
        } catch (e: Exception) {
            Log.e("VpnDetector", "registerNetworkCallback failed", e)
        }
        // Evaluate immediately
        updateVpnState()
    }

    fun stop() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) { /* already unregistered */ }
    }

    // ── Detection logic ───────────────────────────────────────────────────────
    private fun updateVpnState() {
        val active = isVpnCurrentlyActive()
        if (_vpnActive.value != active) {
            Log.i("VpnDetector", "VPN state changed → active=$active")
            _vpnActive.value = active
        }
    }

    /**
     * Returns true if ANY active network is a VPN or lacks NOT_VPN capability.
     * Checks all networks, not just the default one, to handle split-tunnel VPNs.
     */
    fun isVpnCurrentlyActive(): Boolean {
        val networks = connectivityManager.allNetworks
        for (network in networks) {
            val caps = connectivityManager.getNetworkCapabilities(network) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) return true
        }
        return false
    }
}
