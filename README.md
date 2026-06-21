# ZeroVPN

A free, open-source Android VPN and exit-node kit.

ZeroVPN routes Android traffic through an exit node that you control. It is
not a VPN service provider: it does not sell subscriptions, operate shared
exit nodes, collect ZeroVPN accounts, or run a central VPN backend.

## Current status

ZeroVPN is experimental and under active development. The currently working
path is **Oracle Free Exit**: the Android app provisions an Oracle Cloud VM,
configures WireGuard, and connects Android through it.

Other exit types shown in the UI are roadmap items and are not functional yet.
Do not treat the app as polished or production-ready.

What works now:

- Android app.
- Oracle Free Exit provisioning.
- Oracle Cloud Always Free-compatible VM creation.
- OCI networking and security rule setup.
- WireGuard installation and configuration on the VM.
- Android VPN connection through Android `VpnService` and the WireGuard backend.
- User-facing diagnostics for exit IP, country/provider, DNS check, and last
  handshake.
- Developer diagnostics hidden behind Developer Mode.
- Multiple saved OCI exits are in progress in the current branch, but the main
  supported path is still Oracle Free Exit.

Not implemented or not polished yet:

- Volunteer Network.
- Scan QR Invite.
- Import Config.
- Create Private Node.
- Logs, where shown as placeholders.
- Fully polished Oracle signup, login, MFA/2FA, and browser return flow.

## Important: prepare your Oracle account first

At the moment, the hardest part of using ZeroVPN is not the VPN tunnel. It is
getting through Oracle's account signup, login, and two-factor authentication
flow.

ZeroVPN does not create your Oracle account for you. It does not handle your
Oracle password. It does not set up Oracle MFA/2FA for you.

To avoid frustration, create and verify your Oracle Cloud account before
installing or testing ZeroVPN.

Official Oracle links:

- Oracle Cloud Free Tier signup: https://signup.oraclecloud.com/
- Oracle Cloud Free Tier overview: https://www.oracle.com/cloud/free/
- Oracle MFA documentation: https://docs.oracle.com/en-us/iaas/Content/Identity/Tasks/usingmfa.htm
- Oracle Cloud Console: https://cloud.oracle.com/

Oracle may require email verification, phone verification, payment-card
verification, region selection, identity checks, and MFA/2FA setup. ZeroVPN does
not control Oracle's account, billing, verification, capacity, or MFA systems.
Oracle does not approve every signup, Free Tier capacity is not guaranteed in
every region, and signup may not be quick.

## Recommended setup before using ZeroVPN

1. Create an Oracle Cloud Free Tier account using Oracle's official signup page:
   https://signup.oraclecloud.com/
2. Complete any email, phone, payment-card, region, or identity checks Oracle
   requires.
3. Sign in to the Oracle Cloud Console at least once:
   https://cloud.oracle.com/
4. Complete Oracle MFA/2FA setup if prompted.
5. Confirm you can reach the Oracle Cloud Console normally in a browser.
6. Then install or open ZeroVPN and choose **Create Oracle Free Exit**.
7. If asked, choose the existing-account/sign-in path.

Do not create extra Oracle accounts just for experimentation. Use Oracle's
services within Oracle's terms and limits.

## Oracle onboarding is not polished yet

Existing Oracle users should have the smoothest experience. New users should
sign up for Oracle Cloud before using the app.

The Oracle signup/login return journey is still rough. If the browser does not
return to ZeroVPN automatically after Oracle login or MFA, switch back to
ZeroVPN manually from Android recent apps and continue from there.

This is known roughness and not the intended final UX. ZeroVPN should not leave
users guessing, but this part of the app is still being improved.

## What ZeroVPN does after Oracle login

After Oracle login, ZeroVPN uses Oracle API access to create the cloud resources
needed for an exit:

- Creates a small VM intended to fit Oracle Always Free usage.
- Configures OCI networking and firewall/security rules.
- Installs and configures WireGuard on the VM.
- Creates a WireGuard client profile for Android.
- Connects the device through that VM.

## What ZeroVPN does not do

- It does not create your Oracle account.
- It does not bypass Oracle login.
- It does not bypass Oracle MFA/2FA.
- It does not guarantee Oracle Free Tier approval or capacity.
- It does not hide the fact that traffic exits from your Oracle VM.
- It does not currently provide the Volunteer Network, Scan QR Invite, Import
  Config, or Create Private Node modes.

## Exit types

1. **Oracle Free Exit** - working/current main path.
2. **Volunteer Network** - planned / not implemented.
3. **Scan QR Invite** - planned / not implemented.
4. **Import Config** - planned / not implemented.
5. **Create Private Node** - planned / not implemented.

## Privacy and responsibility

ZeroVPN creates a personal exit in your own Oracle account. Traffic exits
through that VM. HTTPS still protects encrypted website and app content, but the
destination service and network observers may see the Oracle VM as the source IP.

DNS is currently configured through the VPN path, but use diagnostics to confirm
behavior on your device and network.

You are responsible for your Oracle account and any cloud resources created in
it. Destroy exits when you no longer need them.

## Developer status

Developer Mode hides raw WireGuard and SSH diagnostics from normal users. Debug
views and private-key displays are development aids and should not be treated as
production UX.

The project is under active development. Expect rough edges, especially around
Oracle account onboarding and browser return behavior.

## Building

Android build notes are in `docs/ANDROID_BUILD.md`.

From the Android project directory:

```powershell
cd android
.\gradlew.bat assembleDebug
```

## License

MIT (see `LICENSE`). Dependencies may carry their own licenses; a full license
review should be done before release.
