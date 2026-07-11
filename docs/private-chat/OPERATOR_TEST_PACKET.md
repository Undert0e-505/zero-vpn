# Private Chat Phase 1 Operator Test Packet

Operator: Aaron  
Scope: disposable Oracle Free Tier eligible Ubuntu A1 node, normal ZeroVPN app flow  
Runtime status before this packet: **not tested on Android or Oracle in the code-preparation lane**

## Test APK

APK:

```text
D:\dev\zero-vpn\artifacts\zerovpn-private-chat-phase1-debug.apk
```

Checksum file:

```text
D:\dev\zero-vpn\artifacts\zerovpn-private-chat-phase1-debug.apk.sha256
```

Expected SHA-256:

```text
846e76df8600514055408305b71d97daca9c422b1303587d3d30f696a6371564
```

This is a debug APK for a controlled test, not a release build.

## Boundary and stop conditions

- Aaron must perform every Oracle sign-in, MFA, consent, and account action through the app's normal browser flow.
- Do not share Oracle credentials, MFA codes, Matrix credentials, SSH keys, WireGuard private keys, tokens, or screenshots containing them.
- Do not use a production VM. Use a disposable node and review Oracle's cost estimate/tenancy limits before confirming anything.
- `VM.Standard.A1.Flex` at 1 OCPU, 6 GB RAM, and a 50 GB boot volume is requested when Private Chat is enabled. Eligibility and capacity are determined by Oracle and are not guaranteed.
- Stop and report if the app asks for credentials outside the normal Oracle browser, proposes an unexpected shape/cost, or displays a secret in a log or Diagnostics.
- Do not manually resize, repair, or reconfigure the VM during this test. A failed chat install should be handled with **Retry** or **Remove Chat** in ZeroVPN.

## 1. Verify and install the APK

1. On the Windows test machine, verify the file before transferring it:

   ```powershell
   $apk = 'D:\dev\zero-vpn\artifacts\zerovpn-private-chat-phase1-debug.apk'
   (Get-FileHash -Algorithm SHA256 -LiteralPath $apk).Hash.ToLowerInvariant()
   ```

2. Confirm the result is exactly:

   ```text
   846e76df8600514055408305b71d97daca9c422b1303587d3d30f696a6371564
   ```

3. Use a disposable Android device or test profile. If a differently signed ZeroVPN build is already installed, `adb install -r` may report a signature conflict. Do not uninstall an existing app unless losing its local ZeroVPN profiles is acceptable.
4. Install with ADB:

   ```powershell
   adb install -r 'D:\dev\zero-vpn\artifacts\zerovpn-private-chat-phase1-debug.apk'
   ```

   Alternatively, transfer the APK to the test device and open it with Android's package installer. Approve installation from that source only for this test.
5. Launch ZeroVPN. Record the device model, Android version, and the displayed ZeroVPN version.

## 2. Enable the existing Dev Mode

1. Open the **Settings** tab.
2. Turn on **Developer Mode**.
3. Confirm the description mentions OCI telemetry and Private Chat stage timing, health, and manifest summary.
4. Leave Developer Mode enabled for the whole provisioning run.

## 3. Start a disposable Oracle A1 provisioning run

1. Open **Add Exit**.
2. Choose the Oracle exit flow to reach **Create Oracle Exit**.
3. Turn on **Private Chat Node (Phase 1)** before starting Oracle authentication.
4. Confirm the screen shows the requested `VM.Standard.A1.Flex`, 1 OCPU, 6 GB RAM, and 50 GB boot volume wording and the Oracle billing disclaimer.
5. Optionally choose Aaron's Oracle home region. Otherwise leave **Optional: choose region manually** unchanged and let ZeroVPN discover it.
6. Tap **I already have an Oracle Cloud account**.
7. Complete Oracle sign-in and any MFA only in the browser/app flow opened by ZeroVPN. Do not paste credentials anywhere else.
8. Return to ZeroVPN after Oracle redirects back. If the app asks for a manual continue, follow the visible normal-flow prompt.
9. Allow the app to create the disposable VM and working WireGuard exit. Do not use Oracle Console or external credentials to modify the VM while the test is running.

## 4. Watch and capture Private Chat provisioning

1. Keep the provisioning screen open. The working WireGuard exit should be saved before the optional chat workload starts.
2. In the Dev Mode log, check that each of these stages gets a **start** and then **pass**, **warning**, or **fail** entry:

   ```text
   PRIVATE_CHAT_PRECHECK
   PRIVATE_CHAT_PACKAGES
   PRIVATE_CHAT_POSTGRES
   PRIVATE_CHAT_SYNAPSE
   PRIVATE_CHAT_TLS
   PRIVATE_CHAT_FIREWALL
   PRIVATE_CHAT_OWNER_ACCOUNT
   PRIVATE_CHAT_HEALTH
   PRIVATE_CHAT_ENCRYPTION_SELF_TEST
   PRIVATE_CHAT_COMPLETE
   ```

3. For terminal entries, check that a duration is shown. On a failure, check that the detail is redacted and the UI says retry resumes from the saved VM stage while WireGuard remains available.
4. At completion, check the retained **PRIVATE CHAT PROVISIONING LOG** for:

   - stage name, status, and duration;
   - PostgreSQL, Synapse, TLS endpoint, Matrix `/versions`, owner-account, firewall, and self-test results;
   - a manifest summary containing only `server_name`, private URL, TLS fingerprint, and installed versions.

5. Take a screenshot showing several Private Chat stages and their status/duration. Scroll and take another if needed to include `PRIVATE_CHAT_COMPLETE` and the manifest summary.
6. Inspect screenshots before sharing. They must not contain a password, token, private key, Matrix credential, or Oracle account detail.

## 5. Check the existing Diagnostics screen

1. After provisioning returns success or a chat-only failure with the VPN retained, open the **Diagnostics** tab.
2. Find the existing **PRIVATE CHAT NODE** card.
3. Tap **Refresh node** once. Wait for the card to update.
4. Check and record:

   - **Private Chat installed:** Yes/No;
   - **Node health:** healthy/degraded/unhealthy/not checked;
   - **PostgreSQL:** Pass/Fail;
   - **Synapse:** Pass and loopback-only status/Fail;
   - **TLS endpoint:** Pass/Fail;
   - **Matrix /versions:** Pass/Fail;
   - **Owner account:** Exists/Missing;
   - **Private firewall:** Active/Fail;
   - **Chat-only peer rules active:** Phase 1 should normally say **No - policy ready; Phase 1 has no chat-only peers**;
   - **Last self-test:** Pass/Fail/Not run;
   - `server_name`, private Matrix URL, TLS SPKI SHA-256 fingerprint, and installed versions;
   - all installation stages as complete/failed/running/pending.

5. Take screenshots of:

   - the Diagnostics card header through the four node-health checks;
   - the manifest fields and installed versions;
   - the installation-stage and firewall/self-test portion.

6. Tap **Copy summary**, paste into a local scratch note, and confirm it contains only the same non-secret diagnostic fields. Delete the scratch note after reporting.
7. Optional Phase 1 owner check: return to **Home**, connect the new WireGuard exit, then tap **Verify owner login through VPN** in its Private Chat card. Record pass/fail without exposing the account password or session token.

## 6. What to send back

Send one report containing:

- device model, Android version, ZeroVPN version, Oracle region, and test date/time;
- whether VM/WireGuard provisioning completed;
- whether every Private Chat stage started and how each ended;
- stage durations, especially packages, Synapse, TLS, health, and encrypted self-test;
- overall node health and each Diagnostics component result;
- whether the owner account exists;
- whether the manifest fields are present and internally consistent;
- whether chat-only policy chains are ready and peer rules are correctly reported inactive for Phase 1;
- last self-test result;
- whether ordinary WireGuard connect/disconnect still works;
- whether **Verify owner login through VPN** passed, failed, or was not run;
- every visible error message exactly as shown, plus which button/action preceded it;
- whether **Retry** resumed completed stages rather than visibly replaying all work;
- the requested screenshots.

Use this compact result template:

```text
APK SHA-256 verified: yes/no
Device / Android:
Oracle region:
VM + WireGuard: pass/fail
Private Chat install: pass/fail
Failed stage (if any):
Retry result: pass/fail/not-run
PostgreSQL: pass/fail/not-checked
Synapse: pass/fail/not-checked
TLS endpoint: pass/fail/not-checked
Matrix /versions: pass/fail/not-checked
Owner account: exists/missing/not-checked
Private firewall: active/fail/not-checked
Chat-only peer rules active: yes/no/not-checked
Encrypted self-test: pass/fail/not-run
Owner login through VPN: pass/fail/not-run
WireGuard survived chat failure/removal: yes/no/not-tested
Exact visible error(s):
Screenshot filenames:
Notes:
```

## 7. Roll back Private Chat while keeping the VPN

Use this path if chat installation fails or the node health is unacceptable:

1. Open **Home** and select the disposable Oracle exit.
2. In its **Private Chat** section, tap **Remove**. On a provisioning-result failure screen, **Remove Chat** is the equivalent action.
3. Read the **Remove Private Chat?** confirmation. It must say Synapse, the dedicated database, TLS identity, and owner Matrix data are removed while the WireGuard exit/profile stays available.
4. Tap **Remove Chat**. Do not tap **Destroy Node** or the exit's Oracle destruction action.
5. Wait for removal to finish. If it fails, capture the redacted error and retry removal; do not manually edit WireGuard.
6. Confirm the Oracle exit is still listed on Home.
7. Connect that exit and verify ordinary VPN traffic still works. Record the observed result; this is the required runtime WireGuard-survival evidence.
8. Reopen **Diagnostics** and confirm **Private Chat installed: No** while the connection diagnostics still identify the retained exit.
9. Only after evidence is captured, destroy the disposable Oracle VM through ZeroVPN's normal lifecycle flow if the test is finished and Aaron intends to remove the whole exit.

Removal is intentionally chat-scoped. It must not edit `/etc/wireguard`, stop
`wg-quick@wg0`, delete OCI resources, or remove the Android VPN profile. The
runtime test above is required to demonstrate that behavior on Aaron's node.
