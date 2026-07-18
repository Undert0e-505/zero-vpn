package com.zerovpn.app.ui.provisioning

import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.work.Configuration
import androidx.work.WorkManager
import com.zerovpn.app.ZeroVpnApp
import com.zerovpn.app.chat.retry.CapacityRetryMode
import com.zerovpn.app.chat.retry.CapacityRetryRepository
import com.zerovpn.app.chat.retry.CapacityRetryState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = ZeroVpnApp::class, sdk = [35])
class AuthoritativeRegionFlowTest {
    private val app get() = RuntimeEnvironment.getApplication() as ZeroVpnApp
    private val prefs get() = app.getSharedPreferences("zerovpn_provisioning", Context.MODE_PRIVATE)
    private lateinit var store: ViewModelStore
    private lateinit var viewModel: ProvisioningViewModel

    @Before fun setUp() {
        runCatching { WorkManager.getInstance(app) }.getOrElse {
            WorkManager.initialize(app, Configuration.Builder().build())
        }
        prefs.edit().clear().commit()
        store = ViewModelStore()
        viewModel = ProvisioningViewModel().also { store.put("region-flow", it) }
        viewModel.initPrefs(app)
    }

    @After fun tearDown() {
        viewModel.cancel()
        store.clear()
        prefs.edit().clear().commit()
    }

    @Test fun cleanInstallRequiresSelectionAndCannotBeginBrowserAuth() {
        assertTrue(viewModel.state.value is ProvisioningState.RegionSelectionRequired)
        viewModel.startProvisioning(app)
        assertTrue(viewModel.state.value is ProvisioningState.RegionSelectionRequired)
        assertNull(viewModel.currentPhase.value)
        assertTrue(viewModel.events.value.isEmpty())
    }

    @Test fun zurichIsCommittedBeforeForegroundAuthenticationAndRestoredOnRecreation() {
        viewModel.selectOracleRegion("eu-zurich-1")
        viewModel.startProvisioning(app)

        assertEquals("eu-zurich-1", prefs.getString("selected_oracle_region", null))
        assertEquals("eu-zurich-1", prefs.getString("pre_auth_selected_oracle_region", null))
        assertFalse(viewModel.events.value.any { it.message.contains("us-ashburn-1") && it.message.contains("candidate", true) })

        viewModel.cancel()
        store.clear()
        store = ViewModelStore()
        viewModel = ProvisioningViewModel().also { store.put("region-return", it) }
        viewModel.initPrefs(app)
        viewModel.markAuthReturned()
        assertEquals("eu-zurich-1", viewModel.selectedOracleRegion.value)
    }

    @Test fun staleTerminalCapacitySessionDoesNotOverrideFreshSetup() {
        val repository = CapacityRetryRepository(prefs)
        val old = repository.createSession(
            candidateId = "candidate:old",
            mode = CapacityRetryMode.INITIAL_PRIVATE_CHAT,
            sourceExitId = null,
            compartmentOcid = "ocid1.tenancy.oc1..old",
        )
        repository.replaceSession(old.copy(state = CapacityRetryState.FAILED_TERMINAL))

        viewModel.prepareNewProvisioningFlow()
        assertTrue(viewModel.state.value is ProvisioningState.RegionSelectionRequired)
        assertFalse(viewModel.events.value.any { it.message.contains("capacity retry session is unavailable", true) })
    }

    @Test fun emptyResourceLedgerNeverOffersCleanup() {
        assertFalse(viewModel.hasCloudResourcesToCleanup())
    }
}
