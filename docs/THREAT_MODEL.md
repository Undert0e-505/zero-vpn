# Threat Model

## What ZeroVPN protects against

- **Local network observers** — when VPN is active, the local network
  only sees encrypted WireGuard UDP to the exit node.
- **ISP traffic inspection** — the ISP sees encrypted traffic to one
  endpoint, not individual destination sites.
- **UK IP geolocation gating** — traffic exits from a non-UK IP, so
  IP-based geolocation does not return the UK.
- **Repeated identity document exposure** — where a non-UK IP is
  sufficient to bypass a platform gate, the user avoids repeated KYC.
- **Casual DNS leaks** — DNS is routed through the tunnel when
  configured correctly.

## What ZeroVPN does NOT protect against

- **Malicious or careless exit-node operators** — the operator can see
  all destination traffic (plaintext where HTTPS is not used).
- **Cloud provider logs** — the cloud provider has metadata, billing,
  and potentially traffic logs.
- **Platform account metadata** — a VPN does not hide account login
  history, payment details, or prior device fingerprints.
- **App-level telemetry** — installed apps may leak data independently
  of the VPN tunnel.
- **Phone number / SIM country** — a VPN does not change the SIM or
  phone number country code.
- **GPS / location permissions** — apps with location permission can
  determine real location regardless of VPN.
- **Browser fingerprinting** — canvas, WebGL, font, and other
  fingerprinting vectors are unaffected.
- **Device attestation** — Play Integrity, SafetyNet, and similar
  attestation systems verify the device, not the network.
- **Malware on the phone** — malware with sufficient permissions can
  bypass the VPN or exfiltrate data directly.
- **All forms of age assurance** — a VPN is not an age-verification
  bypass. Age assurance uses multiple signals beyond IP.

## Volunteer Network Mode — additional risks

- **Public exits are slow and often blocked** — destination sites may
  recognise the relay network and challenge or block it.
- **Exit nodes see plaintext** — where HTTPS is not used, the exit
  node can observe traffic content.
- **Not a private endpoint** — the exit IP is shared and may change.
- **Not stable by country** — country constraints reduce reliability.
- **Destination sites may know** — traffic may be flagged as coming
  from a public relay network (e.g. Tor exit lists).

## Trust assumptions

- The phone user trusts the node operator.
- The node operator trusts the cloud provider (or runs their own hardware).
- The project maintainer (code publisher) is trusted to publish clean,
  auditable code. The maintainer does not operate exit nodes.

## What ZeroVPN is not

- Not an anonymity tool.
- Not a Tor Browser replacement.
- Not a dark-web access tool.
- Not an age-verification bypass.
- Not a censorship-evasion transport.
- Not a commercial VPN service.

ZeroVPN is a routing tool. It gives the user control over their traffic
exit path. It is honest about what it can and cannot do.