package com.zerovpn.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.zerovpn.app.oci.OciAuthReturn
import com.zerovpn.app.ui.navigation.NavGraph
import com.zerovpn.app.ui.theme.ZeroVpnTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ZeroVpnTheme {
                NavGraph()
            }
        }
        OciAuthReturn.handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        OciAuthReturn.handleIntent(intent)
    }
}
