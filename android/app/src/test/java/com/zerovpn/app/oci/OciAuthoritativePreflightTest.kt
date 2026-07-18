package com.zerovpn.app.oci

import com.zerovpn.app.ZeroVpnApp
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.UnknownHostException
import java.net.InetAddress
import java.security.KeyPairGenerator

@RunWith(RobolectricTestRunner::class)
@Config(application = ZeroVpnApp::class, sdk = [35])
class OciAuthoritativePreflightTest {
    @Test fun zurichDnsFailureIsTransientAndNeverFallsBackOrUploadsAKey() = runBlocking {
        val requestedHosts = mutableListOf<String>()
        val client = OkHttpClient.Builder().dns(failingDns(requestedHosts)).build()
        val provisioner = OciProvisioner(
            context = RuntimeEnvironment.getApplication(),
            region = "us-ashburn-1",
            isDevMode = true,
            httpClientOverride = client,
        )
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val auth = OciProvisioner.AuthResult(
            securityToken = "browser-token",
            privateKey = keys.private,
            keyPair = keys,
            userOcid = "ocid1.user.oc1..test",
            tenancyOcid = "ocid1.tenancy.oc1..test",
            fingerprint = "00:11:22",
            authBootstrapRegion = "us-ashburn-1",
        )

        val result = provisioner.preflight(auth, "eu-zurich-1", "user-selected")

        assertFalse(result.success)
        assertTrue(result.isTransientNetworkFailure)
        assertTrue(result.error!!.startsWith("TRANSIENT_NETWORK_FAILURE"))
        assertEquals("eu-zurich-1", result.homeRegion)
        assertEquals("us-ashburn-1", result.authBootstrapRegion)
        assertEquals(listOf("identity.eu-zurich-1.oci.oraclecloud.com"), requestedHosts)
        assertFalse(requestedHosts.any { it.contains("ashburn") || it.contains("amsterdam") || it.contains("madrid") })
        assertFalse(provisioner.events.replayCache.any { it.message.contains("Uploading API key") })
    }

    @Test fun missingAuthorityStopsBeforeDnsAndApiKeyGeneration() = runBlocking {
        val requestedHosts = mutableListOf<String>()
        val client = OkHttpClient.Builder().dns(failingDns(requestedHosts)).build()
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val auth = OciProvisioner.AuthResult(
            "token", keys.private, keys, "user", "tenancy", "fingerprint", "us-ashburn-1",
        )
        val provisioner = OciProvisioner(RuntimeEnvironment.getApplication(), "us-ashburn-1", false, client)

        val result = provisioner.preflight(auth, null)

        assertEquals("REGION_SELECTION_REQUIRED", result.error)
        assertTrue(requestedHosts.isEmpty())
        assertFalse(provisioner.events.replayCache.any { it.message.contains("Uploading API key") })
    }

    private fun failingDns(requestedHosts: MutableList<String>): Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            requestedHosts += hostname
            throw UnknownHostException(hostname)
        }
    }
}
