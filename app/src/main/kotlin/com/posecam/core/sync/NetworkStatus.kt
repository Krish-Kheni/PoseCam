package com.posecam.core.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Whether the current network satisfies a [SyncPolicy]; used only to label "Waiting for Wi-Fi". */
class NetworkStatus(context: Context) {
    private val connectivity = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /** Emits whenever connectivity changes (and once on subscription), so "waiting for Wi-Fi" stays live. */
    fun changes(): Flow<Unit> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(Unit) }
            override fun onLost(network: Network) { trySend(Unit) }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { trySend(Unit) }
        }
        trySend(Unit)
        connectivity.registerDefaultNetworkCallback(callback)
        awaitClose { runCatching { connectivity.unregisterNetworkCallback(callback) } }
    }

    /** True on an unmetered network (typically Wi-Fi); false on mobile data, a metered hotspot, or no network. */
    fun isUnmetered(): Boolean = satisfies(SyncPolicy.WIFI_ONLY)

    fun satisfies(policy: SyncPolicy): Boolean {
        val capabilities = connectivity.activeNetwork?.let { connectivity.getNetworkCapabilities(it) } ?: return false
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        return when (policy) {
            SyncPolicy.WIFI_ONLY -> capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            SyncPolicy.ANY_NETWORK -> true
            SyncPolicy.MANUAL_ONLY -> true
        }
    }
}
