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

  Android Tor binary and library packaging. This is the Phase 1 dependency
  candidate for the embedded Tor SOCKS proof of concept. The current README
  documents `info.guardianproject:tor-android:0.4.9.9` and `jtorctl`, API 24
  minimum, standard Android ABIs (`arm64-v8a`, `armeabi-v7a`, `x86`,
  `x86_64`), and `INTERNET` permission. Maven Central currently publishes
  `info.guardianproject:tor-android:0.4.9.9.1`; its POM lists BSD 3-Clause,
  `androidx.localbroadcastmanager:localbroadcastmanager:1.1.0`,
  `info.guardianproject:jtorctl:0.4.5.7`, and Kotlin stdlib as compile
  dependencies.

* Maven Central tor-android metadata:
  https://repo1.maven.org/maven2/info/guardianproject/tor-android/maven-metadata.xml

  Used to confirm the available Maven version for the embedded dependency.

* Maven Central tor-android 0.4.9.9.1 POM:
  https://repo1.maven.org/maven2/info/guardianproject/tor-android/0.4.9.9.1/tor-android-0.4.9.9.1.pom

  Used to confirm dependency metadata and declared license.

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
  Its README describes Android support, SOCKS5 configuration, TCP redirection,
  UDP relay modes, optional map-DNS configuration, and a C API that accepts a
  TUN file descriptor. License: MIT.

* SocksTun:
  https://github.com/heiher/sockstun

  Android VPN-over-SOCKS app built on `hev-socks5-tunnel`. Useful prior art for
  Android `VpnService.Builder`, TUN fd handoff, configuration generation, and
  stop sequencing. It builds native code with `ndk-build` and a git submodule
  rather than a Maven AAR. License: MIT.

* xjasonlyu tun2socks:
  https://github.com/xjasonlyu/tun2socks

  A tun2socks implementation powered by the gVisor TCP/IP stack. Candidate for
  comparison on Android packaging, licensing, DNS, and UDP behavior. Its README
  describes SOCKS support and user-space networking. License: MIT.

* Outline Foundation outline-go-tun2socks:
  https://github.com/OutlineFoundation/outline-go-tun2socks

  The repository README says this repo is no longer maintained and points to
  Outline/Intra repositories. It remains useful historical prior art for
  Android Go tun2socks builds. License: Apache 2.0.

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
