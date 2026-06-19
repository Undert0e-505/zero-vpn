# Phase 2A: Oracle In-App Bootstrap

## Goal

From the Android app, after the user has created an Oracle account,
authenticate through Oracle's own browser login and provision a
disposable Always Free VM with public IPv4 automatically.

## Background

Manual OCI console setup was rejected after 30 minutes of failed
VNIC/public-IP navigation. Cloud Shell paste scripts work for
development but are rejected for the product flow — no user should
be pasting scripts into a cloud terminal.

The target is a fully in-app experience: tap a button, log in to
Oracle via browser, get a VM.

## Constraints

- **No Oracle credentials in native UI.** Do not ask for
  username/password inside ZeroVPN native UI.
- **No stored secrets beyond the OCI API signing key.** Do not store
  Oracle password, 2FA secret, card data, cookies, or recovery codes.
- **Oracle-controlled browser login.** Use Chrome Custom Tab or
  equivalent for Oracle login/2FA. The app never sees the password.
- **OCI private signing key in Android Keystore.** Store any generated
  OCI private signing key using Android Keystore / encrypted storage.
- **No manual infrastructure steps.** No VCN, subnet, public IP,
  gateway, security-list, SSH, or Cloud Shell steps. No scripts pasted
  by the user.
- **Disposable by design.** The app can destroy the VM from inside
  ZeroVPN.

## Research findings (2026-06-19)

### How `oci setup bootstrap` works (source: `cli_setup_bootstrap.py`)

The bootstrap flow is:

1. **Generate RSA keypair locally** (client-side)
2. **Encode public key as JWK**, base64url-encode it
3. **Construct Oracle OAuth2 authorize URL**:
   - `https://login.{region}.{realm}/v1/oauth2/authorize`
   - Query params: `action=login`, `client_id=iaas_console`,
     `response_type=token id_token`, `nonce=<uuid>`,
     `scope=openid`, `public_key=<base64url JWK>`,
     `redirect_uri=http://localhost:8181`
4. **Open browser** to that URL — user logs in with Oracle
5. **Oracle redirects back** to `http://localhost:8181` with
   `#security_token=<JWT>` in the URL fragment
6. **Javascript in the redirect page** extracts the fragment and sends
   it to `http://localhost:8181/token?security_token=<value>`
7. **Local HTTP server captures the security token** (a JWT)
8. **Extract user OCID and tenancy OCID** from the JWT `sub` and
   `tenant` claims
9. **Upload the public key as an API key** via OCI API
   (`IdentityClient.upload_api_key`), signed with the security token
10. **Write config file** with user OCID, tenancy OCID, region,
    fingerprint, key path

### Auth model

OCI supports two auth methods relevant to us:

1. **API key signing** (permanent): RSA private key signs each request.
   Requires `user_ocid`, `tenancy_ocid`, `fingerprint`, `key_file`,
   `region`. This is what `oci setup bootstrap` ends with.

2. **Session token (UPST)** (temporary, 5-60 min): Security token +
   private key used to sign requests. Token is refreshable. This is
   what `oci session authenticate` produces.

Both use **request signing** (not OAuth2 bearer tokens). Every API
call is signed with the RSA private key. There is no simple
"Bearer token" mode for OCI API calls.

### Android adaptation strategy

The bootstrap flow maps to Android as follows:

| Step | CLI | Android |
|---|---|---|
| Generate RSA keypair | `cryptography` lib | `KeyPairGenerator` -> Android Keystore |
| Encode public key as JWK | Python `json` + `base64` | Java `JWK` or manual JSON+Base64 |
| Open browser to Oracle login | `webbrowser.open()` | **Chrome Custom Tab** with the OAuth2 URL |
| Capture redirect | Local HTTP server on port 8181 | **Deep link / App Link** (`zerovpn://auth/callback` or `https://zerovpn.app/auth/callback`) |
| Extract token from URL fragment | JS in redirect page + HTTP | **Custom Tab redirect capture** |
| Upload API key | OCI SDK `upload_api_key()` | Raw HTTP POST to OCI API, signed with security token |
| Store config | File on disk | **EncryptedSharedPreferences** / Keystore |

### Key challenge: redirect capture

The CLI uses a localhost HTTP server on port 8181. Android can't do
that. Instead:

- **Option A: Custom URI scheme** (`zerovpn://auth/callback`). The
  Chrome Custom Tab redirects to this URI, Android opens the app,
  the app receives the token via Intent. Oracle's OAuth2 `redirect_uri`
  must accept custom schemes.
- **Option B: App Link** (`https://zerovpn.app/auth/callback`).
  Verified App Link, same flow but with HTTPS URL.
- **Option C: WebView with JavaScript interface.** Use a WebView
  instead of Chrome Custom Tab. Inject JS to capture the redirect
  fragment. Uglier but guaranteed to work regardless of Oracle's
  redirect_uri validation.

**Risk:** Oracle's OAuth2 may only accept `localhost` redirect URIs
(or a fixed set). If `redirect_uri` is validated server-side and
custom schemes are rejected, Option C (WebView) is the fallback.

**This is the #1 research spike.** Everything else is straightforward
if the redirect capture works.

### OCI Java SDK on Android

The OCI Java SDK uses Jersey (JAX-RS) HTTP client, which has heavy
dependencies. A 2024 refactor eliminated BouncyCastle, which helps.
But Jersey on Android is problematic (large jar, `javax.*` vs
`jakarta.*`, HTTP client conflicts).

**Recommendation: don't use the OCI Java SDK directly.** Instead,
implement request signing in pure Kotlin/Java (it's RSA-SHA256 over
specific headers — well-documented) and use OkHttp for HTTP calls.
The signing algorithm is:

1. Build canonical request string (method, path, headers)
2. Sign with RSA-SHA256 using the private key
3. Add `Authorization: Signature ...` header

This is ~100 lines of Kotlin. Much lighter than pulling in the
entire OCI SDK.

### Region selection

Always Free VMs must be in the **home region**. Aaron's account home
region is **UK South (London)** — `uk-london-1`. This is useless for
a UK VPN exit (the point is to exit outside the UK).

Options:
- Create a second Oracle account with a non-UK home region (e.g.,
  `us-ashburn-1` or `eu-frankfurt-1`). Oracle's TOS may restrict
  multiple accounts per person.
- Subscribe to a non-home region (Always Free resources are only
  in the home region, so this doesn't help on the free tier).
- Use a different provider for non-UK exits (Azure Student, existing
  server abroad).

**This is a product-level blocker for the Oracle lane as a UK VPN
exit.** The in-app bootstrap can still be built and tested with a
UK-London VM (for development), but production non-UK exits need
either a non-UK Oracle account or a different provider.

### `--no-browser` mode (for programmatic auth)

`oci session authenticate --no-browser` uses a different flow:

1. Generate RSA keypair locally
2. Call `identity_data_plane.generate_user_security_token()` directly
   with the public key and session expiration
3. Get back a token immediately — no browser needed

**But** this requires an existing authenticated session (delegation
token from Cloud Shell or an existing API key). It's not a way to
bootstrap from scratch without prior auth. So it doesn't help for
the first-time user flow.

### Summary: feasibility verdict

**Technically feasible** with these caveats:

1. **Redirect capture is the critical unknown.** Need to verify that
   Oracle's OAuth2 accepts custom-scheme or App Link redirect URIs.
   If not, a WebView fallback is possible but uglier.
2. **Request signing in Kotlin** is straightforward (~100 lines).
   No need for the full OCI SDK.
3. **Region limitation is a product blocker for UK exits.** The
   Oracle lane works technically but only provides a UK IP for Aaron's
   account. Non-UK exits need a different account or provider.
4. **No password storage.** The app only stores the RSA private key
   (in Keystore) and the user/tenancy OCIDs + fingerprint. Oracle
   password is never seen by the app.

## Acceptance test

1. Aaron taps "Create Oracle Free Exit"
2. App opens Oracle login/signup if required (Chrome Custom Tab)
3. Aaron completes Oracle login/2FA with Oracle
4. App returns to ZeroVPN
5. App provisions a disposable non-UK OCI VM automatically
6. App displays public IPv4 and provisioning status
7. App can destroy the VM from inside ZeroVPN
8. No manual Oracle console networking is required

## Implementation phases

### Phase 2A-1: Research (complete)
- [x] Investigate `oci setup bootstrap` source code and docs
- [x] Determine auth flow (OAuth2 implicit flow + API key signing)
- [x] Evaluate OCI Java SDK on Android (recommend: raw OkHttp + manual signing)
- [x] Determine region/availability constraints (home region only = UK for Aaron)
- [ ] Spike: can we get an authenticated OCI API session from an Android
  app after browser login? (redirect capture test)

### Phase 2A-2: Auth spike (next)
- Chrome Custom Tab login flow with Oracle OAuth2 URL
- Test redirect_uri acceptance (custom scheme vs App Link vs WebView)
- API key generation + upload via raw HTTP
- Store signing key in Android Keystore
- First authenticated API call from Android (list regions)

### Phase 2A-3: Provisioning
- VCN + subnet + IGW + route table + security list via API
- Compute instance launch with public IP
- Poll for public IP assignment
- Display status in-app

### Phase 2A-4: Lifecycle
- Destroy VM + cleanup all resources via API
- Re-provision after destruction
- Error handling (capacity, quota, region issues)

### Phase 2A-5: UX polish
- Progress indicators during provisioning
- Region selection (non-UK requirement)
- QR code / WireGuard config delivery after provisioning
- Integration with existing ZeroVPN connection UI

## Relationship to other lanes

- **Cloud Shell script** (`cloud-shell/oci-bootstrap.sh`) remains as
  the developer/test path. It's useful for iterating on the
  infrastructure layout before encoding it into the app.
- **Resource Manager / Terraform stack** is still a potential
  alternative product path (one-click from Oracle console) but the
  in-app lane is now the primary target.
- **Manual console** is deprecated and will not be supported.