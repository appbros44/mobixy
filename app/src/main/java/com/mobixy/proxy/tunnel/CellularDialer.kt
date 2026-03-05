package com.mobixy.proxy.tunnel

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference

internal class CellularDialer(private val appContext: Context) {

    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val cellularNetworkRef = AtomicReference<Network?>(null)

    suspend fun ensureCellularNetwork(timeoutMs: Long = 10_000): Network = withContext(Dispatchers.Main) {
        cellularNetworkRef.get()?.let { return@withContext it }

        val deferred = CompletableDeferred<Network>()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!deferred.isCompleted) {
                    cellularNetworkRef.set(network)
                    deferred.complete(network)
                }
            }

            override fun onUnavailable() {
                if (!deferred.isCompleted) {
                    deferred.completeExceptionally(IOException("Cellular network unavailable"))
                }
            }
        }

        connectivityManager.requestNetwork(request, callback)

        try {
            kotlinx.coroutines.withTimeout(timeoutMs) {
                deferred.await()
            }
        } finally {
            try {
                connectivityManager.unregisterNetworkCallback(callback)
            } catch (_: Exception) {
            }
        }
    }

    suspend fun dial(host: String, port: Int, timeoutMs: Int = 10_000): Socket = withContext(Dispatchers.IO) {
        val network = ensureCellularNetwork(timeoutMs = timeoutMs.toLong())
        val socket = network.socketFactory.createSocket(host, port)
        socket.soTimeout = timeoutMs
        socket
    }
}
