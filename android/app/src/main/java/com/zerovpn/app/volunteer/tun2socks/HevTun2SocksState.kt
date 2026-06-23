package com.zerovpn.app.volunteer.tun2socks

sealed interface HevTun2SocksState {
    data object Idle : HevTun2SocksState
    data object Starting : HevTun2SocksState
    data object Running : HevTun2SocksState
    data object Stopping : HevTun2SocksState
    data object Stopped : HevTun2SocksState
    data class Failed(val message: String) : HevTun2SocksState
}
