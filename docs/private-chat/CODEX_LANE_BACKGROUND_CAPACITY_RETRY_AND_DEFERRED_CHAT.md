# Codex Lane: 24-Hour A1 Capacity Retry, VPN-Only Bypass, and Deferred Private Chat Migration

## Mission

Extend ZeroVPN’s Oracle provisioning workflow so that an unavailable A1 Private Chat VM is no longer a dead end.

Implement three connected capabilities:

1. **A durable A1 capacity retry session**
   - After both Private Chat launch configurations fail because Oracle explicitly reports `Out of host capacity`, the user may opt into background retries.
   - Retry on a best-effort 15-minute cadence.
   - Stop at a fixed deadline 24 hours after the user starts the retry session.
   - Survive ordinary app process death, task removal, device restart, and later app relaunch.
   - When the user returns to OCI setup, show how many background retry cycles actually ran and how long remains before the fixed deadline.

2. **An immediate VPN-only bypass**
   - From the A1-capacity failure path, offer to abandon/defer Private Chat and continue with the existing standard VPN-only VM workflow.
   - Do not weaken or overload the standard VPN VM by silently installing the current Synapse/PostgreSQL stack on it.
   - Preserve the user’s intent that Private Chat was deferred and may be added later.

3. **Deferred Private Chat on a parallel replacement candidate**
   - A user who already has a working standard VPN-only exit must later be able to choose **Add Private Chat**.
   - ZeroVPN must provision a second A1-capable VM in parallel using the same preferred 6 GB, compact 4 GB, and optional 24-hour retry workflow.
   - The existing VPN VM must remain active and untouched while the candidate VM is being acquired, configured, tested, or retried.
   - After the candidate VM has a working VPN and Private Chat Node and passes all required checks, present an explicit switch operation.
   - Never switch automatically.
   - Never destroy the old VM automatically.
   - Preserve the old exit as a rollback option until the user explicitly removes it later.

This is an implementation task, not an architecture essay. Read the repository from scratch, adapt to its real state and conventions, implement the complete workflow, test it, document it, build an APK, commit, and push.

---

## Repository and execution requirements

Expected repository:

```text
D:\dev\zero-vpn
```

Expected feature branch:

```text
feature/oracle-private-chat-node
```

Do not assume a particular HEAD commit. Inspect:

```powershell
git status --short
git branch --show-current
git log --oneline --decorate -20
git diff
```

The branch has previously contained Phase 1 Private Chat work and a typed A1 memory fallback. The current implementation should already attempt:

```text
VM.Standard.A1.Flex — 1 OCPU / 6 GB
```

and, only after a definite OCI `Out of host capacity` response:

```text
VM.Standard.A1.Flex — 1 OCPU / 4 GB
```

Locate and preserve that implementation. Verify the actual repository rather than trusting this summary.

Use the actual `codex-lane` skill with:

```text
model: gpt-5.6-sol
reasoning effort: max
```

Requirements:

- Read the codex-lane skill before invoking it.
- Preserve and restore temporary Codex configuration.
- Do not touch unrelated repositories.
- Do not merge into the default branch.
- Do not perform Oracle login, launch a real VM, destroy a real VM, or interact with Aaron’s Android device.
- Aaron performs all authenticated Oracle and real-device tests.
- Do not claim owner-operated runtime validation has passed until Aaron supplies evidence.

---

## Repository orientation before coding

Read the root README, Gradle files, Android manifest, navigation, persistence layer, secure storage, OCI provisioning code, VPN service/configuration code, exit inventory, cleanup/destroy flow, diagnostics, Dev Mode logging, and all Private Chat documentation.

At minimum inspect:

```text
docs/ZEROVPN_PRIVATE_CHAT_NODE_PLAN.md
docs/private-chat/
android/app/src/main/java/com/zerovpn/app/oci/
android/app/src/main/java/com/zerovpn/app/ui/provisioning/
android/app/src/main/java/com/zerovpn/app/ui/diagnostics/
```

Search for:

```text
VM.Standard.A1.Flex
Out of host capacity
VmLaunchFailure
LaunchAttemptResult
WorkManager
ConfiguredExit
SecureSecretStore
PrivateChat
ProvisioningPhase
VM_LAUNCH
opc-retry-token
Retry
Cleanup
Destroy
```

Map and report:

- the current standard VPN-only shape and launch path;
- the current Private Chat A1 launch path;
- how an active exit is selected;
- how multiple configured exits are represented;
- where OCI API credentials and signing keys are stored;
- how provisioning state survives activity recreation;
- whether Room, DataStore, SharedPreferences, files, or another mechanism owns durable state;
- how Dev Mode events are retained;
- how WorkManager is currently configured, if present;
- how resources are tagged or otherwise reconciled after ambiguous OCI responses.

Prefer extending existing abstractions over adding a disconnected second provisioning system.

---

## Existing behaviour that must remain correct

Preserve all of the following:

- Standard VPN-only provisioning continues to use its existing shape, cost warning, networking, WireGuard, diagnostics, cleanup, and destroy flow.
- Private Chat continues to prefer A1 Flex with 1 OCPU and 6 GB RAM.
- The compact 4 GB attempt occurs only after a definite capacity rejection of the 6 GB request.
- Authentication, authorization, quota, malformed request, invalid shape, rate-limit, and ambiguous transport failures must not be misclassified as an A1 memory fallback.
- A VM-launch failure is attributed to `VM_LAUNCH`, not API-key setup.
- Private Chat failure must not remove an already working VPN.
- No paid shape is selected silently.
- No existing VM is resized or recreated silently.
- No resource is destroyed without explicit user intent.
- No secret appears in normal UI, Dev Mode logs, Diagnostics, WorkManager input/output, notifications, or test fixtures.


---

# Part A — Durable 24-hour background capacity retry

## User entry point

After both the 6 GB and 4 GB launch attempts receive the exact capacity classification, present a capacity-specific result with these choices:

```text
Keep trying for 24 hours
Set up VPN only instead
Retry now
Cancel
```

Use labels that fit the existing UI style, but preserve the meaning.

The capacity screen must explain:

- Oracle login and API signing succeeded.
- Oracle had no A1 host capacity for either configuration.
- No VM was created if no instance OCID exists.
- Background work is best-effort and Android may delay an individual run.
- The retry window expires 24 hours after the user starts it.
- The user may stop the retry session at any time.
- The user may choose VPN-only setup without losing the option to add Private Chat later.

Do not tell the user to try another region when the current account’s home region is fixed and Always Free eligibility depends on that home region. Keep the message specific to the actual options ZeroVPN can perform.

## Cadence and deadline

The user requirement is:

```text
Target cadence: every 15 minutes
Retry window: 24 hours
```

Use Android WorkManager or the repository’s established persistent-work abstraction.

Important scheduling semantics:

- Fifteen minutes is a **best-effort target**, not an exact wall-clock promise.
- The fixed deadline is authoritative.
- Store an absolute UTC start time and deadline.
- App relaunch must not reset or extend the deadline.
- Manual viewing of the OCI setup screen must not reset or extend the deadline.
- A delayed worker that wakes after the deadline must not launch a new VM.
- When the deadline passes, cancel future work and mark the session `TIMED_OUT`.
- A new 24-hour session requires an explicit new user action.
- Use unique work so there cannot be two active retry workers for the same candidate/session.
- Work must require network connectivity.
- Do not require the app process or UI to remain alive.
- Normal task removal/process death must not lose state.
- After device restart, WorkManager and persisted state must reconcile.
- Android force-stop may prevent background execution until the user opens the app; the persisted deadline must still continue to elapse.

Choose either:

- one unique 15-minute periodic worker that checks the deadline every run; or
- a chain of unique one-time workers that schedules the next eligible run.

Select the design that best matches the current app and is easiest to test. Document the choice. Do not use exact alarms.

## Retry session state

Create a durable, versioned state model using the repository’s existing persistence conventions.

Suggested conceptual states:

```text
NONE
ACTIVE
PAUSED_AUTH_REQUIRED
ACQUIRING
INSTANCE_ACQUIRED
RESUME_PROVISIONING_REQUIRED
SUCCEEDED
TIMED_OUT
CANCELLED
FAILED_TERMINAL
FAILED_AMBIGUOUS_RECONCILIATION_REQUIRED
```

Suggested durable fields:

```text
sessionId
candidateId
mode
createdAtUtc
deadlineUtc
nextEligibleAttemptAtUtc
lastWorkerStartedAtUtc
lastWorkerFinishedAtUtc
retryCycleCount
launchRequestCount6Gb
launchRequestCount4Gb
lastAttemptMemoryGb
lastHttpStatus
lastOciErrorCode
lastSafeErrorCategory
lastRedactedRequestId
lastResult
currentWorkId or uniqueWorkName
preferredRetryToken
compactRetryToken
instanceOcid if acquired
instanceDisplayName
availabilityDomain
compartmentOcid reference
sourceExitId when this is a deferred-chat replacement
requiresUserAction
terminalReason
```

Adapt names to the actual codebase. Do not store secrets in this record.

Define counts precisely:

- `retryCycleCount` increments only when a background worker actually reaches the OCI launch cycle.
- The original foreground 6 GB/4 GB attempt is not a background retry cycle.
- Track 6 GB and 4 GB launch request counts separately for diagnosis.
- The user-facing primary count is **background retries attempted**.
- Diagnostics may additionally show total OCI launch requests.

Persist the state before enqueuing work and update it transactionally around each attempt.

## OCI launch behaviour in each background cycle

Each background cycle must reuse the already implemented Private Chat launch policy:

```text
Attempt A: A1 Flex, 1 OCPU, 6 GB
  success -> persist the instance immediately and stop retrying
  exact out-of-host-capacity -> Attempt B
  any other result -> follow the explicit error policy below

Attempt B: A1 Flex, 1 OCPU, 4 GB
  success -> persist the instance immediately and stop retrying
  exact out-of-host-capacity -> record cycle failure and wait for the next 15-minute window
  any other result -> follow the explicit error policy below
```

The capacity classifier must remain narrow. Require all relevant elements from the real response, including the OCI error code and capacity message. Do not trigger compact fallback from a generic HTTP 500.

The worker must not redo browser login. It may use only the app’s already persisted, valid OCI signing configuration through the same secure path as foreground provisioning.

Do not put private key material, API key contents, auth headers, signatures, or full provisioning payloads into WorkManager `Data`.

## Idempotency and duplicate-VM protection

Background launch code must be safe across:

- process death during a request;
- a lost HTTP response;
- worker cancellation;
- device reboot;
- WorkManager redelivery;
- app relaunch;
- user opening the provisioning screen while work is running.

Use OCI `opc-retry-token` correctly.

Rules:

1. A definite `Out of host capacity` response means that logical launch failed without creating the requested instance. The next scheduled cycle may use a new logical retry token.
2. A timeout, connection loss, process death, or ambiguous server response must not immediately generate a fresh launch request.
3. For an ambiguous result:
   - retain the same logical retry token;
   - reconcile against OCI using deterministic ZeroVPN tags, display name, session ID, or another existing repository mechanism;
   - adopt exactly one matching instance if one was created;
   - surface a reconciliation-required state rather than risking a duplicate if certainty is not possible.
4. On a successful launch response:
   - persist the instance OCID and candidate identity before any subsequent setup;
   - cancel the capacity retry work;
   - reconcile once more if persistence was interrupted.
5. Before every new logical launch, check for an existing instance belonging to the same candidate/session.
6. Never create both a 6 GB and 4 GB VM because of a timeout race.
7. Never create multiple candidate VMs because the user reopened the app or tapped the action twice.

Add deterministic tags or metadata if the current implementation lacks sufficient reconciliation identifiers. Do not expose those tags as secrets.

## What happens after an instance is acquired in background

Keep the capacity-acquisition worker bounded and reliable.

Preferred minimum behaviour:

1. Persist the instance OCID and candidate state.
2. Cancel further capacity retry work.
3. Post a notification:
   - `Oracle VM acquired`
   - `Open ZeroVPN to continue Private Chat setup`
4. On next app open, resume the existing idempotent provisioning state machine from the correct stage.

Only continue SSH/server installation fully in background if the current repository already has a safe, tested, resumable background mechanism suitable for that work. Do not turn a short WorkManager capacity worker into an unbounded provisioning process merely for convenience.

## Timeout and cancellation

At 24 hours:

- mark the retry session `TIMED_OUT`;
- stop future work;
- notify the user once;
- show the final number of completed background retry cycles;
- show the final attempt time and safe result;
- do not create or destroy anything;
- offer:
  - start a new 24-hour retry session;
  - set up VPN only;
  - cancel/defer.

When the user presses **Stop retrying**:

- cancel unique work;
- mark the session `CANCELLED`;
- preserve diagnostic history;
- do not delete OCI credentials;
- do not delete an acquired instance;
- if a request is ambiguous, reconcile before declaring that no resource exists.


---

# Part B — Relaunch and OCI setup status

When the app is relaunched and the user enters the existing OCI setup flow, detect any persisted retry session and show a status card before ordinary provisioning controls.

For an active session show:

```text
Private Chat capacity retry active
Background retries attempted: N
Last attempt: <local time or "not yet">
Last result: <safe classification>
Next target attempt: <relative time or "waiting for Android">
Time remaining: <hours/minutes>
Deadline: <local date/time>
Preferred: A1 Flex, 1 OCPU / 6 GB
Fallback: A1 Flex, 1 OCPU / 4 GB
```

Actions:

```text
Stop retrying
Set up VPN only instead
View Dev Mode log
```

A manual `Retry now` action may be retained if the existing UI already supports it, but it must use the same unique-session lock and duplicate protection.

For timed-out, cancelled, terminal-failure, auth-required, ambiguous, or acquired states, show the correct state-specific explanation and action.

The countdown must be calculated from the persisted absolute deadline. It must not be reconstructed from a WorkManager estimate.

If WorkManager reports no matching work while durable state says `ACTIVE`, reconcile and re-enqueue only when:

- the deadline has not passed;
- no instance has been acquired;
- no terminal or ambiguous state blocks safe retry;
- the user has not cancelled.

---

# Part C — VPN-only bypass

## Capacity failure action

From the Private Chat A1 capacity failure screen, provide:

```text
Set up VPN only instead
```

This must:

- stop/cancel any active Private Chat capacity retry after clear confirmation;
- use the existing standard VPN-only provisioning path and existing standard VM shape;
- preserve current cost/Free Tier warnings;
- not attempt A1;
- not install PostgreSQL, Synapse, private TLS, or Private Chat server components;
- not change the established VPN-only diagnostics or cleanup semantics;
- record that Private Chat was deferred rather than permanently unavailable.

The UI should explain:

```text
ZeroVPN will create the normal VPN exit now.
Private Chat will not be installed on this VM.
You can add Private Chat later. ZeroVPN will create a second capable VM,
test it in parallel, and ask before switching away from this VPN.
```

Do not call the standard VM a chat-capable VM.

## State after VPN-only success

The configured VPN-only exit should expose an action in the most appropriate existing screen:

```text
Add Private Chat
```

Place it where users manage an existing exit or Private Chat capability. Do not create a hidden developer-only route.

Persist capability/state explicitly, for example:

```text
privateChatStatus = DEFERRED
```

Use the repository’s actual model and migration strategy.

---

# Part D — Add Private Chat later using a parallel candidate VM

## Starting deferred setup

When a user with a working standard VPN-only exit selects **Add Private Chat**:

- do not modify, resize, stop, or destroy the current VM;
- create a candidate provisioning record linked to the current exit;
- explain that a second capable VM will be created;
- show the requested A1 6 GB configuration and 4 GB capacity fallback;
- preserve Oracle cost/eligibility confirmation;
- reuse the same foreground attempt and optional 24-hour background retry workflow;
- allow the user to leave and return without losing state.

The candidate VM must be independently identifiable from the active exit.

Suggested conceptual relationship:

```text
active VPN-only exit
    sourceExitId = existing exit
    remains connected and selectable

candidate VPN + Private Chat exit
    candidateId = new record
    lifecycle = acquiring/provisioning/testing/ready/failed
```

Do not overwrite the active exit’s OCID, IP, WireGuard configuration, keys, diagnostics, or destroy metadata while the candidate is incomplete.

## Candidate provisioning

Once the candidate A1 VM is acquired, provision it through the existing full workflow:

1. Oracle networking and VM baseline.
2. WireGuard exit.
3. Verify VPN connectivity and expected regional exit IP.
4. Private Chat prerequisites.
5. PostgreSQL.
6. Synapse.
7. Private TLS and pinning.
8. Firewall/network isolation.
9. Owner account.
10. Real encrypted Matrix self-test where the existing design supports it.
11. Diagnostics and manifest validation.

Use the existing idempotent stages. Do not invent a parallel server installer.

The original VPN-only exit must remain usable throughout.

If candidate provisioning fails:

- keep the original exit active;
- preserve candidate diagnostics;
- offer resume/retry/cleanup for the candidate only;
- never point cleanup at the original exit;
- never delete the original VM as part of candidate rollback.

## Ready-to-switch gate

The candidate may become `READY_TO_SWITCH` only after all required checks pass.

At minimum:

- candidate instance exists;
- candidate WireGuard service is healthy;
- device can establish the candidate VPN;
- candidate exit IP is verified;
- existing VPN diagnostics pass on the candidate;
- Private Chat services pass required health checks;
- private TLS pin matches;
- Matrix is reachable only through the intended private path;
- encrypted self-test passes;
- no prohibited public exposure is detected;
- all candidate secrets and metadata are persisted safely.

Static configuration checks are not a substitute for runtime checks. On Aaron’s device, the final owner-operated test supplies the runtime proof.

## Explicit switch

Present a review screen:

```text
New VPN + Private Chat VM is ready

Current active VPN:
<existing exit>

New candidate:
<A1 candidate summary>

Switching changes the active ZeroVPN exit.
The old VM will be kept for rollback and will not be destroyed.
```

Actions:

```text
Switch to new VPN + Chat VM
Keep current VPN for now
View diagnostics
```

Switch requirements:

- never automatic;
- atomically update the selected/active exit only after candidate configuration is fully persisted;
- disconnect/reconnect using existing VPN service APIs;
- verify handshake and exit IP after the switch;
- if activation fails, restore the old active exit automatically;
- report rollback clearly;
- do not destroy either VM;
- preserve both exits in inventory;
- mark the old exit as previous/rollback-capable, not deleted;
- make later destruction an explicit, separately confirmed action.

Do not silently migrate Matrix identity or discard the candidate on a failed switch.

## Multiple candidates and repeated actions

Prevent:

- multiple active background retry sessions for the same source exit;
- multiple candidate A1 VMs from repeated taps;
- starting a second migration while one is active;
- deleting the wrong VM;
- confusing candidate diagnostics with active-exit diagnostics.

If another candidate already exists, route the user to its status instead of creating a new one.


---

# Part E — Error policy

Use typed errors/results at the provisioning boundary. Avoid UI-layer string matching where practical.

## Continue the 15-minute capacity session

Continue to the next scheduled cycle after a definite final result that both A1 configurations are out of host capacity.

## Pause and require user action

Pause and surface a clear action for:

- missing or unusable OCI signing material;
- revoked API key;
- authentication refresh required;
- user needs to complete browser interaction;
- permissions changed.

A background worker must never launch a browser or fake success.

## Stop as terminal failure

Stop the session for clear non-capacity failures such as:

- authorization denied;
- invalid compartment or tenancy;
- service limit/quota exceeded;
- invalid shape configuration;
- incompatible image/architecture;
- malformed request;
- billing/cost safeguard failure;
- policy denial;
- user cancellation.

Do not retry every 15 minutes for 24 hours when the error cannot be fixed by host capacity changing.

## Ambiguous result

For timeouts, lost responses, process death during request, or uncertain OCI state:

- do not advance to a fresh logical launch;
- use the same `opc-retry-token`;
- reconcile by deterministic candidate/session identifiers;
- adopt the resource if found;
- otherwise require safe reconciliation;
- never risk duplicate chargeable or free-tier-consuming resources.

## Rate limiting and transient service failures

Handle these deliberately and document the decision.

They must not trigger the 6 GB to 4 GB memory fallback. They may defer the current cycle safely if idempotency is preserved, but must not create a tight retry loop or bypass the fixed 15-minute cadence.

---

# Part F — Dev Mode, Diagnostics, and notifications

## Dev Mode logging

Extend the existing on-screen Dev Mode log. Do not create a separate logging product.

Log safely:

```text
Capacity retry session created
Session ID abbreviated
Deadline
Background retry cycle number
6 GB launch started
6 GB capacity result
4 GB launch started
4 GB capacity result
Next target attempt
Worker delayed/resumed
Worker missing/re-enqueued
Instance acquired
Instance OCID abbreviated
Provisioning resume required
Session timed out
Session cancelled
VPN-only bypass selected
Deferred chat candidate created
Candidate ready to switch
Switch started
Switch succeeded
Switch failed and old exit restored
```

Never log:

- OCI private keys;
- API key material;
- auth headers;
- request signatures;
- WireGuard private keys;
- Matrix tokens/passwords;
- TLS private keys;
- complete reusable request bodies;
- unredacted secrets in exception chains.

## Diagnostics

Extend the existing Diagnostics screen with a coherent section.

Capacity retry:

```text
Retry status
Started
Deadline
Time remaining
Background retry cycles attempted
6 GB launch requests
4 GB launch requests
Last attempted configuration
Last result
Next target attempt
Work scheduled
Instance acquired
Cleanup required
```

Deferred candidate:

```text
Source exit
Candidate state
Candidate instance
Candidate VPN health
Candidate chat health
Ready to switch
Current active exit
Previous rollback exit
```

Use existing status labels such as:

```text
PASS
FAIL
WARNING
NOT TESTED
NOT INSTALLED
```

Do not show VM health when no VM exists.

## Notifications

Use the app’s existing notification channel conventions.

Notify only for meaningful state changes:

- A1 instance acquired; open app to continue.
- Retry paused because authentication/action is required.
- Retry session timed out.
- Candidate is ready to switch.
- Terminal failure requiring attention.

Do not notify every 15-minute capacity miss.

Notifications must not contain secrets, full OCIDs, private IPs, tokens, or detailed exception bodies.

---

# Part G — Persistence, migrations, and security

- Use the existing secure store for sensitive OCI material.
- WorkManager receives only opaque IDs and non-sensitive scheduling metadata.
- Add Room/DataStore migrations where required.
- Migration must preserve existing configured VPN exits.
- Existing users with no retry/candidate state must default cleanly.
- State updates around instance acquisition and active-exit switching must be transactional or otherwise crash-safe.
- Encrypt or protect data according to the repository’s existing model.
- Add no analytics or external telemetry.
- Do not upload user location, Oracle account details, capacity results, or retry history.
- Do not add a backend service for this task.
- Do not weaken TLS, Matrix encryption, WireGuard, firewall, or secret-redaction requirements.


---

# Part H — Automated tests

Add focused tests at the correct layers. Refactor for dependency injection and deterministic clocks/schedulers rather than relying on sleeps.

## Scheduling and persistence

Test:

1. User opt-in creates exactly one active retry session.
2. Deadline is exactly 24 hours from opt-in.
3. Target interval is 15 minutes.
4. Unique work prevents duplicate workers.
5. Process/view-model recreation does not reset state.
6. Repository reconstruction simulating app relaunch preserves count and deadline.
7. WorkManager-state reconciliation re-enqueues missing work safely.
8. No re-enqueue occurs after deadline.
9. No re-enqueue occurs after cancellation.
10. A delayed worker waking after deadline does not call OCI.
11. Retry cycle count increments only for actual background cycles.
12. Relaunch UI reports the correct count.
13. Relaunch UI reports correct remaining time.
14. Timeout produces `TIMED_OUT` and cancels future work.
15. Starting a new session after timeout requires explicit action.

## Capacity and launch safety

Test:

16. Every background cycle tries 6 GB first.
17. 6 GB success does not try 4 GB.
18. Exact 6 GB capacity failure tries 4 GB once.
19. Two capacity failures schedule the next cycle rather than terminal failure.
20. Generic HTTP 500 does not count as host capacity.
21. Generic `InternalError` does not count as host capacity.
22. Authorization/quota/invalid-shape errors stop appropriately.
23. Transport timeout does not create a fresh logical launch.
24. Ambiguous state reuses the same retry token.
25. Reconciliation adopts an existing tagged candidate.
26. Repeated worker delivery cannot create a duplicate.
27. Instance OCID is persisted before retry work is cancelled.
28. Existing foreground 6 GB to 4 GB behaviour remains correct.
29. Standard VPN-only provisioning remains unchanged.

## VPN-only bypass

Test:

30. Capacity failure exposes VPN-only bypass.
31. VPN-only bypass cancels the retry session after confirmation.
32. VPN-only bypass calls the existing standard VPN path.
33. It does not call A1 launch.
34. It does not install Private Chat services.
35. Successful VPN-only setup records Private Chat as deferred.
36. Existing cost confirmation remains required.

## Deferred Private Chat candidate

Test:

37. `Add Private Chat` from a standard exit creates one candidate linked to that exit.
38. Existing active exit remains unchanged.
39. Repeated taps route to the existing candidate.
40. Candidate uses 6 GB then 4 GB capacity logic.
41. Candidate may enter the same 24-hour retry workflow.
42. Candidate failure does not affect the source exit.
43. Candidate cleanup cannot target the source exit.
44. Candidate must pass readiness checks before switch is offered.
45. Switch is never automatic.
46. Successful switch makes the candidate active and retains the old exit.
47. Failed switch restores the old active exit.
48. Neither VM is destroyed by switching.
49. App restart during candidate provisioning resumes the correct state.
50. App restart during switch reconciles to one valid active exit.

## UI, diagnostics, and redaction

Test:

51. Active retry card displays count, deadline, and remaining time.
52. Timed-out and cancelled states display appropriate actions.
53. Diagnostics distinguish active exit and candidate.
54. Dev Mode contains retry/candidate events.
55. Logs, WorkManager data, notifications, and diagnostics contain no secrets.
56. VM-launch failures remain attributed to `VM_LAUNCH`.
57. Genuine API-key failures remain correctly attributed.

Add any additional tests needed by the actual architecture.

---

# Part I — Documentation and owner-operated test packet

Update the relevant existing documents under `docs/private-chat/`, including the real filenames present in the repository.

At minimum document:

- 15-minute best-effort cadence;
- fixed 24-hour deadline;
- WorkManager persistence and Android scheduling limitations;
- retry count semantics;
- exact capacity-only continuation rule;
- `opc-retry-token` and reconciliation strategy;
- timeout, cancellation, auth-required, terminal, and ambiguous states;
- VPN-only bypass;
- deferred Private Chat status;
- parallel candidate VM;
- explicit switch and rollback;
- old VM retention;
- no automatic destruction;
- no paid fallback;
- security and redaction;
- known limitations.

Create or update an operator test packet for Aaron covering:

### Test 1 — Background capacity retry

1. Install the test APK.
2. Enable Dev Mode.
3. Request Private Chat.
4. Reach both 6 GB and 4 GB capacity failures.
5. Select `Keep trying for 24 hours`.
6. Capture the initial status card.
7. Remove the app from recents and leave it.
8. Reopen later and enter OCI setup.
9. Capture retry count, last result, and remaining time.
10. Verify the deadline did not reset.
11. Stop the retry and verify no further work is scheduled.

Do not require waiting the full 24 hours for every development cycle. Provide a debug-only injectable clock or short test window behind an unmistakable developer setting if needed, while production remains fixed at 24 hours/15 minutes.

### Test 2 — VPN-only bypass

1. From capacity failure, choose VPN-only.
2. Confirm the standard VM provisions.
3. Confirm VPN handshake and regional exit IP.
4. Confirm no chat services are reported.
5. Confirm `Add Private Chat` is available later.

### Test 3 — Deferred parallel candidate

1. From the working VPN-only exit, select `Add Private Chat`.
2. Confirm the original VPN remains active.
3. Acquire/provision the candidate when capacity permits.
4. Capture candidate diagnostics.
5. Confirm switch is offered only after readiness.
6. Switch explicitly.
7. Verify VPN handshake and exit IP on the candidate.
8. Verify Private Chat diagnostics and encrypted self-test.
9. Confirm the old VM still exists and is available for rollback.
10. Do not destroy either VM until evidence is captured.

Specify exactly which screenshots and copied logs Aaron should return. State which values must be redacted.

---

# Part J — Validation, artifact, Git, and report

Run the repository’s canonical validation, including at least:

```powershell
.\gradlew.bat clean
.\gradlew.bat test
.\gradlew.bat lint
.\gradlew.bat assembleDebug
python -m pytest -v
git diff --check
git status --short
```

Use the correct working directories.

Also perform:

- secret scan across changed files and generated fixtures;
- review of manifest permissions;
- review that WorkManager dependencies and initialization are correct;
- review that background retry does not require exact-alarm permission;
- review that no foreground service is added without necessity;
- review that existing VPN-only tests still pass;
- review that current Private Chat Phase 1 tests still pass.

Produce a distinctly named APK, for example:

```text
artifacts/zerovpn-private-chat-background-retry-debug.apk
```

Create and verify a SHA-256 checksum file.

Follow the repository’s existing artifact policy. Do not overwrite a stable release artifact ambiguously.

Create logical commits, for example:

1. `Persist private chat capacity retry sessions`
2. `Run bounded A1 retries with WorkManager`
3. `Add VPN-only bypass and deferred chat candidate`
4. `Add explicit candidate switch and rollback`
5. `Add diagnostics tests and operator documentation`

Commit names may differ if the final structure suggests better boundaries.

Push:

```powershell
git push origin feature/oracle-private-chat-node
```

Do not merge.

## Final report

Return:

- actual starting and ending commit;
- branch;
- files changed;
- persistence model and migrations;
- WorkManager design and unique-work policy;
- exact cadence and deadline semantics;
- retry-cycle count definition;
- OCI retry-token/reconciliation design;
- capacity, terminal, auth-required, and ambiguous error policies;
- VPN-only bypass behaviour;
- deferred candidate lifecycle;
- switch and rollback behaviour;
- diagnostics and Dev Mode additions;
- notifications added;
- complete automated test results;
- APK path, byte size, and SHA-256;
- documentation paths;
- exact owner-operated tests Aaron must run next;
- clean Git status;
- pushed-branch confirmation;
- confirmation that no Oracle resources were operated;
- confirmation that temporary Codex configuration was restored;
- confirmation that unrelated repositories were untouched;
- any requirement not completed, stated plainly.

## Acceptance criteria

The lane is complete only when:

- an opted-in capacity retry survives app process death and relaunch;
- the production deadline remains exactly 24 hours from opt-in;
- target retry cadence is 15 minutes, documented as Android best-effort;
- entering OCI setup shows actual background retry count and remaining time;
- no duplicate A1 VM can be created by retries, timeouts, or app relaunch;
- the user can stop retrying;
- the user can choose the existing standard VPN-only workflow;
- a successful standard VPN exit exposes `Add Private Chat`;
- deferred chat provisions a separate candidate without disrupting the active VPN;
- the candidate cannot become active before readiness checks pass;
- switching is explicit and rollback-safe;
- the old VM is retained and never automatically destroyed;
- all existing VPN and Private Chat tests pass;
- diagnostics and logs are useful and secret-safe;
- a test APK and checksum exist;
- changes are committed and pushed;
- owner-operated Oracle/runtime validation is clearly marked as pending.
