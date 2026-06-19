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

## Research questions

1. **`oci setup bootstrap`** — Oracle CLI has a bootstrap command that
   performs browser login, API key generation, upload, and config
   creation. Can this flow be replicated or adapted on Android?
   - What does it do exactly?
   - Does it use OAuth2, SAML, or proprietary auth?
   - Can the API key upload be done programmatically after browser
     login?
2. **OCI API authentication** — after bootstrap, OCI API calls use
   request signing with a private key. What's the exact flow?
   - API key signing (RSA key + fingerprint)
   - Are there OAuth2 bearer token alternatives?
   - Can we use instance principals or resource principals on a
     mobile device? (Probably not — those are for OCI-hosted
     workloads.)
3. **OCI SDK for Java/Android** — does the OCI Java SDK work on
   Android? Can we use it directly, or do we need raw HTTP?
4. **Chrome Custom Tab + Oracle login** — what's the redirect flow?
   Can we capture an auth token via redirect URI?
5. **Region selection** — Always Free VMs must be in the home region.
   Can we query the home region via API? Can we provision in a
   non-home region? (Aaron's account home region is UK South/London,
   which is useless for a UK VPN exit. This may require a separate
   account created with a non-UK home region.)
6. **Always Free capacity** — `VM.Standard.E2.1.Micro` availability
   varies. How do we handle "out of host capacity" gracefully?

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

### Phase 2A-1: Research (current)
- Investigate `oci setup bootstrap` source code and docs
- Determine auth flow (OAuth2 vs API key signing vs other)
- Evaluate OCI Java SDK on Android
- Determine region/availability constraints
- Spike: can we get an authenticated OCI API session from an Android
  app after browser login?

### Phase 2A-2: Auth spike
- Chrome Custom Tab login flow
- API key generation + upload via API
- Store signing key in Android Keystore
- First authenticated API call from Android

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