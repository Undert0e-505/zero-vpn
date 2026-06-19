# Codex Task: Port Real OCI Provisioning to Android

## Objective

Replace the **simulated** provisioning pipeline in the Android app with the **real** OCI provisioning flow validated in the Python state machine. The app must drive the entire flow: browser auth → preflight → network → VM → WireGuard → client config.

## Reference Implementation

**Read these files first:**

1. `D:/dev/zero-vpn/harness/state_machine.py` — the validated Python state machine (707 lines). This is the source of truth for the flow, phases, events, and state transitions.
2. `D:/dev/zero-vpn/harness/setup-wg.sh` — the WireGuard server setup script (runs on the VM via SSH).
3. `D:/dev/zero-vpn/android/app/src/main/java/com/zerovpn/app/oci/OciRequestSigner.kt` — existing Kotlin OCI signing utility (JWK generation, request signing, JWT decode). **Note: the JWK format here is WRONG — it has `alg` and `use` fields and uses padding. The correct format (matching the CLI) is: `{"kty":"RSA","n":"...","e":"...","kid":"Ignored"}` with padding STRIPPED from n and e. Fix this.**
4. `D:/dev/zero-vpn/android/app/src/main/java/com/zerovpn/app/ui/provisioning/ProvisioningViewModel.kt` — current simulated ViewModel. Replace `simulatePipeline()` with real calls.
5. `D:/dev/zero-vpn/android/app/src/main/java/com/zerovpn/app/ui/provisioning/ProvisioningEvent.kt` — event model (already good).
6. `D:/dev/zero-vpn/android/app/src/main/java/com/zerovpn/app/ui/screens/ProvisioningScreen.kt` — UI (already good, keep as-is).

## What to Build

### 1. Fix OciRequestSigner.kt

Update the JWK generation to match the validated CLI format:
- Fields: `kty`, `n`, `e`, `kid` — **NO `alg`, NO `use`**
- `kid` value: `"Ignored"` (literal string)
- `n` and `e`: base64url **WITHOUT padding** (strip `=` characters)
- Use `Base64.getUrlEncoder().withoutPadding()` for n and e
- The outer base64url of the JWK JSON: use `Base64.getUrlEncoder()` (with padding is fine, CLI does this too)

Also add:
- `md5Fingerprint(publicKey: RSAPublicKey): String` — MD5 of DER public key, colon-separated hex (16 bytes). This is the OCI API key fingerprint format.
- Fix `decodeJwt` to use `org.json.JSONObject` instead of regex.

### 2. OciProvisioner.kt (new file)

A class that runs the provisioning pipeline and emits `ProvisioningEvent` objects via a Kotlin Flow. This is the Kotlin port of `do_provision()` from the Python state machine.

```kotlin
class OciProvisioner(
    private val context: Context,
    private val region: String,
) {
    val events: SharedFlow<ProvisioningEvent>

    suspend fun authenticate(): AuthResult  // browser auth via Custom Tab + localhost listener
    suspend fun preflight(auth: AuthResult): PreflightResult
    suspend fun provision(auth: AuthResult, preflight: PreflightResult): ProvisionResult
    suspend fun destroy(resourceIds: ResourceIds, auth: AuthResult): Boolean
}
```

#### Auth flow (Chrome Custom Tab + localhost listener)

The Python uses `http://localhost:8181` as redirect_uri with a local HTTP server. On Android:

1. Generate RSA 2048-bit keypair (use `KeyPairGenerator.getInstance("RSA")`)
2. Build JWK (using fixed `OciRequestSigner`)
3. Build authorize URL with `redirect_uri=http://localhost:8181`
4. Start a `NanoHTTPD` server on port 8181 inside the app
5. Open Chrome Custom Tab with the authorize URL
6. User logs in to Oracle in the browser
7. Oracle redirects to `http://localhost:8181` — the NanoHTTPD server catches it
8. Serve the same JS redirect page as the Python (extract fragment, send to `/token`)
9. Capture the security token from `/token?security_token=...`
10. Close the server and Custom Tab
11. Decode JWT to get user OCID, tenancy OCID
12. Return `AuthResult(token, privateKey, userOcid, tenancyOcid, fingerprint)`

**NanoHTTPD dependency:** Add `org.nanohttpd:nanohttpd:2.3.1` to `build.gradle.kts`.

**Chrome Custom Tab dependency:** Add `androidx.browser:browser:1.8.0`.

**INTERNET permission is already in the manifest.**

#### Preflight

Port `do_preflight()` from Python:
1. Find home region via `list_region_subscriptions` API call
2. If home region is UK and not dev mode → return failure with UK warning
3. Check Always Free shape availability (optional, non-fatal)
4. Check API key count (max 3)

Use OkHttp for raw HTTP calls with OCI request signing (the `OciRequestSigner.buildAuthHeader` method). No OCI SDK — just raw HTTP + manual signing.

**OkHttp dependency:** Add `com.squareup.okhttp3:okhttp:4.12.0`.

#### Provisioning

Port `do_provision()` from Python. Each step emits a `ProvisioningEvent`:

1. **API key upload** — POST to OCI API with security token signer
2. **Network creation** — POST to create VCN, security list, subnet, IGW, route table
3. **VM launch** — POST to launch instance, poll for RUNNING, get public IP
4. **SSH + WireGuard** — This is the hard part on Android. Use `JSch` library for SSH (no `ssh` command on Android). Install WireGuard via apt, generate keys, run setup script, extract peer config.

**JSch dependency:** Add `com.jcraft:jsch:0.1.55` or `com.github.mwiede:jsch:0.2.17`.

**SSH key:** The project SSH key (`oci-zerovpn-exit-01`) is on the workstation, not the phone. For the Android app, generate a new SSH keypair on the phone (stored in Android Keystore), use that for the VM. The Python pipeline uses a fixed key because it runs on the workstation. The Android app should generate its own key per provisioning run and inject it via `metadata.ssh_authorized_keys`.

Actually, simpler approach: generate an ed25519 SSH keypair in the app, use the public key in the VM launch metadata, and use JSch with the private key for SSH.

### 3. Update ProvisioningViewModel.kt

Replace `simulatePipeline()` with real `OciProvisioner` calls:

```kotlin
fun startProvisioning() {
    viewModelScope.launch {
        val provisioner = OciProvisioner(context, "uk-london-1")
        // Collect events from provisioner and emit to _events
        // Handle state transitions: Running → Success/Failure
    }
}
```

Add `isDevMode` parameter (default true for now, Aaron's account is UK).

### 4. State persistence

Save setup state to `SharedPreferences` (or encrypted prefs):
- current state (enum string)
- region, user_ocid, tenancy_ocid, fingerprint, home_region
- resource_ids (as JSON object)
- public_ip, wireguard_port
- last_successful_phase, last_error
- is_dev_mode

**No secrets in SharedPreferences.** Private keys go to Android Keystore.

### 5. Pre-start screen update

Update the pre-start copy to include:
- "Oracle signup or login may be required"
- "Oracle may require card and 2FA"
- "ZeroVPN never sees your Oracle password, card, 2FA, or cookies"
- "Home region cannot be changed later"
- "UK London region: dev/test mode only. This validates the pipeline but is not a non-UK exit."
- "Setup takes 4-6 minutes"

### 6. UK region warning

If preflight detects UK home region and dev mode is on:
- Show warning screen: "Your Oracle home region is UK London. This can be used for development/testing, but not as a non-UK Always Free exit."
- Button: "Continue (dev/test mode)" or "Cancel"

### 7. Success screen update

Show:
- Public IP
- WireGuard port
- Region (with "dev/test mode" label if UK)
- "Connect Now" button (wire to WireGuard tunnel setup — for now, save client.conf and show a toast saying "Config saved, WireGuard connection coming soon")
- "Destroy Node" button (calls `provisioner.destroy()`)

### 8. Failure screen update

Show:
- Failed phase name
- Last successful phase
- Error message (user-safe, no secrets)
- "Retry" and "Cleanup" buttons

## Key Technical Details

### OCI API endpoints

All use HTTPS to `iaas.{region}.oci.oraclecloud.com` (for compute/network) or `identity.{region}.oci.oraclecloud.com` (for identity).

Key endpoints:
- List region subscriptions: `GET https://identity.{region}.oci.oraclecloud.com/20160918/tenancies/{tenancyId}/regionSubscriptions`
- List availability domains: `GET https://identity.{region}.oci.oraclecloud.com/20160918/availabilityDomains?compartmentId={tenancyId}`
- Create VCN: `POST https://iaas.{region}.oci.oraclecloud.com/20160918/vcns`
- Create security list: `POST https://iaas.{region}.oci.oraclecloud.com/20160918/securityLists`
- Create subnet: `POST https://iaas.{region}.oci.oraclecloud.com/20160918/subnets`
- Create IGW: `POST https://iaas.{region}.oci.oraclecloud.com/20160918/internetGateways`
- Update route table: `PUT https://iaas.{region}.oci.oraclecloud.com/20160918/routeTables/{rtId}`
- List images: `GET https://iaas.{region}.oci.oraclecloud.com/20160918/images?compartmentId={cid}&operatingSystem=Canonical+Ubuntu&operatingSystemVersion=22.04&shape=VM.Standard.E2.1.Micro&sortBy=TIMECREATED&sortOrder=DESC`
- Launch instance: `POST https://iaas.{region}.oci.oraclecloud.com/20160918/instances`
- Get instance: `GET https://iaas.{region}.oci.oraclecloud.com/20160918/instances/{instanceId}`
- List VNIC attachments: `GET https://iaas.{region}.oci.oraclecloud.com/20160918/vnicAttachments?compartmentId={cid}&instanceId={instId}`
- Get VNIC: `GET https://iaas.{region}.oci.oraclecloud.com/20160918/vnics/{vnicId}`
- Terminate instance: `DELETE https://iaas.{region}.oci.oraclecloud.com/20160918/instances/{instanceId}`
- Upload API key: `POST https://identity.{region}.oci.oraclecloud.com/20160918/users/{userId}/apiKeys`

### OCI request signing

For security token auth (during provisioning), the Authorization header uses:
```
Signature version="1",keyId="ST-{tenancyOcid}/{userOcid}/{fingerprint}",algorithm="rsa-sha256",headers="date (request-target) host",signature="{base64sig}"
```

The `ST-` prefix indicates security token auth. For API key auth, it's `KeyId="{tenancyOcid}/{userOcid}/{fingerprint}"` (no `ST-` prefix).

### JWT decode

The security token is a JWT. The `sub` claim is the user OCID, the `tenant` claim is the tenancy OCID. Decode by splitting on `.`, base64url-decoding the middle part, parsing as JSON.

### WireGuard setup via SSH

Use JSch to SSH into the VM (user: `ubuntu`, key: generated ed25519 key). Run the same commands as the Python pipeline:
1. `sudo apt-get update -y; sudo apt-get install -y wireguard wireguard-tools; which wg || exit 1`
2. `sudo sh -c "umask 077; wg genkey > /etc/wireguard/server.key; wg pubkey < /etc/wireguard/server.key > /etc/wireguard/server.pub"`
3. SCP the `setup-wg.sh` script (embed it as a string constant in Kotlin, write to `/tmp/setup-wg.sh` on the VM)
4. `sed -i 's/\r$//' /tmp/setup-wg.sh; bash /tmp/setup-wg.sh`
5. Parse stdout for `PEER_PRIVATE_KEY=...` and `SERVER_PUBLIC_KEY=...`
6. Build client.conf from the extracted keys + public IP

## Build

```bash
cd D:\dev\zero-vpn\android
.\gradlew.bat assembleRelease
```

Must produce a release APK (not debug — debug has `android:debuggable=true` which Google Drive blocks).

## Constraints

- **No secrets in logs, UI, or state files.** Private keys, tokens, full client configs go to Android Keystore only.
- **No OCI Java SDK.** Use raw OkHttp + manual request signing.
- **No `ssh` command.** Use JSch for SSH.
- **NanoHTTPD** for the localhost listener (not Android's built-in HTTP server).
- **Chrome Custom Tab** for browser auth (not WebView).
- **Keep the existing ProvisioningScreen.kt UI** — it's already good. Just wire real events into it.
- **Keep the existing ProvisioningEvent.kt model** — it's already good.
- **Release build must pass** — `./gradlew assembleRelease` with no errors.

## Acceptance test

1. Aaron taps "Create Oracle Free Exit" on AddExit screen
2. Pre-start warning shown with UK dev/test mode notice
3. Aaron taps "Start" → Chrome Custom Tab opens Oracle login
4. Aaron logs in (or already logged in → quick redirect)
5. App captures token, runs preflight
6. UK warning shown → "Continue (dev/test mode)"
7. Provisioning streams live events in terminal-style log
8. VM created, WireGuard installed, client config generated
9. Success screen: public IP, WireGuard port, "dev/test mode" label
10. "Destroy Node" works — tears down all resources