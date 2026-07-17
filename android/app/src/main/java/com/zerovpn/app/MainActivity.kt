package com.zerovpn.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zerovpn.app.oci.OciAuthReturn
import com.zerovpn.app.ui.navigation.NavGraph
import com.zerovpn.app.ui.theme.ZeroVpnTheme

class MainActivity : ComponentActivity() {
    private var openDiagnostics by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openDiagnostics = intent?.action == ACTION_VIEW_DIAGNOSTICS
        enableEdgeToEdge()
        setContent {
            ZeroVpnTheme {
                NavGraph(
                    openDiagnostics = openDiagnostics,
                    onDiagnosticsOpened = { openDiagnostics = false },
                )
            }
        }
        OciAuthReturn.handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == ACTION_VIEW_DIAGNOSTICS) {
            openDiagnostics = true
        }
        OciAuthReturn.handleIntent(intent)
    }

    companion object {
        const val ACTION_VIEW_DIAGNOSTICS = "com.zerovpn.app.action.VIEW_DIAGNOSTICS"
    }
}
