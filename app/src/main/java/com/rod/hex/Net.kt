package com.rod.hex

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.util.concurrent.CopyOnWriteArrayList

/** Sait si Hex est en ligne, et prévient quand ça change. */
class Net(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)!!
    private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()
    @Volatile var online: Boolean = check()
        private set

    init {
        val req = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        cm.registerNetworkCallback(req, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = update()
            override fun onLost(network: Network) = update()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = update()
        })
    }

    private fun check(): Boolean {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun update() {
        val now = check()
        if (now != online) {
            online = now
            listeners.forEach { it(now) }
        }
    }

    fun listen(l: (Boolean) -> Unit) { listeners.add(l) }
}
