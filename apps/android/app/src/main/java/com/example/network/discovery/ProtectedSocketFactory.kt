package com.example.network.discovery

import android.net.Network
import android.net.VpnService
import android.util.Log
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.lang.ref.WeakReference
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/**
 * Manages socket protection so control-plane and probing requests
 * use the underlying physical network without looping into the VPN TUN interface.
 */
object SocketProtector {
    private const val TAG = "SocketProtector"
    private var vpnServiceRef: WeakReference<VpnService>? = null
    @Volatile var activePhysicalNetwork: Network? = null

    fun register(service: VpnService) {
        vpnServiceRef = WeakReference(service)
        Log.i(TAG, "VpnService registered for socket protection")
    }

    fun unregister(service: VpnService) {
        if (vpnServiceRef?.get() === service) {
            vpnServiceRef = null
            Log.i(TAG, "VpnService unregistered from socket protection")
        }
    }

    fun protect(socket: Socket): Boolean {
        // First try binding directly to physical network if available (Android M+)
        val net = activePhysicalNetwork
        if (net != null) {
            try {
                net.bindSocket(socket)
                return true
            } catch (t: Throwable) {
                Log.w(TAG, "Network.bindSocket failed: ${t.message}")
            }
        }
        val svc = vpnServiceRef?.get()
        return if (svc != null) {
            try {
                svc.protect(socket)
            } catch (t: Throwable) {
                Log.w(TAG, "VpnService.protect(socket) failed: ${t.message}")
                false
            }
        } else {
            false
        }
    }

    fun protect(fd: Int): Boolean {
        val svc = vpnServiceRef?.get() ?: return false
        return try {
            svc.protect(fd)
        } catch (t: Throwable) {
            Log.w(TAG, "VpnService.protect(fd) failed: ${t.message}")
            false
        }
    }
}

/**
 * Custom SocketFactory that protects every created socket from entering the VPN TUN.
 */
class ProtectedSocketFactory(
    private val delegate: SocketFactory = SocketFactory.getDefault(),
    private val protect: (Socket) -> Boolean = { SocketProtector.protect(it) }
) : SocketFactory() {

    override fun createSocket(): Socket {
        val socket = delegate.createSocket()
        protect(socket)
        return socket
    }

    override fun createSocket(host: String?, port: Int): Socket {
        val socket = delegate.createSocket(host, port)
        protect(socket)
        return socket
    }

    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket {
        val socket = delegate.createSocket(host, port, localHost, localPort)
        protect(socket)
        return socket
    }

    override fun createSocket(host: InetAddress?, port: Int): Socket {
        val socket = delegate.createSocket(host, port)
        protect(socket)
        return socket
    }

    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket {
        val socket = delegate.createSocket(address, port, localAddress, localPort)
        protect(socket)
        return socket
    }
}

fun createProtectedOkHttpClient(
    dns: Dns? = null,
    connectTimeoutSeconds: Long = 5,
    readTimeoutSeconds: Long = 10
): OkHttpClient {
    val builder = OkHttpClient.Builder()
        .socketFactory(ProtectedSocketFactory())
        .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(readTimeoutSeconds, TimeUnit.SECONDS)

    if (dns != null) {
        builder.dns(dns)
    }

    return builder.build()
}
