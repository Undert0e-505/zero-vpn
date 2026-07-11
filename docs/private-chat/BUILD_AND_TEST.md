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

All Python dependencies must stay in repository-local virtual environments. Do
not install pytest, matrix-nio, python-olm, or Synapse into the global
`D:\Python310` interpreter.

Create the ordinary unit-test environment once from the repository root:

```powershell
Set-Location D:\dev\zero-vpn
& 'D:\Python310\python.exe' -m venv .venv-test
& .\.venv-test\Scripts\python.exe -m pip install --disable-pip-version-check --requirement requirements-test.txt
```

The exact normal test command is:

```powershell
& .\.venv-test\Scripts\python.exe -m pytest -v
```

Equivalently, after activating `.venv-test`, run exactly:

```powershell
python -m pytest -v
```

`pytest.ini` limits ordinary collection to `test_*.py` under
`server/private-chat/tests`. The ad-hoc OCI harness and the real
`encrypted_self_test.py` client are not ordinary unit tests and are therefore
not imported during normal collection. The PowerShell helper uses the same
isolated command:

```powershell
& .\server\private-chat\tests\run-tests.ps1
```

The ordinary suite covers:

- probe-satisfied, failed, and resumed stage transitions;
- atomic state and error redaction;
- Ubuntu/architecture/RAM/disk/package-manager/WireGuard/port preflight cases;
- managed partial-install detection;
- firewall generation and non-interference with built-in/WireGuard/NAT chains;
- future chat-only metadata/lateral/Internet denial structure;
- node manifest schema, private URL, TLS pin, stable owner ID, and secret-field rejection;
- the real encrypted self-test procedure contract, including both directions, raw ciphertext, plaintext absence, account deactivation, and room purge.
- PostgreSQL/Synapse/nginx closed-service policy, exact listener scope parsing,
  TLS identity requirements, Android manifest parsing/redaction, Diagnostics
  field coverage, and VPN-preserving removal boundaries.

The contract test is not a substitute for the real Synapse run. It ensures the
shipped real-client script cannot be silently replaced by a mock procedure.

Run the applicable Python static syntax check with the ordinary environment:

```powershell
& .\.venv-test\Scripts\python.exe -m compileall -q server/private-chat
```

## Opt-in encrypted Matrix interoperability test

Create a completely separate environment:

```powershell
Set-Location D:\dev\zero-vpn
& 'D:\Python310\python.exe' -m venv .venv-interop
& .\.venv-interop\Scripts\python.exe -m pip install --disable-pip-version-check --requirement requirements-interop.txt
```

The exact explicit integration command is:

```powershell
& .\.venv-interop\Scripts\python.exe -m pytest -v -rs server/private-chat/tests/interop_encrypted_matrix.py
```

On Windows this reports a clear skip: the real exchange is restricted to the
disposable Ubuntu/Synapse node, and `requirements-interop.txt` intentionally
does not attempt the unsupported Windows python-olm source build. On a prepared
Ubuntu node the same requirements file installs `matrix-nio[e2e]` in
`.venv-interop`; run the explicit file with root-readable disposable inputs:

```bash
python3 -m venv .venv-interop
.venv-interop/bin/python -m pip install --disable-pip-version-check --requirement requirements-interop.txt
export ZEROVPN_MATRIX_LOCAL_URL='http://127.0.0.1:8008'
export ZEROVPN_MATRIX_OWNER_CREDENTIALS='/etc/zerovpn/private-chat/secrets/owner-matrix.json'
export ZEROVPN_MATRIX_REGISTRATION_SECRET='/etc/zerovpn/private-chat/secrets/registration_shared_secret'
sudo --preserve-env=ZEROVPN_MATRIX_LOCAL_URL,ZEROVPN_MATRIX_OWNER_CREDENTIALS,ZEROVPN_MATRIX_REGISTRATION_SECRET \
  .venv-interop/bin/python -m pytest -v -rs server/private-chat/tests/interop_encrypted_matrix.py
```

Do not enable shell tracing, echo those variables, or use a non-disposable
homeserver. Missing optional modules, a non-Linux host, non-root access, or
missing input paths produces an explicit skip before `matrix-nio` is imported.

## Android validation and APK asset check

```powershell
Set-Location D:\dev\zero-vpn\android
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
$env:ANDROID_HOME = 'D:\dev\android-sdk'
$env:ANDROID_SDK_ROOT = 'D:\dev\android-sdk'
.\gradlew.bat clean
.\gradlew.bat test
.\gradlew.bat lint
.\gradlew.bat assembleDebug
```

`android/app/build.gradle.kts` packages this repository's `server/private-chat` tree as APK assets. Verify the essential files after asset merge:

```powershell
.\gradlew.bat :app:mergeDebugAssets
Get-ChildItem -Recurse app\build\intermediates\assets\debug\mergeDebugAssets\private-chat
```

At minimum, the merged assets must contain `installer/install.py`, `installer/model.py`, Synapse templates/requirements, firewall unit template, and `tests/encrypted_self_test.py`.

The source debug APK is:

```text
D:\dev\zero-vpn\android\app\build\outputs\apk\debug\app-debug.apk
```

The review/test copy and checksum are:

```text
D:\dev\zero-vpn\artifacts\zerovpn-private-chat-phase1-debug.apk
D:\dev\zero-vpn\artifacts\zerovpn-private-chat-phase1-debug.apk.sha256
```

The checksum file contains lowercase SHA-256 hex followed by the APK filename.

Code/build validation recorded on 2026-07-11:

- normal pytest: 37 passed (plus 55 unittest subtests), no collection errors;
- explicit Windows interop command: 1 accurately reported Ubuntu-only skip;
- Python compileall, generated firewall shell syntax, tracked health shell syntax, and PowerShell parser checks: passed;
- Gradle `clean`, `test`, `lint`, and `assembleDebug`: passed with JDK 21 and the SDK paths above;
- merged APK asset tree: 16 runtime files, including the encrypted self-test and excluding host-only pytest/PowerShell runners;
- APK size: 107,847,791 bytes;
- APK SHA-256: `846e76df8600514055408305b71d97daca9c422b1303587d3d30f696a6371564`.

These are host-side code/build results. The APK was not installed or exercised
on Android, and no Oracle or Ubuntu VM runtime was used in this lane.

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
