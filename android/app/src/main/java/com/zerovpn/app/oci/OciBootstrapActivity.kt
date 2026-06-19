package com.zerovpn.app.oci

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.ui.viewinterop.AndroidView
import com.zerovpn.app.ui.theme.*
import java.security.interfaces.RSAPublicKey
import java.util.UUID

/**
 * OCI Bootstrap Spike Activity — WebView approach
 *
 * Oracle OAuth2 rejects custom-scheme redirect URIs ("invalid redirect uri").
 * The CLI uses redirect_uri=http://localhost:8181 with a local HTTP server.
 * Android can't run a localhost HTTP server, but we CAN use a WebView and
 * intercept the redirect before it tries to load localhost.
 *
 * Flow:
 * 1. Generate RSA keypair
 * 2. Build Oracle OAuth2 authorize URL with redirect_uri=http://localhost:8181
 * 3. Load it in a WebView
 * 4. User logs in to Oracle
 * 5. Oracle redirects to http://localhost:8181/?security_token=<JWT>#...
 * 6. WebViewClient intercepts the navigation, extracts the token from the URL
 * 7. Show the extracted token + user/tenancy OCIDs
 */
class OciBootstrapActivity : ComponentActivity() {

    companion object {
        private const val TAG = "OciBootstrap"
        private const val REGION = "uk-london-1"
        private const val REALM = "oraclecloud.com"
        private const val REDIRECT_URI = "http://localhost:8181"
        private const val REDIRECT_HOST = "localhost"
    }

    private var bootstrapState by mutableStateOf(BootstrapState.IDLE)
    private var statusMessage by mutableStateOf("")
    private var extractedInfo by mutableStateOf("")
    private var authUrl by mutableStateOf("")

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BootstrapScreen(
                state = bootstrapState,
                statusMessage = statusMessage,
                extractedInfo = extractedInfo,
                authUrl = authUrl,
                onStart = { startBootstrap() },
            )
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

            // 3. Build authorize URL with localhost redirect (same as CLI)
            authUrl = OciRequestSigner.buildAuthorizeUrl(
                region = REGION,
                realm = REALM,
                publicKeyJwkBase64 = jwkBase64,
                redirectUri = REDIRECT_URI,
            )

            Log.d(TAG, "Authorize URL: $authUrl")
            statusMessage = "Loading Oracle login in WebView..."
            bootstrapState = BootstrapState.WAITING_FOR_REDIRECT

        } catch (e: Exception) {
            Log.e(TAG, "Bootstrap failed", e)
            bootstrapState = BootstrapState.FAILED
            statusMessage = "Error: ${e.message}"
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    private fun OracleWebView() {
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): Boolean {
                            val url = request?.url?.toString() ?: return false
                            Log.d(TAG, "Navigation: $url")

                            // Check if this is the redirect to localhost
                            if (url.startsWith(REDIRECT_URI) || url.contains(REDIRECT_HOST)) {
                                // Extract the security token from the URL
                                // The token may be in the query (?security_token=...)
                                // or in the fragment (#security_token=...)
                                val token = OciRequestSigner.extractSecurityToken(url)
                                    ?: extractTokenFromFragment(url)

                                if (token != null) {
                                    handleToken(token)
                                    return true // Stop the WebView from loading localhost
                                }
                            }
                            return false // Let other URLs load normally
                        }
                    }
                    loadUrl(authUrl)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }

    private fun extractTokenFromFragment(url: String): String? {
        // Oracle redirects with the token in the fragment: #security_token=...
        // But the WebView may give us the full URL or just the localhost URL
        val fragment = url.substringAfter("#", "")
        val params = fragment.split("&").associate {
            val (k, v) = it.split("=", limit = 2)
            k to (v ?: "")
        }
        return params["security_token"]
    }

    private fun handleToken(token: String) {
        runOnUiThread {
            bootstrapState = BootstrapState.REDIRECT_RECEIVED
            statusMessage = "Token captured!"

            try {
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
                statusMessage = "Auth spike successful! WebView approach works."
            } catch (e: Exception) {
                extractedInfo = "Token captured but JWT decode failed: ${e.message}\n\nToken: ${token.take(200)}..."
                bootstrapState = BootstrapState.SUCCESS
                statusMessage = "Token captured (JWT decode issue)"
            }
        }
    }

    @Composable
    private fun BootstrapScreen(
        state: BootstrapState,
        statusMessage: String,
        extractedInfo: String,
        authUrl: String,
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

            when (state) {
                BootstrapState.IDLE -> {
                    Text(
                        text = "This tests the Oracle OAuth2 flow using a WebView. " +
                            "The custom-scheme redirect was rejected by Oracle, " +
                            "so we use redirect_uri=localhost and intercept it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextDim,
                    )
                    Button(
                        onClick = onStart,
                        colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    ) {
                        Text("Start Oracle Login", color = Bg)
                    }
                }

                BootstrapState.GENERATING_KEY,
                BootstrapState.OPENING_BROWSER -> {
                    CircularProgressIndicator(color = Accent)
                    Text(statusMessage, color = TextPrimary, fontFamily = FontFamily.Monospace)
                }

                BootstrapState.WAITING_FOR_REDIRECT -> {
                    Text(
                        text = "Complete the login in the WebView below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextDim,
                    )
                    OracleWebView()
                }

                BootstrapState.REDIRECT_RECEIVED,
                BootstrapState.SUCCESS,
                BootstrapState.FAILED -> {
                    Text(
                        text = "Status: $statusMessage",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (state == BootstrapState.SUCCESS) Accent else Danger,
                        fontFamily = FontFamily.Monospace,
                    )
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
                    Button(
                        onClick = { finish() },
                        colors = ButtonDefaults.buttonColors(containerColor = Surface),
                    ) {
                        Text("Close", color = TextPrimary)
                    }
                }
            }
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