package com.zerovpn.app.vpn

data class ProviderSwitchDiagnostics(
    val selectedExitId: String? = null,
    val activeExitId: String? = null,
    val activeProviderType: String? = null,
    val selectedProviderType: String? = null,
    val switchingTargetExitId: String? = null,
    val lastProviderSwitchStartedAt: Long? = null,
    val lastProviderSwitchCompletedAt: Long? = null,
    val lastProviderSwitchError: String? = null,
)
