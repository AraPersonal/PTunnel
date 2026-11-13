package com.example.vpn.core

import android.content.Context
import android.provider.Settings
import android.util.Base64
import android.util.Log
import go.Seq
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray

/**
 * Production TunnelCore implementation using the embedded Xray/libv2ray engine.
 * Handles VLESS Reality, mKCP with FinalMask salamander, VMess WS, Trojan, and UDP traffic for online games like Once Human.
 */
class XrayTunnelCore(
    context: Context,
    private val listener: TunnelCoreListener
) : TunnelCore {

    private val controller: CoreController

    init {
        try {
            Seq.setContext(context.applicationContext)
            val assetPath = context.getDir("assets", Context.MODE_PRIVATE).absolutePath
            val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "ptunnel"
            val base64Key = Base64.encodeToString(
                androidId.toByteArray(Charsets.UTF_8).copyOf(32),
                Base64.NO_PADDING or Base64.URL_SAFE
            )
            Libv2ray.initCoreEnv(assetPath, base64Key)
        } catch (t: Throwable) {
            Log.w("XrayTunnelCore", "initCoreEnv warning: " + t.message)
        }

        controller = Libv2ray.newCoreController(object : CoreCallbackHandler {
            override fun startup(): Long {
                listener.onCoreStarted()
                return 0
            }

            override fun shutdown(): Long {
                listener.onCoreStopped()
                return 0
            }

            override fun onEmitStatus(code: Long, message: String?): Long {
                listener.onCoreStatus(code, message)
                return 0
            }
        })
    }

    override val isRunning: Boolean
        get() = controller.isRunning

    override fun startLoop(config: String, tunFd: Int) {
        controller.startLoop(config, tunFd)
    }

    override fun stopLoop() {
        try {
            controller.stopLoop()
        } catch (t: Throwable) {
            Log.w("XrayTunnelCore", "stopLoop warning: " + t.message)
        }
    }

    override fun measureDelay(url: String): Long {
        return try {
            controller.measureDelay(url)
        } catch (_: Throwable) {
            -1L
        }
    }

    override fun uplinkBytes(): Long = 0L

    override fun downlinkBytes(): Long = 0L
}
