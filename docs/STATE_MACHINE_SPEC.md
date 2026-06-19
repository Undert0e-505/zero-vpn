# Oracle Setup State Machine — Design Spec

## States

```
NotStarted
  → OracleIntro (user tapped "Create Oracle Free Exit")
OracleIntro
  → AwaitingOracleBrowser (user read warning, tapped "Continue")
  → NotStarted (user cancelled)
AwaitingOracleBrowser
  → AuthReceived (callback received with security token)
  → OracleIntro (user returned without completing auth, pressed "Continue Oracle setup")
AuthReceived
  → PreflightRunning (token decoded, starting tenancy checks)
PreflightRunning
  → ReadyToProvision (preflight passed)
  → PreflightFailed (preflight failed)
PreflightFailed
  → OracleIntro (user chose different account)
  → ReadyToProvision (dev mode: user overrode UK region warning)
ReadyToProvision
  → Provisioning (user tapped "Create Exit")
  → OracleIntro (user cancelled)
Provisioning
  → ExitReady (all phases succeeded)
  → ProvisionFailed (a phase failed)
ExitReady
  → Destroying (user tapped "Destroy Node")
  → NotStarted (user tapped "Done")
ProvisionFailed
  → Provisioning (user tapped "Retry")
  → Destroying (user tapped "Cleanup")
Destroying
  → Destroyed (cleanup succeeded)
  → CleanupRequired (cleanup partially failed)
Destroyed
  → NotStarted (done)
CleanupRequired
  → Destroying (user tapped "Retry Cleanup")
  → NotStarted (user tapped "Ignore")
```

## Persisted state (non-secret, app storage)

```json
{
  "state": "AwaitingOracleBrowser",
  "region": "uk-london-1",
  "user_ocid": null,
  "tenancy_ocid": null,
  "fingerprint": null,
  "home_region": null,
  "resource_ids": {
    "vcn_id": null,
    "subnet_id": null,
    "sl_id": null,
    "igw_id": null,
    "instance_id": null
  },
  "public_ip": null,
  "wireguard_port": 51820,
  "last_successful_phase": null,
  "last_error": null,
  "is_dev_mode": false
}
```

## Secret material (Android Keystore / encrypted storage only)

- OCI API private key (RSA PEM)
- Security token (ephemeral, 60 min)
- SSH private key (project key, on workstation)
- WireGuard private keys (server + peer)
- Full client config (contains private key)

## Preflight checks

1. Decode JWT → extract user OCID, tenancy OCID
2. List region subscriptions → find home region
3. If home region is UK and not dev mode → fail with "UK region cannot be used as non-UK exit"
4. If home region is UK and dev mode → warn but continue
5. Check Always Free compute availability (list shapes, verify VM.Standard.E2.1.Micro exists)
6. Check API key count (max 3 — if at limit, fail with "delete an existing API key")

## Provisioning phases (emitting structured events)

1. AUTH — browser auth, capture token
2. API_KEY — upload API key
3. NETWORK — VCN, security list, subnet, IGW, route table
4. VM_LAUNCH — instance launch, wait for RUNNING, get public IP
5. WAIT_SSH — wait for SSH connectivity
6. WIREGUARD — install, configure, generate peer config
7. DONE — success, show public IP + WireGuard port