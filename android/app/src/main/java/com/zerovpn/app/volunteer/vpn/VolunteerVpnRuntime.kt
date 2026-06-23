package com.zerovpn.app.volunteer.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object VolunteerVpnRuntime {
    private val _state = MutableStateFlow<VolunteerVpnState>(VolunteerVpnState.Idle)
    val state: StateFlow<VolunteerVpnState> = _state.asStateFlow()

    private val _diagnostics = MutableStateFlow(VolunteerVpnDiagnostics())
    val diagnostics: StateFlow<VolunteerVpnDiagnostics> = _diagnostics.asStateFlow()

    fun update(state: VolunteerVpnState, diagnostics: VolunteerVpnDiagnostics) {
        _state.value = state
        _diagnostics.value = diagnostics
    }

    fun updateDiagnostics(transform: (VolunteerVpnDiagnostics) -> VolunteerVpnDiagnostics) {
        _diagnostics.value = transform(_diagnostics.value)
    }
}
