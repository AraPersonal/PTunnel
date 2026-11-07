package com.example.vpn

import com.example.model.VpnConnectionState
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Process-wide connection state shared between the VpnService and the UI.
 */
object VpnRuntime {
    val state = MutableStateFlow(VpnConnectionState.DISCONNECTED)
    val message = MutableStateFlow<String?>(null)
    val connectedAtMillis = MutableStateFlow<Long?>(null)

    fun set(newState: VpnConnectionState, statusMessage: String? = null) {
        state.value = newState
        when (newState) {
            VpnConnectionState.CONNECTED -> connectedAtMillis.value = System.currentTimeMillis()
            VpnConnectionState.DISCONNECTED -> connectedAtMillis.value = null
            VpnConnectionState.CONNECTING -> Unit
        }
        if (statusMessage != null) message.value = statusMessage
    }

    fun clearMessage() {
        message.value = null
    }
}
