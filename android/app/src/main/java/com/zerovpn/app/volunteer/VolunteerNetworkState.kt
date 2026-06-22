package com.zerovpn.app.volunteer

sealed interface VolunteerNetworkState {
    data object Idle : VolunteerNetworkState
    data object StartingTor : VolunteerNetworkState
    data class BootstrappingTor(val progress: Int? = null) : VolunteerNetworkState
    data object SocksReady : VolunteerNetworkState
    data object TestingSocks : VolunteerNetworkState
    data object Ready : VolunteerNetworkState
    data object Stopping : VolunteerNetworkState
    data object Stopped : VolunteerNetworkState
    data class Failed(
        val message: String,
        val throwableClass: String? = null,
    ) : VolunteerNetworkState
}
