package com.zerovpn.app.ui.screens

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zerovpn.app.MainActivity
import com.zerovpn.app.ui.provisioning.ProvisioningViewModel
import com.zerovpn.app.vpn.VpnViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProvisioningNavigationComposeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var store: ViewModelStore
    private lateinit var provisioning: ProvisioningViewModel

    @Before fun setUp() {
        compose.activity.getSharedPreferences("zerovpn_provisioning", Context.MODE_PRIVATE)
            .edit().clear().commit()
        store = ViewModelStore()
        provisioning = ProvisioningViewModel().also { store.put("provisioning", it) }
        compose.activity.setContent {
            ProvisioningScreen(
                snackbarHostState = SnackbarHostState(),
                onBack = {},
                viewModel = provisioning,
                vpnViewModel = VpnViewModel(compose.activity.application),
            )
        }
    }

    @After fun tearDown() {
        provisioning.cancel()
        store.clear()
    }

    @Test fun freshAppSelectZurichOpenBrowserReturnKeepsZurichAndEntersApiKeyPath() {
        compose.onNodeWithText("I already have an Oracle Cloud account").assertIsNotEnabled()
        compose.onNodeWithText("Choose your Oracle home region (required)").performClick()
        compose.onNodeWithText("Switzerland North (Zurich) - eu-zurich-1").performClick()
        compose.onNodeWithText("I already have an Oracle Cloud account").assertIsEnabled().performClick()

        compose.waitUntil(5_000) {
            compose.activity.getSharedPreferences("zerovpn_provisioning", Context.MODE_PRIVATE)
                .getString("pre_auth_selected_oracle_region", null) == "eu-zurich-1"
        }
        assertEquals("eu-zurich-1", provisioning.selectedOracleRegion.value)
        compose.onNodeWithText("Complete Oracle sign-in in your browser.").assertIsDisplayed()

        // The callback is the navigation edge used when the authenticated custom tab returns.
        provisioning.markAuthReturned()
        assertEquals("eu-zurich-1", provisioning.selectedOracleRegion.value)
    }
}
