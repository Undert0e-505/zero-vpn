# ZeroVPN — OCI VM Creation & Connection: Full Implementation

**Version:** 1.0  
**Date:** 2026-06-20  
**Status:** ✅ Working end-to-end (Python + Android)  
**Commits:** `bc1ff66` (Android fix), `5782ff0` (Python wire tap), `5a87dc9` (initial port)

## Overview

ZeroVPN creates an Oracle Cloud Infrastructure (OCI) Always Free VM 
pre-configured as a WireGuard VPN exit node. The entire flow — from 
browser authentication through VM creation to WireGuard configuration — 
runs without any server-side infrastructure. The user's phone talks 
directly to Oracle's API.

There are two implementations:
- **Python** (`harness/state_machine.py`) — the reference implementation, 
  uses the official OCI Python SDK
- **Android** (`android/app/src/main/java/com/zerovpn/app/oci/OciProvisioner.kt`) 
  — the production app, uses raw OkHttp + manual request signing (no OCI SDK)

Both implementations produce equivalent results: a running OCI VM with 
WireGuard configured, and a client config string for the phone to connect.

## Architecture

```
┌─────────────┐     ┌──────────────────────────────────────────┐
│  Phone app  │     │              Oracle Cloud                 │
│             │     │                                          │
│  ┌───────┐  │     │  ┌────────────┐    ┌──────────────────┐   │
│  │Auth   │──┼─────┼─▶│ OAuth2     │    │ Identity Service │   │
│  │(browser│  │     │  │ login page │    │ identity.X.oci   │   │
│  │ tab)   │  │     │  └────────────┘    │ .oraclecloud.com │   │
│  └───┬───┘  │     │         │            └──────────────────┘   │
│      │      │     │         │ token                        │   │
│      ▼      │     │         ▼                              │   │
│  ┌───────┐  │     │  ┌────────────┐    ┌──────────────────┐   │
│  │RSA kp │  │     │  │ Security    │    │ IaaS Service    │   │
│  │gen    │  │     │  │ Token (JWT)│    │ iaas.X.oracle    │   │
│  └───┬───┘  │     │  └────────────┘    │ .cloud.com       │   │
│      │      │     │         │            └──────────────────┘   │
│      ▼      │     │         │ signed req                │   │
│  ┌───────┐  │     │         ▼            ┌──────────────┐ │   │
│  │Sign + │──┼─────┼─────────────────────▶│ API calls    │ │   │
│  │HTTP   │  │     │                      │ VCN/Subnet/  │ │   │
│  └───────┘  │     │                      │ VM/etc       │ │   │
│             │     │                      └──────────────┘ │   │
│  ┌───────┐  │     │  ┌────────────┐                       │   │
│  │SSH →  │──┼─────┼─▶│ VM (Ubuntu)│                       │   │
│  │WG     │  │     │  │ WireGuard  │◀───── UDP 51820 ──────┼───│
│  └───────┘  │     │  └────────────┘                       │   │
│             │     └──────────────────────────────────────────┘
└─────────────┘
```

## The 7-Phase Pipeline

### Phase 1: Browser Auth

**Goal:** Obtain an OCI security token (JWT) by having the user log in 
to Oracle via a browser tab.

**How it works:**
1. Generate an RSA 2048-bit keypair on the device
2. Build a JWK (JSON Web Key) from the public key
3. Base64url-encode the JWK
4. Construct an OAuth2 authorize URL:
   ```
   https://login.{region}.{realm}/v1/oauth2/authorize?
     action=login
     &client_id=iaas_console
     &response_type=token id_token
     &nonce={uuid}
     &scope=openid
     &public_key={jwk_b64}
     &redirect_uri=http://localhost:8181
   ```
5. Open the URL in a browser (Chrome Custom Tab on Android, 
   `webbrowser` on Python)
6. Start a local HTTP server on port 8181 to catch the redirect
7. The browser redirect sends `#security_token=...` which JavaScript 
   extracts and sends to the local server
8. Decode the JWT to extract `sub` (user OCID) and `tenant` (tenancy OCID)

**Output:** Security token, RSA private key, user OCID, tenancy OCID, 
fingerprint (MD5 of public key DER)

**Key details:**
- The RSA keypair is used for two things: (a) signing OCI API requests 
  during provisioning, and (b) the JWK in the OAuth2 URL
- The security token is a JWT that expires (~8h). All API calls during 
  provisioning use security token auth (not API key auth)
- The JWK must strip leading `0x00` sign bytes from the modulus 
  (`BigInteger` adds them) and use base64url **without** padding
- The token is passed as `keyId="ST${token}"` in the Authorization header

### Phase 2: Preflight

**Goal:** Verify the tenancy is usable before provisioning.

**Checks:**
1. **Home region:** GET `/20160918/tenancies/{tenancy}/regionSubscriptions` 
   → find `is_home_region: true`. The home region cannot be changed later.
2. **UK region guard:** If home region is `uk-london-1` or `uk-cardiff-1`, 
   warn (dev mode allows it, production mode blocks it)
3. **API key count:** GET `/20160918/users/{user}/apiKeys` → max 3 keys 
   allowed. If at limit, user must delete one in the console first.
4. **Shape availability:** (Python only) Check that 
   `VM.Standard.E2.1.Micro` is available in the region

**Host:** `identity.{region}.oci.oraclecloud.com` (note: `.oci.` in 
the hostname — this was a bug in the Android app, see Bugs section)

### Phase 3: API Key Upload

**Goal:** Upload the RSA public key as an OCI API key. This enables 
API key auth (as opposed to security token auth) for future sessions.

**Request:** `POST /20160918/users/{userOcid}/apiKeys`

**Body:**
```json
{"key": "-----BEGIN PUBLIC KEY-----\nMIIBIjAN...\n-----END PUBLIC KEY-----\n"}
```

**Signing:** The POST is signed with security token auth. The signing 
string includes 6 headers:
```
date: {RFC1123 GMT}
(request-target): post /20160918/users/{user}/apiKeys
host: identity.{region}.oci.oraclecloud.com
content-length: {body byte count}
content-type: application/json
x-content-sha256: {base64 SHA-256 of body}
```

**After upload:** Wait 5 seconds for propagation.

**Response:** 200 (or 409 if key already exists — treated as success).

### Phase 4: Network Creation

**Goal:** Create the virtual network infrastructure for the VM.

**Resources created (in order):**

1. **VCN** (Virtual Cloud Network)
   - `POST /20160918/vcns` on `iaas.{region}.oraclecloud.com`
   - CIDR: `10.0.0.0/24`, DNS label: `zerovpn`
   - Wait for `AVAILABLE` state

2. **Security List**
   - `POST /20160918/securityLists`
   - Ingress rules:
     - TCP 22 from 0.0.0.0/0 (SSH — for provisioning, can remove later)
     - UDP 51820 from 0.0.0.0/0 (WireGuard)
   - Egress rules:
     - All protocols to 0.0.0.0/0

3. **Subnet**
   - `POST /20160918/subnets`
   - CIDR: `10.0.0.0/24`, attached to security list
   - Uses VCN default DHCP options (no need to fetch them separately 
     — this was a bug, see Bugs section)
   - Wait for `AVAILABLE` state

4. **Internet Gateway**
   - `POST /20160918/internetGateways`
   - Routes VCN traffic to the internet
   - Wait for `AVAILABLE` state

5. **Route Table**
   - `PUT /20160918/routeTables/{defaultRouteTableId}`
   - Add default route `0.0.0.0/0` → IGW

**Host:** `iaas.{region}.oraclecloud.com` (note: NO `.oci.` in this 
hostname — only the identity service has it)

### Phase 5: VM Launch

**Goal:** Launch an Always Free eligible micro instance.

**Steps:**

1. **Find availability domain**
   - `GET /20160918/availabilityDomains?compartmentId={tenancy}` on 
     `identity.{region}.oci.oraclecloud.com`
   - Returns a **raw JSON array** (not `{"items":[...]}`)
   - Take the first AD's `name`

2. **Find Ubuntu image**
   - `GET /20160918/images?compartmentId={tenancy}&operatingSystem=Canonical+Ubuntu&operatingSystemVersion=22.04&shape=VM.Standard.E2.1.Micro&sortBy=TIMECREATED&sortOrder=DESC` 
     on `iaas.{region}.oraclecloud.com`
   - Returns a **raw JSON array**
   - Fallback to 24.04 if 22.04 not found
   - Take the first (newest) image's `id`

3. **Generate SSH keypair**
   - RSA 2048-bit via JSch (`KeyPair.RSA`)
   - Public key goes into VM metadata as `ssh_authorized_keys`
   - Private key is used to SSH in for WireGuard setup
   - (Ed25519 was the original choice but Bouncy Castle dependency 
     conflicts on Android forced the switch to RSA — see Bugs section)

4. **Launch instance**
   - `POST /20160918/instances` on `iaas.{region}.oraclecloud.com`
   - Body:
     ```json
     {
       "availabilityDomain": "vwPG:UK-LONDON-1-AD-1",
       "compartmentId": "{tenancy}",
       "displayName": "zerovpn-exit-01",
       "shape": "VM.Standard.E2.1.Micro",
       "subnetId": "{subnetId}",
       "sourceDetails": {
         "imageId": "{imageId}",
         "bootVolumeSizeInGBs": 50,
         "sourceType": "image"
       },
       "createVnicDetails": {
         "subnetId": "{subnetId}",
         "assignPublicIp": true
       },
       "metadata": {
         "ssh_authorized_keys": "ssh-rsa AAAA... zerovpn-android"
       }
     }
     ```

5. **Wait for RUNNING**
   - Poll `GET /20160918/instances/{instanceId}` every 10s
   - Timeout: 5 minutes
   - States: `PROVISIONING` → `RUNNING`

6. **Get public IP**
   - `GET /20160918/vnicAttachments?compartmentId={tenancy}&instanceId={instanceId}` 
     on `iaas.{region}.oraclecloud.com`
   - Returns a **raw JSON array**
   - Extract `vnicId`, then `GET /20160918/vnics/{vnicId}` → `publicIp`
   - Retry up to 10 times × 10s if not yet assigned

### Phase 6: SSH + WireGuard Setup

**Goal:** SSH into the VM, install WireGuard, generate keys, configure 
the tunnel.

**SSH connection:**
- Username: `ubuntu` (Canonical Ubuntu image)
- Auth: public key (the RSA keypair generated in Phase 5)
- Wait: up to 10 minutes, retrying every 10 seconds
  - OCI reports `RUNNING` before cloud-init/sshd are ready
  - Typical wait: 20-30 seconds after RUNNING state
- All SSH operations must run on `Dispatchers.IO` (Android)

**WireGuard installation:**
```bash
sudo apt-get update -y; sudo apt-get install -y wireguard wireguard-tools; which wg || exit 1
```
Note: semicolons not `&&` — apt post-invoke hooks can return non-zero 
even when packages install successfully.

**Server key generation:**
```bash
sudo sh -c "umask 077; wg genkey > /etc/wireguard/server.key; wg pubkey < /etc/wireguard/server.key > /etc/wireguard/server.pub"
```

**WireGuard configuration (setup-wg.sh):**
```bash
#!/bin/bash
set -e

SERVER_KEY=$(sudo cat /etc/wireguard/server.key)

# Write wg0.conf
sudo bash -c "cat > /etc/wireguard/wg0.conf << EOF
[Interface]
PrivateKey = $SERVER_KEY
Address = 10.66.66.1/24
ListenPort = 51820
PostUp = iptables -A FORWARD -i wg0 -j ACCEPT; iptables -t nat -A POSTROUTING -o eth0 -j MASQUERADE
PostDown = iptables -D FORWARD -i wg0 -j ACCEPT; iptables -t nat -D POSTROUTING -o eth0 -j MASQUERADE
EOF"
sudo chmod 600 /etc/wireguard/wg0.conf

# Enable IP forwarding
sudo sysctl -w net.ipv4.ip_forward=1
echo 'net.ipv4.ip_forward=1' | sudo tee -a /etc/sysctl.conf

# Start WireGuard
sudo systemctl enable wg-quick@wg0
sudo systemctl start wg-quick@wg0

# Generate peer keypair
wg genkey | tee /tmp/peer.key | wg pubkey > /tmp/peer.pub
PEER_KEY=$(cat /tmp/peer.key)
PEER_PUB=$(cat /tmp/peer.pub)

# Add peer to server
sudo wg set wg0 peer "$PEER_PUB" allowed-ips 10.66.66.2/32

# Get server public key
SERVER_PUB=$(sudo cat /etc/wireguard/server.pub)

# Output (parsed by the app)
echo "PEER_PRIVATE_KEY=$PEER_KEY"
echo "SERVER_PUBLIC_KEY=$SERVER_PUB"
echo "---"
sudo wg show
```

The script is written to the VM via `cat > /tmp/setup-wg.sh << 'ENDOFSCRIPT'`, 
line endings fixed with `sed -i 's/\r$//'`, then executed with 
`bash /tmp/setup-wg.sh`.

**Output parsing:** The app reads stdout for:
- `PEER_PRIVATE_KEY=...` → client's WireGuard private key
- `SERVER_PUBLIC_KEY=...` → server's WireGuard public key

### Phase 7: Client Config + Done

**Client config generated on the device:**
```ini
[Interface]
PrivateKey = {peer_private_key}
Address = 10.66.66.2/24
DNS = 1.1.1.1

[Peer]
PublicKey = {server_public_key}
Endpoint = {public_ip}:51820
AllowedIPs = 0.0.0.0/0
PersistentKeepalive = 25
```

**Result:** Public IP, WireGuard port (51820/udp), client config string.

## OCI Request Signing

Every API call to OCI must be signed. The signing mechanism is 
HTTP Signature Authentication (RFC draft-cavage-http-signatures).

### Signing string format

**For GET/DELETE:**
```
date: {RFC1123 GMT}
(request-target): {method_lowercase} {path}
host: {hostname}
```

**For POST/PUT:**
```
date: {RFC1123 GMT}
(request-target): {method_lowercase} {path}
host: {hostname}
content-length: {body_byte_count}
content-type: application/json
x-content-sha256: {base64_sha256_of_body}
```

### Authorization header

```
Signature algorithm="rsa-sha256",
  headers="date (request-target) host [content-length content-type x-content-sha256]",
  keyId="ST${security_token}",
  signature="{base64_rsa_sha256_signature}",
  version="1"
```

For security token auth, `keyId` is `ST$` + the entire JWT token. 
For API key auth, `keyId` is `{tenancy}/{user}/{fingerprint}`.

### Critical details

- **Host must include `.oci.` for identity service:** 
  `identity.{region}.oci.oraclecloud.com` — NOT 
  `identity.{region}.oraclecloud.com`. IAAS service is 
  `iaas.{region}.oraclecloud.com` (no `.oci.`).
- **Content-Type must match exactly:** The signing string's 
  `content-type` must match what the HTTP client actually sends. 
  OkHttp appends `; charset=utf-8` to string bodies — send bytes 
  instead to avoid this.
- **Content-Length is body byte count:** Not character count. For 
  UTF-8 ASCII bodies these are the same, but the signing string must 
  use `toByteArray(Charsets.UTF_8).size`.
- **Date is RFC 1123 format in GMT:** 
  `Sat, 20 Jun 2026 05:16:30 GMT`

## Destroy / Teardown

Resources are deleted in reverse order:
1. Terminate instance → wait for `TERMINATED`
2. Clear route table rules
3. Delete internet gateway
4. Delete subnet → wait for `TERMINATED`
5. Delete security list
6. Delete VCN

All deletes are best-effort (non-fatal on error during teardown).

## Bugs Found and Fixed

### 1. Missing `.oci.` in identity host (Android)
- **Symptom:** POST to API key upload returns 401 
  "Failed to verify the HTTP(S) Signature"
- **Cause:** `identity.{region}.oraclecloud.com` instead of 
  `identity.{region}.oci.oraclecloud.com`. The signing string had 
  the wrong host, so Oracle's signature verification failed on POST 
  (GETs were more permissive).
- **Fix:** Add `.oci.` to all identity service hostnames.

### 2. Content-Type charset mismatch (Android)
- **Symptom:** POST still returns 401 after host fix
- **Cause:** OkHttp appends `; charset=utf-8` to `Content-Type` when 
  the request body is a String. The signing string had 
  `content-type: application/json` but OkHttp sent 
  `Content-Type: application/json; charset=utf-8`. Oracle reconstructs 
  the signing string from actual headers → mismatch → 401.
- **Fix:** Send `bodyBytes.toRequestBody(jsonMedia)` (bytes, not string) 
  so OkHttp doesn't add the charset.
- **Detection:** Required a network-level OkHttp interceptor to see 
  the actual wire headers. Application-level interceptors run before 
  OkHttp adds standard headers.

### 3. Bouncy Castle / Ed25519 conflict (Android)
- **Symptom:** `NoClassDefFoundError: Ed25519PrivateKeyParameters`
- **Cause:** JSch 0.2.x needs Bouncy Castle for Ed25519 key generation. 
  Adding `bcprov-jdk18on` separately caused class conflicts with JSch's 
  expectations on Android. Removing it left JSch without BC.
- **Fix:** Switch SSH key generation from Ed25519 to RSA. JSch can 
  generate RSA keypairs without Bouncy Castle. RSA keys work fine for 
  VM SSH access.

### 4. DHCP options 404 (Android)
- **Symptom:** GET `/20160918/dhcpOptions` returns 404 
  `NotAuthorizedOrNotFound`
- **Cause:** The code fetched DHCP options to get the ID for the 
  subnet creation. This call is unnecessary — OCI VCNs have default 
  DHCP options that the subnet uses automatically.
- **Fix:** Remove the DHCP options GET and the `dhcpOptionsId` field 
  from the subnet creation body.

### 5. Raw JSON array responses (Android)
- **Symptom:** `JSONArray cannot be converted to JSONObject`
- **Cause:** Three OCI endpoints return raw JSON arrays, not 
  objects with an `items` field:
  - `GET /20160918/availabilityDomains` → `[{...}, {...}]`
  - `GET /20160918/images` → `[{...}, {...}]`
  - `GET /20160918/vnicAttachments` → `[{...}]`
- **Fix:** Added `ociGetArray()` helper that parses responses as 
  `JSONArray` instead of `JSONObject`.

### 6. NetworkOnMainThreadException (Android)
- **Symptom:** JSch `session.connect()` and `sshExec()` crash with 
  `android.os.NetworkOnMainThreadException`
- **Cause:** SSH operations (socket I/O) were running on the main 
  thread. Android blocks network I/O on the main thread.
- **Fix:** Wrap `session.connect()` and all `sshExec()` calls in 
  `kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO)`.

### 7. SSH wait too short (Android)
- **Symptom:** "SSH connection failed" even though the VM was healthy
- **Cause:** The retry loop was 12 × 10s = 2 minutes. OCI reports 
  `RUNNING` before `sshd` is ready, and cloud-init can take 30+ seconds.
- **Fix:** Increase to 10 minutes with per-attempt error logging.

## File Reference

### Python (reference implementation)
| File | Purpose |
|---|---|
| `harness/state_machine.py` | Full pipeline: auth → preflight → provision → destroy |
| `harness/state_machine_capture.py` | Wire-tapped copy for debugging |
| `harness/setup-wg.sh` | WireGuard setup script (copied to VM) |
| `harness/echo_server.py` | HTTP echo server for wire-level debugging |
| `harness/python_post_capture.json` | Captured POST request/response from wire tap |
| `harness/python_all_requests.log` | All HTTP calls with timestamps |
| `harness/state_machine_capture.log` | Full wire tap log with `[WIRE TAP]` lines |
| `secrets/oci-zerovpn-exit-01` | Pre-generated Ed25519 SSH key (Python only) |

### Android (production app)
| File | Purpose |
|---|---|
| `OciProvisioner.kt` | Full pipeline: auth → preflight → provision → destroy |
| `OciRequestSigner.kt` | RSA key gen, JWK, request signing, JWT decode |
| `ProvisioningViewModel.kt` | UI state management, orchestrates OciProvisioner |
| `ProvisioningEvent.kt` | Event data class (phase, status, message) |
| `Phase.kt` | Phase enum (AUTH, API_KEY, NETWORK, VM_LAUNCH, WAIT_SSH, WIREGUARD, DONE) |
| `Status.kt` | Status enum (RUNNING, SUCCESS, WARNING, ERROR) |
| `build.gradle.kts` | OkHttp, JSch, NanoHTTPD dependencies |
| `libs.versions.toml` | Version catalog |

## Dependencies

### Python
- `oci` 2.179.0 (OCI Python SDK, uses vendored `requests`)
- `cryptography` (RSA key generation, PEM serialization)
- `requests` (HTTP client, but OCI SDK uses its own vendored copy)

### Android
- `okhttp` 4.12.0 (HTTP client)
- `jsch` 0.2.20 (mwiede fork, SSH client)
- `nanohttpd` 2.3.1 (embedded HTTP server for OAuth2 redirect)
- `androidx.browser` 1.8.0 (Chrome Custom Tabs)
- `org.json` (built-in Android JSON, not Jackson/Gson)
- **No OCI SDK** — all API calls use raw OkHttp + manual signing
- **No Bouncy Castle** — RSA SSH keys via JSch's built-in support

## Wire Tap Methodology

The wire tap was essential for finding the Content-Type charset bug. 
The approach:

1. **Python side:** Monkey-patch `oci._vendor.requests.sessions.Session.send` 
   (NOT standard `requests.Session.send` — the OCI SDK uses a vendored 
   copy) to log every request's exact headers, body, SHA256, and response.
2. **Android side:** Add an OkHttp **network interceptor** (not 
   application interceptor — network interceptors run after OkHttp adds 
   standard headers like Content-Length, Host, Connection).
3. **Compare:** Run both against Oracle, diff the wire-level headers.

The wire tap files are preserved in the repo as the golden reference for 
any future signing issues.

## OCI Hostname Reference

| Service | Hostname pattern | Example |
|---|---|---|
| Identity | `identity.{region}.oci.oraclecloud.com` | `identity.uk-london-1.oci.oraclecloud.com` |
| IaaS/Compute | `iaas.{region}.oraclecloud.com` | `iaas.uk-london-1.oraclecloud.com` |
| Login/OAuth | `login.{region}.{realm}` | `login.uk-london-1.oraclecloud.com` |

The `.oci.` subdomain is only on the identity service. IaaS/compute 
does NOT have it. This asymmetry was the root cause of Bug #1.