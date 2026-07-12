# Private Chat Phase 1 Report

Status: Phase 1 implementation  
Date: 2026-07-12

## Summary

Phase 1 implements the owner's private Matrix node on an Oracle Cloud Free Tier
A1 VM. It provisions WireGuard, PostgreSQL, Synapse, private TLS, the owner
Matrix identity, private-chat diagnostics, and Dev Mode logs.

## A1 capacity fallback

### First owner test (2026-07-12)

Aaron installed the Phase 1 APK and first proved that ordinary VPN-only
provisioning still works: created a normal OCI VM, connected the VPN, verified
the browser exit IP was in the Oracle home region, and destroyed the VM
successfully.

Aaron then enabled Dev Mode, selected Private Chat Node, and began
provisioning. The chat path requested `VM.Standard.A1.Flex` with 1 OCPU and
6 GB RAM. Oracle returned:

```text
HTTP 500
code: InternalError
message: Out of host capacity.
```

Aaron retried three times and received the same capacity failure each time. This
proved that Oracle login, API signing, and the API key were all valid — the
failure was A1 host capacity, not authentication. However, the UI displayed
**Failed at: API key setup**, which was incorrect. The actual failure was at the
VM launch stage.

### Fix

The capacity fallback implementation adds:

1. **Preferred configuration:** `VM.Standard.A1.Flex` — 1 OCPU / 6 GB
2. **Compact fallback:** `VM.Standard.A1.Flex` — 1 OCPU / 4 GB
3. **Trigger:** HTTP 500 + response body containing both `InternalError` and
   `Out of host capacity` (case-insensitive)
4. **No further fallback:** E2.1.Micro, paid shapes, increased OCPU, or any
   non-Free-Tier-eligible shape is never attempted
5. **No fallback for:** 401, 403, 429, 400, network timeouts, IOException,
   generic 500 without the capacity message, or any other non-capacity failure
6. **Stage attribution:** Capacity failures are attributed to **VM launch**,
   not **API key setup**, using a typed `VmLaunchFailureException` classified
   by `OciProvisioner.classifyLaunchFailure()` rather than brittle string
   matching
7. **Cleanup:** If both attempts fail, no instance OCID is created, so no
   instance termination is requested. Network resources that were already
   created are cleaned up normally
8. **Retry:** A user-initiated retry starts fresh with the preferred 6 GB
   configuration. Authentication and API-key state from the previous run are
   preserved when the retry begins from the saved Oracle session

### Dev Mode output

Dev Mode shows concise, useful entries:

```text
Launching Private Chat Node VM Attempt 1: VM.Standard.A1.Flex — 1 OCPU / 6 GB
Oracle reported no A1 host capacity
Retrying compact configuration
Launching Private Chat Node VM Attempt 2: VM.Standard.A1.Flex — 1 OCPU / 4 GB
```

On two capacity failures, the log shows both attempted configurations, the
actual VM-launch stage, no VM created, cleanup not required, and retry-later
guidance. Raw OCI response bodies are emitted as `developerOnly` events for
debugging but do not contain signing headers or credentials.

### Diagnostics integration

The Diagnostics screen **ORACLE OPERATION STATE** card now includes:

- Requested chat shape: `VM.Standard.A1.Flex`
- Preferred configuration: `1 OCPU / 6 GB`
- Compact fallback: `1 OCPU / 4 GB`
- Last attempted configuration
- Last launch result: `PASS` / `FAIL` / `NOT TESTED`
- Instance created: `Yes` / `No` / `NOT TESTED`
- Cleanup required: `Yes` / `No` / `WARNING` / `NOT TESTED`

When no VM exists, no server-health claims are displayed. No passwords, request
signatures, authentication headers, private keys, or reusable Oracle signing
material are shown.

### Automated tests

- `VmLaunchCapacityFallbackTest` (15 tests): capacity classifier, launch
  response classifier, final failure classifier
- `ProvisioningFailureClassificationTest` (4 tests): capacity error → VM_LAUNCH,
  generic error → event-based phase, clean message, auth error → AUTH

## Next operator test procedure

1. Build a new debug APK with the capacity fallback changes
2. Verify APK SHA-256 before installing
3. Enable Dev Mode
4. Select Private Chat Node and start provisioning
5. If Oracle has A1 capacity, both attempts should succeed at 6 GB (no fallback
   needed)
6. If Oracle returns out-of-host-capacity, verify the Dev Mode log shows both
   attempts and the UI attributes the failure to VM launch
7. If both attempts fail, verify Diagnostics shows the capacity-fallback fields
8. Retry and verify it starts fresh with 6 GB
9. If provisioning succeeds, continue with the existing operator test packet
   for chat installation stages

## Validation status

- Android unit tests: 22 total, all pass
- Gradle `clean`, `test`, `lint`, `assembleDebug`: passed
- Python server tests: unchanged from 2026-07-11 baseline
- No Oracle login, Oracle VM creation, or interactive Android run was performed
  in this code lane. Runtime verification remains pending the next operator
  test.