# Volunteer VPN Device Validation

Date: 2026-06-22

Result: the Developer Mode-only Volunteer VPN spike has proven the end-to-end
browser path on a real Android device.

## Path Proven

Chrome traffic successfully used:

```text
Chrome -> Android VpnService TUN -> HEV tun2socks -> embedded Tor SOCKS -> Tor exit
```

This validates the current self-contained direction for the spike:

* embedded Tor starts inside ZeroVPN
* HEV native source loads on device
* Android VpnService creates the TUN interface
* HEV bridges TUN traffic to local Tor SOCKS at `127.0.0.1:9050`
* another app, Chrome, can reach the Tor check page through that route

This is not product-ready Volunteer Network mode.

## Evidence

While the Volunteer VPN test was running:

* embedded Tor bootstrapped successfully
* HEV native loaded on `arm64-v8a`
* Android VPN key appeared
* diagnostics showed `androidVpnActiveDetected=true`
* diagnostics showed `tunCreated=true`
* diagnostics showed `tunFdOpen=true`
* diagnostics showed `hevRunning=true`
* diagnostics showed `zeroVpnAppIncludedInVolunteerVpn=false`
* diagnostics showed `zeroVpnAppExcludedFromVolunteerVpn=true`
* diagnostics showed `appExclusionError=N/A`
* diagnostics showed `torSocksBaselineStatus=HTTP 200 IsTor=true`
* Chrome opened `https://check.torproject.org/`
* Chrome reported: `Congratulations. This browser is configured to use Tor.`
* Chrome showed Tor exit IP `203.55.81.1`

ZeroVPN is intentionally excluded from the Volunteer VPN route so it can control
embedded Tor and HEV from outside the test TUN path. Browser validation is the
current data-path proof.

## Stop Validation

After pressing Stop VPN test:

* Android VPN key disappeared
* diagnostics showed `vpnServiceStarted=false`
* diagnostics showed `tunFdOpen=false`
* diagnostics showed `hevRunning=false`
* diagnostics showed `androidVpnActiveDetected=false`
* diagnostics showed `stoppedAt` populated
* diagnostics showed `stopDurationMs=292`
* Chrome opened `https://check.torproject.org/`
* Chrome reported: `Sorry. You are not using Tor.`
* Chrome showed normal IP `79.77.77.175`

The remaining polish fix is that final Volunteer VPN diagnostics must label the
completed state as `Stopped`, not `Stopping`, after cleanup finishes.

## Current Caveats

* Developer Mode-only.
* No normal user UI.
* No release or main branch merge.
* DNS mode remains `dnsMode=hev-map-dns-through-socks`.
* DNS leak risk remains `dnsLeakRisk=unknown`.
* UDP remains `udpMode=unsupported-not-validated-tor-socks`.
* Lifecycle and restart recovery still need production hardening.
* The result does not imply anonymity guarantees or Tor Browser-equivalent
  protection.
