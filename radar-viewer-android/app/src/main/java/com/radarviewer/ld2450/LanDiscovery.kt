package com.radarviewer.ld2450

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

/**
 * Finds the ESP32 on the home network: it advertises "_ld2450._tcp" over mDNS, with its device id
 * (e.g. "ld2450-35f4") as the service name. Callbacks arrive on a system thread.
 */
class LanDiscovery(context: Context, private val onFound: (deviceId: String, host: String) -> Unit) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var listener: NsdManager.DiscoveryListener? = null

    fun start() {
        stop()
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                @Suppress("DEPRECATION")  // resolveService is fine for one-off lookups
                nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        @Suppress("DEPRECATION")
                        val host = info.host?.hostAddress ?: return
                        if (host.contains(':')) return  // prefer IPv4; the ESP32 announces both
                        onFound(info.serviceName, host)
                    }
                })
            }
        }
        listener = l
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            listener = null
        }
    }

    fun stop() {
        val l = listener ?: return
        listener = null
        try {
            nsd.stopServiceDiscovery(l)
        } catch (_: Exception) {
        }
    }

    companion object {
        const val SERVICE_TYPE = "_ld2450._tcp."
    }
}
