package com.zerovpn.app.ui.provisioning

import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.work.Configuration
import androidx.work.WorkManager
import com.zerovpn.app.ZeroVpnApp
import com.zerovpn.app.chat.retry.CapacityRetryMode
import com.zerovpn.app.chat.retry.CapacityRetryRepository
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(application = ZeroVpnApp::class, sdk = [35])
class ProvisioningViewModelRetryStateTest {
    @Test fun activeRetryMakesPrivateChatAuthoritativeAcrossReopenAndToggleAttempt() {
        val app = RuntimeEnvironment.getApplication()
        runCatching { WorkManager.getInstance(app) }.getOrElse {
            WorkManager.initialize(app, Configuration.Builder().build())
            WorkManager.getInstance(app)
        }
        val prefs = app.getSharedPreferences(
            CapacityRetryRepository.PREFS_NAME,
            Context.MODE_PRIVATE,
        )
        prefs.edit().clear().commit()
        prefs.edit().putBoolean("private_chat_requested", false).commit()
        val now = Instant.now()
        CapacityRetryRepository(
            prefs,
            Clock.fixed(now, ZoneOffset.UTC),
        ).createSession(
            candidateId = "candidate:reopen",
            mode = CapacityRetryMode.INITIAL_PRIVATE_CHAT,
            sourceExitId = null,
            compartmentOcid = "ocid1.tenancy.oc1..example",
        )

        val store = ViewModelStore()
        val viewModel = ProvisioningViewModel()
        store.put("retry-state", viewModel)
        try {
            viewModel.initPrefs(app)
            assertTrue(viewModel.privateChatRequested.value)

            viewModel.setPrivateChatRequested(false)
            assertTrue(viewModel.privateChatRequested.value)
        } finally {
            store.clear()
        }
    }
}
