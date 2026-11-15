package dev.akhil.freebuff.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/** Connectivity facts. Online features check this before claiming to be online. */
object NetworkMonitor {

    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun isMetered(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return true
        val caps = cm.getNetworkCapabilities(network) ?: return true
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    fun isUnmetered(context: Context): Boolean = isOnline(context) && !isMetered(context)

    fun label(context: Context): String = when {
        !isOnline(context) -> "Offline"
        isMetered(context) -> "Mobile data"
        else -> "Wi-Fi"
    }
}

/** Simple callback used by screens that want to react to connectivity changes. */
class ConnectivityWatcher(
    private val context: Context,
    private val onChange: (online: Boolean) -> Unit,
) {
    private val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            onChange(NetworkMonitor.isOnline(context))
        }

        override fun onLost(network: Network) {
            onChange(NetworkMonitor.isOnline(context))
        }
    }

    fun start() {
        runCatching {
            manager?.registerDefaultNetworkCallback(callback)
        }
    }

    fun stop() {
        runCatching { manager?.unregisterNetworkCallback(callback) }
    }
}
