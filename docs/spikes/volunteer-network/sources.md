# Sources

Primary sources used for this spike.

## Orbot and Guardian Project

* Guardian Project Orbot Android:
  https://github.com/guardianproject/orbot-android

  Orbot is Guardian Project's Android Tor app. Its README describes Orbot as a
  VPN and proxy app for Android and shows that current builds use
  `hev-socks5-tunnel` as a native dependency.

* Guardian Project tor-android:
  https://github.com/guardianproject/tor-android

  Android Tor binary and library packaging. Relevant for any embedded Tor path.

* Guardian Project Orbot-IPtProxy:
  https://github.com/guardianproject/Orbot-IPtProxy

  Pluggable transport component used around Orbot-style anti-censorship
  routing. Review licensing and integration details before embedding anything.

## tun2socks Candidates

* Outline Foundation outline-go-tun2socks:
  https://github.com/OutlineFoundation/outline-go-tun2socks

  TUN-to-SOCKS implementation used by Outline-related projects. Candidate for
  Android VpnService plus local SOCKS routing research.

* hev-socks5-tunnel:
  https://github.com/heiher/hev-socks5-tunnel

  Lightweight tun2socks-style project. Orbot's Android README references it as
  a native dependency, making it directly relevant to Orbot-style architecture.

* xjasonlyu tun2socks:
  https://github.com/xjasonlyu/tun2socks

  A tun2socks implementation powered by the gVisor TCP/IP stack. Candidate for
  comparison on Android packaging, licensing, DNS, and UDP behavior.

## Android Platform

* Android VpnService API:
  https://developer.android.com/reference/android/net/VpnService

  Official Android API for building VPN clients. Important constraint: this is
  the platform surface ZeroVPN must use for full-device routing.

## Tor Project Direction

* Tor Project Onionmasq:
  https://gitlab.torproject.org/tpo/core/onionmasq

  Experimental tunnel interface for Arti. Relevant long-term reference for a
  Tor-derived VPN/tunnel architecture.

* Tor Project VPN for Android:
  https://gitlab.torproject.org/tpo/applications/vpn

  Tor Project's Android VPN work. The repository describes the project as
  subject to change and currently lists BSD 3-Clause licensing.

* Tor Project Arti:
  https://gitlab.torproject.org/tpo/core/arti

  Rust implementation of Tor. Relevant if ZeroVPN later evaluates an Arti-based
  embedded path.

* Tor Project Arti 1.0 announcement:
  https://blog.torproject.org/arti_100_released/

  Notes that Arti reached a production-use milestone for SOCKS proxy use,
  mentions Android portability work, and warns that conventional browsers can
  leak identifying information when pointed at Tor/Arti. This supports cautious
  ZeroVPN product language.

