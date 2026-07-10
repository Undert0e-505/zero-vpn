# Private Chat Build and Test Guide

Status: Phase 1

## Prerequisites

Repository root: `D:\dev\zero-vpn`

- JDK 21: `C:\Program Files\Java\jdk-21`
- Android SDK: `D:\dev\android-sdk`
- Python 3.10: `D:\Python310\python.exe`
- Windows 11/PowerShell for the host checks
- For the real installer: a supported Ubuntu 22.04/24.04 VM with working `wg-quick@wg0`, private address `10.66.66.1`, root through sudo, package-network access, and the Phase 1 resource minimums

No Zero Chat checkout is needed to build, test, or run this implementation.

## Host-side installer tests

From the repository root:

```powershell
& 'D:\Python310\python.exe' -B -m unittest discover -s server/private-chat/tests -p 'test_*.py' -v
```

or:

```powershell
& .\server\private-chat\tests\run-tests.ps1
```

The suite covers:

- probe-satisfied, failed, and resumed stage transitions;
- atomic state and error redaction;
- Ubuntu/architecture/RAM/disk/package-manager/WireGuard/port preflight cases;
- managed partial-install detection;
- firewall generation and non-interference with built-in/WireGuard/NAT chains;
- future chat-only metadata/lateral/Internet denial structure;
- node manifest schema, private URL, TLS pin, stable owner ID, and secret-field rejection;
- the real encrypted self-test procedure contract, including both directions, raw ciphertext, plaintext absence, account deactivation, and room purge.

The contract test is not a substitute for the real Synapse run. It ensures the shipped real-client script cannot be silently replaced by a mock procedure.

## Android compile and APK asset check

```powershell
Set-Location D:\dev\zero-vpn\android
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
$env:ANDROID_HOME = 'D:\dev\android-sdk'
$env:ANDROID_SDK_ROOT = 'D:\dev\android-sdk'
.\gradlew.bat :app:compileDebugKotlin
.\gradlew.bat :app:assembleDebug
```

`android/app/build.gradle.kts` packages this repository's `server/private-chat` tree as APK assets. Verify the essential files after asset merge:

```powershell
.\gradlew.bat :app:mergeDebugAssets
Get-ChildItem -Recurse app\build\intermediates\assets\debug\mergeDebugAssets\private-chat
```

At minimum, the merged assets must contain `installer/install.py`, `installer/model.py`, Synapse templates/requirements, firewall unit template, and `tests/encrypted_self_test.py`.

## Manual Ubuntu installer run

For a standalone test VM, copy the tracked directory without generated state:

```powershell
scp -r .\server\private-chat ubuntu@VM_PUBLIC_IP:/tmp/zerovpn-private-chat-test
```

On the VM:

```bash
sudo python3 /tmp/zerovpn-private-chat-test/installer/install.py \
  install \
  --wireguard-interface wg0 \
  --wireguard-address 10.66.66.1
```

Do not pass database, Matrix, registration, or TLS secrets on the command line. The installer generates them locally.

Expected final marker:

```text
ZEROVPN_PRIVATE_CHAT_COMPLETE {"manifestPath":"/etc/zerovpn/private-chat/node.json",...}
```

Inspect only non-secret state:

```bash
sudo python3 /opt/zerovpn/private-chat/installer/install.py health --json
sudo python3 /opt/zerovpn/private-chat/installer/install.py manifest
sudo systemctl status wg-quick@wg0 postgresql zerovpn-private-chat-synapse nginx zerovpn-private-chat-firewall
sudo ss -ltnp
```

Expected listener scope:

- nginx: `10.66.66.1:443` only;
- Synapse: `127.0.0.1:8008` only;
- PostgreSQL: loopback `5432` only;
- no public Matrix/federation listener.

## Real encrypted self-test

The installation stage runs this automatically against local real Synapse. It uses the pinned `matrix-nio[e2e]` environment at `/opt/zerovpn/private-chat/venv/self-test`.

For an explicit rerun, remove only the self-test stage result from a disposable test VM or run the script with its three root-only environment variables set by an operator shell. Do not echo those variables, enable shell tracing, or capture the environment in CI logs. The normal and preferred route is simply the idempotent `install` command, which retries the stage safely.

Passing evidence requires all of:

- two independently registered temporary identities;
- `m.room.encryption` in room initial state using Megolm;
- exact A-to-B and B-to-A decrypted message matches;
- raw server event type `m.room.encrypted` with non-empty ciphertext;
- each unique plaintext marker absent from raw event JSON;
- both temporary accounts deactivated/erased;
- the room submitted for purge and no longer returned by the room Admin API.

Only booleans are printed. Temporary usernames, passwords, tokens, room/event IDs, and message bodies remain out of logs.

## Network and VPN survival checks

From an owner device connected to the generated WireGuard profile:

1. Confirm ordinary Internet routing still works and record the exit IP.
2. Open the exit's Home card and run **Verify owner login through VPN**. This performs `/versions`, login, and logout through `https://10.66.66.1` with the provisioned SPKI pin.
3. Confirm `https://10.66.66.1/_matrix/client/versions` is reachable only with the tunnel active.
4. From the public Internet, confirm TCP 443/8008/5432 and federation ports are not reachable.
5. Stop Synapse or induce a disposable self-test failure, retry the chat stage, and confirm the WireGuard tunnel remains connected.
6. Use **Remove Private Chat**, then confirm `wg-quick@wg0` remains active and Internet routing still works.

Future chat-only peer negative tests cannot run until Phase 3 creates restricted peers. Phase 1 unit tests validate the generated deny chains, but they are intentionally unattached until such peers exist.

## Idempotency and interruption matrix

On a disposable VM, exercise:

- two complete consecutive installer runs;
- interruption during package, Synapse, TLS, owner-account, and self-test stages;
- reboot after a successful install;
- retry after app/process death;
- pre-existing PostgreSQL with no ZeroVPN database;
- unmanaged Synapse and port-conflict refusal;
- low-memory/disk warnings and hard failures;
- chat removal followed by a fresh reinstall;
- Synapse/PostgreSQL health failure with owner VPN survival.

Never use production owner history or credentials for these tests.
