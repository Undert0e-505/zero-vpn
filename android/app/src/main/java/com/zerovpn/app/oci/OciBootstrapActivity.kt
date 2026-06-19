package com.zerovpn.app.oci

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.zerovpn.app.ui.theme.*
import java.security.interfaces.RSAPublicKey

/**
 * OCI Bootstrap Spike Activity
 *
 * This is a minimal test to verify that the Oracle OAuth2 flow works
 * from an Android app using Chrome Custom Tab with a custom redirect URI.
 *
 * Flow:
 * 1. Generate RSA keypair
 * 2. Build Oracle OAuth2 authorize URL with public key as JWK
 * 3. Open in Chrome Custom Tab
 * 4. User logs in to Oracle
 * 5. Oracle redirects to zerovpn://auth/callback with security token
 * 6. We capture the token and extract user/tenancy OCIDs
 *
 * If this works, the full bootstrap (API key upload + VM provisioning)
 * can be built on top of it.
 */
class OciBootstrapActivity : ComponentActivity() {

    companion object {
        private const val TAG = "OciBootstrap"
        private const val REGION = "uk-london-1"
        private const val REALM = "oraclecloud.com"
        private const val REDIRECT_URI = "zerovpn://auth/callback"
    }

    private var bootstrapState by mutableStateOf(BootstrapState.IDLE)
    private var statusMessage by mutableStateOf("")
    private var capturedToken by mutableStateOf<String?>(null)
    private var extractedInfo by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BootstrapScreen(
                state = bootstrapState,
                statusMessage = statusMessage,
                extractedInfo = extractedInfo,
                onStart = { startBootstrap() },
            )
        }

        // Check if we were launched via the redirect URI
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        Log.d(TAG, "Received redirect: $data")

        if (data.toString().startsWith(REDIRECT_URI)) {
            bootstrapState = BootstrapState.REDIRECT_RECEIVED
            statusMessage = "Redirect received! Extracting token..."

            val token = OciRequestSigner.extractSecurityToken(data.toString())
            if (token != null) {
                capturedToken = token
                val claims = OciRequestSigner.decodeJwt(token)
                val userOcid = claims["sub"] as? String ?: "unknown"
                val tenancyOcid = claims["tenant"] as? String ?: "unknown"

                extractedInfo = """
                    ✅ Security token captured!
                    
                    User OCID: $userOcid
                    Tenancy OCID: $tenancyOcid
                    
                    Token length: ${token.length} chars
                    Token prefix: ${token.take(50)}...
                    
                    Next steps (not implemented in spike):
                    1. Upload public key as API key
                    2. Use API key signing for all calls
                    3. Provision VCN + subnet + VM
                    4. Return public IP to caller
                """.trimIndent()

                bootstrapState = BootstrapState.SUCCESS
                statusMessage = "Auth spike successful!"
            } else {
                extractedInfo = "❌ No security_token found in redirect URL.\n\nFull URL: $data"
                bootstrapState = BootstrapState.FAILED
                statusMessage = "Token extraction failed"
            }
        }
    }

    private fun startBootstrap() {
        bootstrapState = BootstrapState.GENERATING_KEY
        statusMessage = "Generating RSA keypair..."

        try {
            // 1. Generate RSA keypair
            val keyPair = OciRequestSigner.generateKeyPair()
            val publicKey = keyPair.public as RSAPublicKey

            // 2. Encode public key as JWK
            val jwk = OciRequestSigner.publicKeyToJwk(publicKey)
            val jwkBase64 = OciRequestSigner.base64UrlEncode(jwk)

            // 3. Build authorize URL
            val authUrl = OciRequestSigner.buildAuthorizeUrl(
                region = REGION,
                realm = REALM,
                publicKeyJwkBase64 = jwkBase64,
                redirectUri = REDIRECT_URI,
            )

            Log.d(TAG, "Authorize URL: $authUrl")
            statusMessage = "Opening Oracle login in Chrome Custom Tab..."
            bootstrapState = BootstrapState.OPENING_BROWSER

            // 4. Open in Chrome Custom Tab
            val uri = Uri.parse(authUrl)
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)

            statusMessage = "Waiting for Oracle login redirect..."
            bootstrapState = BootstrapState.WAITING_FOR_REDIRECT

        } catch (e: Exception) {
            Log.e(TAG, "Bootstrap failed", e)
            bootstrapState = BootstrapState.FAILED
            statusMessage = "Error: ${e.message}"
        }
    }
}

enum class BootstrapState {
    IDLE,
    GENERATING_KEY,
    OPENING_BROWSER,
    WAITING_FOR_REDIRECT,
    REDIRECT_RECEIVED,
    SUCCESS,
    FAILED,
}

@Composable
private fun BootstrapScreen(
    state: BootstrapState,
    statusMessage: String,
    extractedInfo: String,
    onStart: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "OCI Bootstrap Spike",
            style = MaterialTheme.typography.headlineMedium,
            color = TextPrimary,
        )

        Text(
            text = "This is a development test to verify the Oracle OAuth2 redirect flow works from Android.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextDim,
        )

        if (state == BootstrapState.IDLE) {
            Button(
                onClick = onStart,
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
            ) {
                Text("Start Oracle Login", color = Bg)
            }
        }

        if (statusMessage.isNotEmpty()) {
            Text(
                text = "Status: $statusMessage",
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary,
                fontFamily = FontFamily.Monospace,
            )
        }

        if (extractedInfo.isNotEmpty()) {
            HorizontalDivider(color = Surface)
            Text(
                text = extractedInfo,
                style = MaterialTheme.typography.bodySmall,
                color = TextPrimary,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (state == BootstrapState.WAITING_FOR_REDIRECT) {
            CircularProgressIndicator(color = Accent)
            Text(
                text = "Complete the login in the browser tab. You'll be redirected back here automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = TextDim,
            )
        }
    }
}