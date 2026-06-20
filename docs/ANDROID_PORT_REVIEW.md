# Android Port vs Python Reference — Detailed Comparison

## Summary

The Codex Android port is **structurally faithful** to the Python state machine but has **several bugs** that need fixing before it can work end-to-end.

## Phase-by-phase comparison

### 1. JWK Generation (OciRequestSigner.kt vs Python build_jwk)

| Aspect | Python (validated) | Android (Codex) | Match? |
|---|---|---|---|
| Fields | `kty`, `n`, `e`, `kid` | `kty`, `n`, `e`, `kid` | ✅ |
| kid value | `"Ignored"` | `"Ignored"` | ✅ |
| alg/use | absent | absent | ✅ |
| n encoding | base64url, padding stripped | `Base64.getUrlEncoder().withoutPadding()` | ✅ |
| e encoding | base64url, padding stripped | `Base64.getUrlEncoder().withoutPadding()` | ✅ |
| Sign byte | stripped via `bytes_from_int` | stripped via `copyOfRange(1, size)` | ✅ |
| Outer b64 | `base64.urlsafe_b64encode` (with padding) | `Base64.getUrlEncoder()` (with padding) | ✅ |

**JWK is correct.**

### 2. Fingerprint (OciRequestSigner.kt vs Python md5_fingerprint)

| Aspect | Python | Android | Match? |
|---|---|---|---|
| Algorithm | MD5 | MD5 | ✅ |
| Input | DER public key | `publicKey.encoded` (DER) | ✅ |
| Format | colon-separated hex | colon-separated hex | ✅ |

**Fingerprint is correct.**

### 3. Browser Auth (authenticate() vs do_browser_auth())

| Aspect | Python | Android | Match? |
|---|---|---|---|
| Port | 8181 | 8181 | ✅ |
| Redirect URI | `http://localhost:8181` | `http://localhost:8181` | ✅ |
| OAuth params | action, client_id, response_type, nonce, scope, public_key, redirect_uri | same | ✅ |
| Client ID | `iaas_console` | `iaas_console` | ✅ |
| Response type | `token id_token` | `token id_token` | ✅ |
| Token capture | JS page → `/token?security_token=...` | same JS, NanoHTTPD captures | ✅ |
| JWT decode | `stc.get_jwt()` → `sub`, `tenant` | `decodeJwt()` → `sub`, `tenant` | ✅ |
| Realm | `REALMS[REGION_REALMS[region]]` → `oraclecloud.com` | `realms[region]` → `oraclecloud.com` (after fix) | ✅ (after fix) |

**BUT:** `getRealmForRegion()` in OciRequestSigner.kt still has `?: "oc1"` as fallback. Should be `?: "oraclecloud.com"`. Minor but should be fixed.

**Auth is correct (after realm fix).**

### 4. Preflight (preflight() vs do_preflight())

| Aspect | Python | Android | Match? |
|---|---|---|---|
| Home region | `list_region_subscriptions` → `is_home_region` | same API, `optBoolean("is_home_region")` | ✅ |
| UK check | `home_region in ("uk-london-1", "uk-cardiff-1")` | same list | ✅ |
| Dev mode override | `state.get("is_dev_mode")` | `isDevMode` param | ✅ |
| API key count | `list_api_keys` → check len >= 3 | same API, `JSONArray.length() >= 3` | ✅ |
| Shape check | `list_shapes` → `VM.Standard.E2.1.Micro` | **MISSING** | ⚠️ |

**Shape availability check is missing in Android.** Python checks for `VM.Standard.E2.1.Micro` shape availability. Android skips this. Non-critical (the shape is almost always available) but should be added for completeness.

### 5. API Key Upload (uploadApiKey() vs do_upload_api_key())

| Aspect | Python | Android | Match? |
|---|---|---|---|
| Endpoint | `POST /20160918/users/{userId}/apiKeys` | same | ✅ |
| Body | `CreateApiKeyDetails(key=pub_pem)` | `JSONObject().put("key", pubPem)` | ✅ |
| 409 handling | catch `ServiceError` status 409 | check `response.code != 409` | ✅ |
| Propagation delay | `time.sleep(5)` | `delay(5000)` | ✅ |
| PEM format | PEM with header/footer | `publicKeyToPem()` with header/footer | ✅ |

**API key upload is correct.**

### 6. Network Creation (createNetwork() vs do_provision network phase)

| Aspect | Python | Android | Match? |
|---|---|---|---|
| VCN | `cidr_block`, `compartment_id`, `display_name`, `dns_label` | same fields | ✅ |
| Security list | egress + ingress (SSH 22/tcp, WG 51820/udp) | same rules | ✅ |
| Subnet | `cidr_block`, `compartment_id`, `display_name`, `vcn_id`, `security_list_ids`, `dhcp_options_id` | same | ✅ |
| IGW | `compartment_id`, `display_name`, `is_enabled`, `vcn_id` | same | ✅ |
| Route table | update with `0.0.0.0/0` → IGW | same | ✅ |
| Wait for state | `oci.wait_until(...)` | `delay(2000)` (no polling) | ⚠️ |

**Wait for state issue:** Python uses `oci.wait_until()` to poll until VCN/subnet/IGW are AVAILABLE before proceeding. Android just does a 2-second delay. This is **not reliable** — resources may not be ready yet. Should poll the API for state.

### 7. VM Launch (launchVm() vs do_provision VM phase)

| Aspect | Python | Android | Match? |
|---|---|---|---|
| AD | `list_availability_domains` | same | ✅ |
| Image | Ubuntu 22.04, fallback 24.04 | same | ✅ |
| Shape | `VM.Standard.E2.1.Micro` | same | ✅ |
| Source details | `InstanceSourceViaImageDetails` | `JSONObject` with `imageId`, `bootVolumeSizeInGBs`, `sourceType` | ✅ |
| VNIC | `CreateVnicDetails(assign_public_ip=True)` | `JSONObject` with `assignPublicIp: true` | ✅ |
| SSH key | via `metadata.ssh_authorized_keys` | same | ✅ |
| Wait for RUNNING | `oci.wait_until(..., max_wait_seconds=300)` | poll loop, 30×10s | ✅ |
| Get public IP | `list_vnic_attachments` → `get_vnic` | same | ✅ |

**VM launch is correct.**

### 8. SSH + WireGuard (setupWireGuard() vs do_provision WG phase)

| Aspect | Python | Android | Match? |
|---|---|---|---|
| SSH client | system `ssh` command | JSch library | ✅ (adapted) |
| SSH key | project ed25519 key on disk | ed25519 key generated in-app via JSch | ✅ (adapted) |
| Key to VM | pre-existing `oci-zerovpn-exit-01` key | generated key, public key in VM metadata | ✅ (adapted) |
| apt install | `;` not `&&`, verify with `which wg` | same | ✅ |
| Keygen | `umask 077; wg genkey; wg pubkey` | same | ✅ |
| setup-wg.sh | scp + `sed -i 's/\r$//'` + bash | cat heredoc + `sed -i 's/\r$//'` + bash | ✅ |
| Key extraction | parse `PEER_PRIVATE_KEY=` / `SERVER_PUBLIC_KEY=` | same | ✅ |
| Client config | saved to `secrets/client.conf` | returned as `ProvisionResult.clientConfig` | ✅ (adapted) |
| SSH key temp file | N/A | written to `cacheDir`, deleted after | ✅ |

**SSH/WireGuard is correct** (adapted for Android — generates its own SSH key instead of using the project key).

### 9. Destroy (destroy() vs do_destroy())

| Aspect | Python | Android | Match? |
|---|---|---|---|
| Instance | terminate + wait for TERMINATED | terminate + poll loop | ✅ |
| Route table | clear rules | clear rules | ✅ |
| IGW | delete | delete | ✅ |
| Subnet | delete + wait | delete + delay | ⚠️ |
| Security list | delete | delete | ✅ |
| VCN | delete | delete | ✅ |
| Error handling | try/except per resource | try/catch per resource | ✅ |
| Order | instance → routes → IGW → subnet → SL → VCN | same | ✅ |

### 10. State Persistence

| Aspect | Python | Android | Match? |
|---|---|---|---|
| State file | `setup-state.json` (no secrets) | SharedPreferences (no secrets) | ✅ |
| Resource IDs | persisted | persisted | ✅ |
| Secrets | NOT persisted | NOT persisted (in-memory only) | ✅ |
| Resume | `--resume` flag | ViewModel checks saved state | ✅ |

## BUGS TO FIX

### Bug 1: Realm fallback in OciRequestSigner.kt (minor)
**File:** `OciRequestSigner.kt`, line 261
**Issue:** `getRealmForRegion()` fallback is `"oc1"` instead of `"oraclecloud.com"`
**Fix:** Change `?: "oc1"` to `?: "oraclecloud.com"`

### Bug 2: No wait-for-state on resource creation (moderate)
**File:** `OciProvisioner.kt`, network creation
**Issue:** After creating VCN/subnet/IGW, the code does `delay(2000)` instead of polling the API for `lifecycle_state == "AVAILABLE"`. Resources may not be ready in 2 seconds.
**Fix:** Add polling loops like the Python's `oci.wait_until()`:
```kotlin
// Wait for VCN to be AVAILABLE
for (i in 1..30) {
    delay(2000)
    val vcnResp = ociGet(auth, iaasHost, "/20160918/vcns/${rids.vcnId}")
    if (vcnResp.optString("lifecycleState") == "AVAILABLE") break
}
```

### Bug 3: Missing shape availability check (minor)
**File:** `OciProvisioner.kt`, preflight
**Issue:** Python checks if `VM.Standard.E2.1.Micro` shape is available in the region. Android skips this.
**Fix:** Add a `list_shapes` API call in preflight (non-fatal on failure).

### Bug 4: NanoHTTPD token capture may not work (critical — needs testing)
**File:** `OciProvisioner.kt`, `authenticate()`
**Issue:** The NanoHTTPD `serve()` method checks `session.parameters` for `security_token`. But NanoHTTPD may parse query params differently than expected. The JS on the redirect page sends `GET /token?security_token=...` — NanoHTTPD should parse this into `session.parameters["security_token"]`. This needs verification.
**Risk:** If NanoHTTPD doesn't parse the query params correctly, the token won't be captured.

### Bug 5: Chrome Custom Tab may not close after auth (moderate)
**File:** `OciProvisioner.kt`, `authenticate()`
**Issue:** After the token is captured, the code calls `server.stop()` but doesn't close the Chrome Custom Tab. The user may be left on the "OK" page in the browser.
**Fix:** No direct API to close Custom Tabs. Could redirect the localhost server to a "You can close this now" page before stopping.

### Bug 6: Subnet delete wait (minor)
**File:** `OciProvisioner.kt`, `destroy()`
**Issue:** After deleting subnet, only `delay(3000)` instead of polling for TERMINATED state. VCN deletion may fail if subnet isn't fully terminated.
**Fix:** Add polling loop for subnet state.

## CORRECT ASPECTS

- JWK format matches CLI exactly (kid:Ignored, no alg/use, strip padding)
- MD5 fingerprint correct
- OAuth2 URL construction correct (after realm fix)
- All OCI API endpoints correct
- Security token signing (ST- prefix) correct
- Network resource creation order correct
- VM launch parameters correct
- WireGuard setup script matches validated bash
- SSH approach adapted correctly for Android (JSch + in-app key gen)
- Destroy order correct
- No secrets in persisted state
- Event model matches spec