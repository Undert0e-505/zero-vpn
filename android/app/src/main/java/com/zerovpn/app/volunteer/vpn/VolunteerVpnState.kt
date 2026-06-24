package com.zerovpn.app.volunteer.vpn

sealed interface VolunteerVpnState {
    data object Idle : VolunteerVpnState
    data object PermissionNeeded : VolunteerVpnState
    data object StartingTor : VolunteerVpnState
    data object StartingVpn : VolunteerVpnState
    data object Running : VolunteerVpnState
    data object Stopping : VolunteerVpnState
    data object Stopped : VolunteerVpnState
    data class Failed(val message: String) : VolunteerVpnState
}
