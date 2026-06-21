# ZeroVPN - OCI API Key Upload Wire Tap Test

This document describes the wire-tap method used to compare Python OCI SDK
requests with Android OkHttp requests while debugging OCI API signing.

The original capture files contained real OCI account/resource identifiers and
security-token material. Those generated captures are intentionally not kept in
the public repository.

## Purpose

Capture the exact request shape sent by the Python OCI SDK for API key upload,
then compare it with the Android implementation.

The useful public lesson is the request structure, not the real account data.
All values below are examples.

## Method

A copy of `state_machine.py` can be instrumented as `state_machine_capture.py`.
The capture patch hooks the OCI SDK HTTP session after signing so it can inspect
the final prepared request.

The capture can record:

- Method and URL.
- Headers.
- Body length.
- Body SHA256.
- Response status.

Generated capture files such as `python_all_requests.log`,
`python_post_capture.json`, and `state_machine_capture.log` must stay ignored.
They may contain OCI security tokens, OCIDs, account email addresses, and cloud
resource identifiers.

## Example API Key Upload Request

Example URL:

```text
POST https://identity.uk-london-1.oci.oraclecloud.com/20160918/users/ocid1.example.user/apiKeys
```

Example signed headers:

```text
date: Sat, 20 Jun 2026 05:16:30 GMT
(request-target): post /20160918/users/ocid1.example.user/apiKeys
host: identity.uk-london-1.oci.oraclecloud.com
content-length: 471
content-type: application/json
x-content-sha256: EXAMPLE_BASE64_SHA256
```

Example Authorization header shape:

```text
Signature algorithm="rsa-sha256",headers="date (request-target) host content-length content-type x-content-sha256",keyId="ST$REDACTED_SECURITY_TOKEN",signature="REDACTED_SIGNATURE",version="1"
```

Example body shape:

```json
{"key": "-----BEGIN PUBLIC KEY-----\nREDACTED_PUBLIC_KEY\n-----END PUBLIC KEY-----\n"}
```

## Findings

1. The OCI Python SDK may use a vendored requests session. Patching standard
   `requests.Session.send` alone may miss SDK traffic.
2. For POST requests, the signed header list must match the body bytes actually
   sent on the wire.
3. OCI security-token auth embeds the security token in the signing key ID.
   Never commit raw authorization headers or capture logs.
4. Wire-tap output is generated debug material and must remain outside Git.

## Public Repository Rules

Do not commit:

- Real `Authorization` headers.
- `ST$...` security-token values.
- Real OCIDs.
- Real Oracle account email addresses.
- Generated capture JSON/log files.
- SSH or WireGuard private keys.
