package com.zerovpn.app.vpn

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class VpnViewModel(application: Application) : AndroidViewModel(application) {
    private val controller = WireGuardTunnelController(application)

    val state: StateFlow<VpnConnectionState> = controller.state
    val diagnostics: StateFlow<VpnDiagnostics> = controller.diagnostics

    fun prepareVpn(): Intent? = controller.prepareVpn()

    fun markPermissionRequired(exitId: String) {
        controller.markPermissionRequired(exitId)
    }

    fun onPermissionDenied() {
        controller.onPermissionDenied()
    }

    fun fail(message: String) {
        controller.fail(message)
    }

    fun prepareDiagnostics(exit: ConfiguredExit) {
        controller.prepareDiagnostics(exit)
    }

    fun connect(exit: ConfiguredExit) {
        viewModelScope.launch {
            try {
                controller.connect(exit)
            } catch (e: Exception) {
                // State is already updated by the controller.
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            try {
                controller.disconnect()
            } catch (e: Exception) {
                // State is already updated by the controller.
            }
        }
    }

    suspend fun disconnectIfActive(exitId: String?): Boolean = controller.disconnectIfActive(exitId)
}
