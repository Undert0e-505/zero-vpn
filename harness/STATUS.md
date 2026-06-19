# OCI Auth Harness — Status

## What's been done

### Branch: `oci-auth-harness`

1. **JWK generation verified.** The Python harness generates a valid RSA JWK
   that passes `jwcrypto` (a real JOSE/JWK parser) validation:
   - `kty: RSA` ✓
   - `e: AQAB` (65537) ✓
   - `n: 256 bytes, no BigInteger sign byte` ✓
   - base64url with padding (matching OCI CLI's `base64.urlsafe_b64encode`) ✓
   - No `+` or `/` characters ✓
   - Round-trip decode matches ✓

2. **Harness built.** `harness/oci-auth-harness.py` is a standalone Python
   script that:
   - Generates an RSA 2048-bit keypair
   - Builds the JWK and base64url-encodes it
   - Starts a local HTTP listener on port 8181
   - Opens the system browser to the Oracle OAuth2 authorize URL
   - Logs every redirect/callback received
   - Extracts the security token from the callback
   - Decodes the JWT for user/tenancy OCIDs
   - Optionally uploads the public key as an API key

3. **Android JWK bugs identified and fixed.** The Android Kotlin code had two
   bugs that the Python harness does not:
   - `BigInteger.toByteArray()` adds a leading `0x00` sign byte (now stripped)
   - Used `withoutPadding()` instead of `getUrlEncoder()` (now includes padding)

## What's needed next

### Run the harness on the Windows machine (requires Aaron at the PC)

The harness opens a browser on the local machine and needs a human to
complete the Oracle login. The localhost callback listener must be on the
same machine as the browser.

```
D:\Python310\python.exe D:\dev\zero-vpn\harness\oci-auth-harness.py --region uk-london-1
```

This will:
1. Print the JWK for verification
2. Open the default browser to Oracle login
3. Wait for the callback (up to 5 minutes)

Aaron completes login in the browser, and the harness captures the token.

### Acceptance criteria

Either:
- **Harness completes auth:** token captured, JWT decoded, user/tenancy OCIDs
  extracted. The flow is proven and can be ported to Android.
- **Harness fails:** document exactly why (Oracle rejects the URL, callback
  never arrives, token extraction fails, etc.) and pivot to Resource Manager
  or another approach.

### After the harness works

1. Port the known-good JWK generation and auth URL construction to Kotlin
2. Use Chrome Custom Tab (not WebView) for the browser
3. Use an app-controlled callback (deep link or loopback listener on Android)
4. Keep WebView out of the final auth path

## Key difference from Android approach

The harness runs on the same machine as the browser, so `localhost:8181`
works naturally. On Android, the loopback listener would need to be inside
the app, and the browser (Chrome Custom Tab) would need to redirect back to
the app. The custom-scheme redirect was already rejected by Oracle
(`invalid redirect uri`), so the options are:

1. **Loopback listener on Android:** start a local HTTP server inside the
   app on port 8181, open Chrome Custom Tab to the Oracle URL with
   `redirect_uri=http://localhost:8181`. Chrome Custom Tab is on the same
   device, so `localhost` resolves to the phone. The app's HTTP server
   receives the callback.
2. **Intercept navigation in Chrome Custom Tab:** use Custom Tab intent
   callbacks to detect when the URL changes to `localhost:8181` and extract
   the token before the navigation completes.

Both approaches need verification on Android, but only after the harness
proves the auth flow works at all.