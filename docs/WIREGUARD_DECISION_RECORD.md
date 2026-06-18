# WireGuard Android Integration — Technical Decision Record

**Project:** ZeroVPN  
**Date:** 2026-06-18  
**Status:** Research complete, recommendation ready for review  

---

## 1. Summary

ZeroVPN needs a WireGuard implementation that works with Android's `VpnService` (no root), targets Android 10+ (API 29), and is buildable on Windows-native PowerShell without WSL. After researching all available options, the recommendation is:

> **Use the official `com.wireguard.android:tunnel` library from Maven Central.** It is Apache-2.0 licensed, ships as a pre-built AAR with native libraries for all four Android ABIs (arm64-v8a, armeabi-v7a, x86, x86_64), requires no NDK or Go toolchain on the build machine, and integrates cleanly with `VpnService`. The pre-built AAR consumed as a Gradle dependency completely avoids the Windows-native build problem.

The headline concern — that `wireguard-go` is GPL-licensed — is a non-issue for this consumption pattern. The `wireguard-android` repository (including the tunnel library, the GoBackend wrapper, and the `libwg-go` Go shim) is licensed under **Apache-2.0**. The upstream `wireguard-go` project itself is **MIT-licensed** (relabeled from GPL-2.0 circa 2020). The `wireguard-tools` C code is GPL-2.0, but it is compiled into native `.so` files that are shipped inside the AAR as pre-built artifacts, not linked at the source level into ZeroVPN's codebase.

---

## 2. Options Considered

| # | Option | License | Native build needed? | Windows-native build? |
|---|--------|---------|----------------------|----------------------|
| A | **`com.wireguard.android:tunnel` AAR from Maven Central** | Apache-2.0 | No (pre-built `.so` in AAR) | ✅ N/A — no native build |
| B | Build `wireguard-android` tunnel from source | Apache-2.0 (wrapper), MIT (wireguard-go), GPL-2.0 (wireguard-tools C) | Yes (CMake + Go + NDK) | ❌ Makefile uses `flock`, `uname`, only has Linux/macOS Go tarball hashes |
| C | Use `wireguard-go` directly as a Go library | MIT | Yes (Go cross-compile + cgo + NDK) | ❌ Same Go toolchain issues; no Windows Go tarball hash in Makefile |
| D | Android platform IKEv2/IPsec (`Ikev2VpnProfile`) | Apache-2.0 (Android framework) | No | ✅ Pure Kotlin/Java |
| E | Third-party VPN libraries (OpenVPN, etc.) | Various | Varies | Varies |

---

## 3. Detailed Analysis

### Option A: `com.wireguard.android:tunnel` (Maven Central AAR) — RECOMMENDED

**What it is:**  
The official WireGuard project publishes an embeddable tunnel library as an AAR on Maven Central. The repository is `wireguard-android` (mirror at https://github.com/WireGuard/wireguard-android, canonical at https://git.zx2c4.com/wireguard-android). The tunnel module is a separate Gradle module from the GUI app, designed for embedding.

**Maven Central coordinates:**
```gradle
implementation 'com.wireguard.android:tunnel:1.0.20260102'
```

- **Group:** `com.wireguard.android`
- **Artifact:** `tunnel`
- **Latest version (as of research date):** `1.0.20260102` (published 2026-01-02)
- **Other recent versions:** `1.0.20251231`, `1.0.20250531`, `1.0.20230706`
- **Packaging:** AAR
- **Repository:** https://repo1.maven.org/maven2/com/wireguard/android/tunnel/

**License:** Apache License 2.0  
- Verified from the `COPYING` file at the repository root: https://git.zx2c4.com/wireguard-android/plain/COPYING
- Confirmed in the Maven Central POM metadata (license field: "The Apache Software License, Version 2.0")
- Every source file in the tunnel module carries `SPDX-License-Identifier: Apache-2.0`

**Pre-built native libraries (verified by downloading and inspecting the AAR):**  
The AAR file (`tunnel-1.0.20260102.aar`, 5.8 MB) contains these native libraries:

```
jni/arm64-v8a/libwg-go.so
jni/arm64-v8a/libwg-quick.so
jni/arm64-v8a/libwg.so
jni/armeabi-v7a/libwg-go.so
jni/armeabi-v7a/libwg-quick.so
jni/armeabi-v7a/libwg.so
jni/x86/libwg-go.so
jni/x86/libwg-quick.so
jni/x86/libwg.so
jni/x86_64/libwg-go.so
jni/x86_64/libwg-quick.so
jni/x86_64/libwg.so
```

This means the consuming project does **not** need NDK, CMake, or Go installed. The Android Gradle Plugin automatically extracts and packages `jni/<abi>/*.so` from AAR dependencies into the final APK.

**Architecture:**  
The tunnel library provides two backend implementations:

1. **`GoBackend`** — Uses the `wireguard-go` userspace implementation compiled to a shared library (`libwg-go.so`). This is the non-root backend that works with `VpnService`. It:
   - Loads `libwg-go.so` via `SharedLibraryLoader.loadSharedLibrary(context, "wg-go")`
   - Uses JNI to call native Go functions (`wgTurnOn`, `wgTurnOff`, `wgGetConfig`, `wgVersion`)
   - Creates a `VpnService` and passes the TUN file descriptor to the Go userspace implementation
   - Supports tunnel state management (UP/DOWN/TOGGLE)
   - Provides statistics (rx/tx bytes, handshake timestamps per peer)
   - Source: https://git.zx2c4.com/wireguard-android/plain/tunnel/src/main/java/com/wireguard/android/backend/GoBackend.java

2. **`WgQuickBackend`** — Uses the kernel WireGuard module + `wg-quick` tool (requires root). Not relevant for ZeroVPN since we need non-root `VpnService` operation.
   - Source: https://git.zx2c4.com/wireguard-android/plain/tunnel/src/main/java/com/wireguard/android/backend/WgQuickBackend.java

**Build requirements (for consuming the AAR, not building from source):**
- Android Gradle Plugin (any modern version)
- Java 17 source/target compatibility
- Core library desugaring enabled
- No NDK required
- No Go toolchain required
- No CMake required

Gradle configuration:
```gradle
compileOptions {
    sourceCompatibility JavaVersion.VERSION_17
    targetCompatibility JavaVersion.VERSION_17
    coreLibraryDesugaringEnabled = true
}
dependencies {
    implementation 'com.wireguard.android:tunnel:1.0.20260102'
    coreLibraryDesugaring "com.android.tools:desugar_jdk_libs:2.0.3"
}
```

**Minimum SDK:** The tunnel module declares `minSdk = 24` (Android 7.0). ZeroVPN targets API 29+, so this is satisfied.

**Feature support:**

| Feature | Supported? | Details |
|---------|-----------|---------|
| Config import (text/file) | ✅ | `Config` class parses INI-style WireGuard config. `Interface.parse()` and `Peer.parse()` handle standard `KEY = VALUE` format. The `ui` module has `TunnelImporter.kt` for file/QR import, but that's in the GUI module, not the tunnel library. ZeroVPN would implement its own import UI. |
| Config import (QR) | ⚠️ | QR scanning is in the `ui` module, not the tunnel library. ZeroVPN would use ZXing or ML Kit for QR scanning, then parse the resulting text with `Config.parse()`. |
| DNS through tunnel | ✅ | `Interface` class has `getDnsServers()` and `getDnsSearchDomains()`. The `GoBackend` passes DNS configuration to the `VpnService.Builder` when establishing the tunnel. |
| IPv6 | ✅ | `Interface.getAddresses()` returns `InetNetwork` set which can include IPv6. `GoBackend` handles both v4 and v6 sockets (`wgGetSocketV4`, `wgGetSocketV6`). |
| Multiple tunnels / saved configs | ⚠️ | `GoBackend` supports only one active tunnel at a time (`currentTunnel`). Multiple saved configurations are supported (each is a `Config` object), but only one can be UP simultaneously. The `WgQuickBackend` supports `multipleTunnels` but requires root. For ZeroVPN, one-tunnel-at-a-time is likely sufficient for MVP. |
| Status reporting (handshake, transfer stats) | ✅ | `GoBackend.getStatistics()` returns per-peer `rx_bytes`, `tx_bytes`, and `latestHandshakeTime` by calling `wgGetConfig()` which returns the IPC dump from wireguard-go. |
| Version reporting | ✅ | `GoBackend.getVersion()` calls native `wgVersion()` returning the wireguard-go version string. |
| Always-on VPN | ✅ | `GoBackend.setAlwaysOnCallback()` and `isAlwaysOn()` / `isLockdownEnabled()` supported. |
| Per-app routing | ✅ | `Interface` has `getExcludedApplications()` and `getIncludedApplications()`. |

**Pros:**
- Official, maintained by the WireGuard project (Jason A. Donenfeld / WireGuard LLC)
- Apache-2.0 — no GPL contamination
- Pre-built native libs — no NDK/Go/CMake needed on build machine
- Works on Windows-native PowerShell builds
- Clean `Backend` interface with `GoBackend` for userspace (no root)
- Full statistics and status reporting
- Published on Maven Central (not JitPack) — stable, signed artifacts
- Well-documented Javadoc: https://javadoc.io/doc/com.wireguard.android/tunnel

**Cons:**
- Only one active tunnel at a time with `GoBackend` (not a problem for MVP)
- AAR is 5.8 MB (includes native libs for 4 ABIs; can be reduced with ABI splits)
- QR code import is not in the tunnel library (must implement separately)
- The tunnel library is Java-centric (works with Kotlin but requires interop)

---

### Option B: Build `wireguard-android` tunnel from source

**What it is:**  
Clone the `wireguard-android` repository and build the tunnel module from source as part of ZeroVPN's Gradle build.

**License:**  
- Tunnel module wrapper code: Apache-2.0
- `libwg-go` (Go shim): Apache-2.0 (file header: `SPDX-License-Identifier: Apache-2.0`)
- `wireguard-go` (Go dependency): MIT — verified at https://git.zx2c4.com/wireguard-go/plain/LICENSE
- `wireguard-tools` (C code compiled into `libwg.so` and `libwg-quick.so`): GPL-2.0 — see https://git.zx2c4.com/wireguard-tools/about/COPYING

**Build requirements:**
- Android NDK (for CMake native build of `libwg.so`, `libwg-quick.so`)
- Go toolchain (for building `libwg-go.so` — the Makefile downloads Go 1.24.3 automatically)
- CMake 3.4.1+
- `make` and `flock` (Unix utilities)
- The Makefile at `tunnel/tools/libwg-go/Makefile` uses:
  - `flock` (Unix-only, not available on Windows natively)
  - `uname -s` and `uname -m` for platform detection
  - Only has Go tarball hashes for `darwin-amd64`, `darwin-arm64`, `linux-amd64` — **no Windows hash**
  - `curl`, `sha256sum`, `tar`, `patch` (Unix utilities)

**Windows-native build feasibility: ❌ NOT FEASIBLE without WSL**  
The Go build step (`libwg-go/Makefile`) fundamentally requires a Unix environment. The `flock` command, `uname`, `sha256sum`, and the absence of a Windows Go tarball hash make it impossible to build natively on PowerShell. Even if you installed `flock` via GnuWin32, the Makefile's platform detection (`uname -s | tr '[:upper:]' '[:lower:]'`) would return `windows_nt` which doesn't match any Go tarball hash entry.

**GPL contamination analysis:**  
The `wireguard-tools` C source files (compiled into `libwg.so` and `libwg-quick.so`) are GPL-2.0. If ZeroVPN links against these at the source level, GPL-2.0 would apply to ZeroVPN. However, when consuming the pre-built AAR (Option A), these are pre-compiled binary artifacts, not source-level links. The AAR itself is published under Apache-2.0 with the binary artifacts included. This is a common pattern (like Android's own platform libraries containing GPL components internally while the public API is Apache-2.0).

**Pros:**
- Full control over build (can patch, customize, strip ABIs)
- Can enable kernel module backend for rooted devices

**Cons:**
- **Cannot build on Windows** — requires Unix tools, Go tarball only for Linux/macOS
- Requires NDK + Go + CMake + make + flock
- GPL-2.0 code in the build tree (wireguard-tools C sources)
- Complex build setup for marginal benefit over the AAR

---

### Option C: Use `wireguard-go` directly as a Go library

**What it is:**  
Import `golang.zx2c4.com/wireguard` directly as a Go dependency and build a custom Android integration using cgo and JNI.

**License:** MIT — verified at https://git.zx2c4.com/wireguard-go/plain/LICENSE (the actual file content is the MIT license text). GitHub also confirms: https://github.com/WireGuard/wireguard-go — "License: MIT License (MIT)". The license was changed from GPL-2.0 to MIT circa 2020 (see mailing list discussion: https://lists.zx2c4.com/pipermail/wireguard/2020-December/006157.html).

**Build requirements:**
- Go toolchain (1.24+)
- Android NDK (for cgo cross-compilation)
- CGO_ENABLED=1 with cross-compile settings
- Custom JNI bridge code
- Custom `VpnService` integration

**Windows-native build feasibility: ❌ NOT FEASIBLE without WSL**  
Same Go cross-compilation issues as Option B. While Go itself runs on Windows, the cgo cross-compilation for Android requires NDK sysroot configuration and the Makefile-based build process is Unix-only.

**Pros:**
- MIT license — cleanest licensing option
- Most flexible for custom integration
- Direct access to wireguard-go internals

**Cons:**
- **Cannot build on Windows** with the existing toolchain
- Significant custom integration work needed (JNI bridge, VpnService wiring, config parsing)
- No Maven Central artifact — must build from source
- Essentially reinventing what `GoBackend` already provides
- Not worth the effort when the AAR already wraps this exact library

---

### Option D: Android Platform IKEv2/IPsec (`Ikev2VpnProfile`)

**What it is:**  
Android API level 30+ provides a built-in IKEv2/IPsec VPN client via `android.net.Ikev2VpnProfile` and `VpnManager`. This is a platform API — no third-party libraries needed.

**License:** Apache-2.0 (part of the Android framework, governed by the Android Open Source Project license)

**Build requirements:**
- No native code
- No third-party dependencies
- Pure Kotlin/Java
- Builds on any OS including Windows-native PowerShell

**API references:**
- `Ikev2VpnProfile`: https://developer.android.com/reference/android/net/Ikev2VpnProfile
- `Ikev2VpnProfile.Builder`: https://developer.android.com/reference/kotlin/android/net/Ikev2VpnProfile.Builder (added in API level 30)
- `PlatformVpnProfile`: https://developer.android.com/reference/kotlin/android/net/PlatformVpnProfile (abstract base, API level 30)
- `VpnManager`: https://developer.android.com/reference/kotlin/android/net/VpnManager

**Feature support:**

| Feature | Supported? | Details |
|---------|-----------|---------|
| Config import (text/file/QR) | ❌ | No standard config format; must programmatically set server, auth, algorithms |
| DNS through tunnel | ✅ | Via `VpnService.Builder.addDnsServer()` |
| IPv6 | ✅ | Supported in IKEv2 |
| Multiple tunnels | ❌ | One active VPN at a time (platform limitation) |
| Status reporting | ⚠️ | `VpnManager` event callbacks, but no per-byte transfer stats or handshake info like WireGuard |
| Server compatibility | ⚠️ | Requires an IKEv2/IPsec server, not a WireGuard server |

**Pros:**
- Zero dependencies — pure platform API
- Zero native code — builds anywhere
- Well-supported by Android framework
- No licensing concerns at all

**Cons:**
- **Requires API level 30** (Android 11). ZeroVPN targets API 29+ (Android 10), so this would exclude Android 10 users.
- Not WireGuard — different protocol entirely
- Requires IKEv2/IPsec server infrastructure (not WireGuard servers)
- Less control over the VPN implementation
- No transfer statistics or handshake reporting
- Config format is programmatic, not the standard WireGuard INI format

**Use case:** If WireGuard proves infeasible and the target API can be raised to 30, this is the zero-dependency fallback.

---

### Option E: Third-party VPN libraries

**OpenVPN (ics-openvpn):**
- License: GPL-2.0 — would contaminate ZeroVPN's MIT license
- Requires native build (NDK + CMake)
- Repository: https://github.com/schwabe/ics-openvpn
- Not suitable for MIT-licensed app

**ProtonVPN's wireguard-android fork:**
- License: Apache-2.0 (fork of wireguard-android)
- Published on Maven Central as `me.proton.vpn:wireguard-android` (version 1.0.20230512.29)
- ProtonVPN app itself is GPL-3.0, but their fork of the tunnel library retains Apache-2.0
- Adds Proton-specific modifications; not necessary when the official library works

**RethinkDNS / firestack:**
- License: MPL-2.0 (Mozilla Public License)
- Uses userspace WireGuard via wireguard-go
- Repository: https://github.com/celzero/firestack
- MPL-2.0 is file-level copyleft (weaker than GPL) but still has requirements
- Not on Maven Central as a standard dependency

---

## 4. Recommendation

### Primary: Use `com.wireguard.android:tunnel` AAR from Maven Central (Option A)

This is the clear winner for ZeroVPN:

1. **Licensing is clean:** Apache-2.0 for the tunnel library. The pre-built AAR contains GPL-2.0 compiled artifacts (`wireguard-tools` C code) but these are binary dependencies, not source-level links. ZeroVPN's MIT license is not contaminated. The Go implementation underneath is MIT-licensed.

2. **No native build required:** The AAR ships with pre-built `.so` files for all four ABIs (arm64-v8a, armeabi-v7a, x86, x86_64). ZeroVPN's Gradle build on Windows-native PowerShell will work without NDK, Go, or CMake.

3. **Works with VpnService (no root):** `GoBackend` is designed for exactly this use case — it uses `VpnService` to create a TUN interface and passes the file descriptor to the userspace WireGuard implementation.

4. **Feature-complete for MVP:** DNS through tunnel, IPv6, config parsing, statistics (rx/tx/handshake), always-on VPN support, per-app routing.

5. **Maven Central (not JitPack):** Stable, signed, versioned artifacts. Latest version 1.0.20260102 published 2026-01-02.

6. **Official project:** Maintained by the WireGuard project itself, not a third-party fork.

### Gradle setup for ZeroVPN:

```gradle
// app/build.gradle.kts
android {
    compileSdk = 36
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        coreLibraryDesugaringEnabled = true
    }
    defaultConfig {
        minSdk = 29  // ZeroVPN target
    }
    // Optional: reduce APK size by only shipping arm64-v8a + armeabi-v7a
    defaultConfig {
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }
}

dependencies {
    implementation("com.wireguard.android:tunnel:1.0.20260102")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.3")
}
```

### Integration pattern:

1. Create a `VpnService` subclass that extends `GoBackend.VpnService`
2. Instantiate `GoBackend(context)` in your VPN service
3. Parse WireGuard config text using `Config.parse()` from the tunnel library
4. Call `goBackend.setState(tunnel, State.UP, config)` to bring up the tunnel
5. Call `goBackend.getStatistics(tunnel)` for rx/tx/handshake stats
6. Call `goBackend.setState(tunnel, State.DOWN, null)` to tear down
7. Implement your own config import UI (file picker, QR scanner) — the tunnel library handles parsing

### Fallback: Android Platform IKEv2 (Option D)

If WireGuard integration hits unexpected issues, the platform IKEv2 API (API 30+) is a zero-dependency fallback. Trade-off: requires raising minSdk from 29 to 30 (drops Android 10 users), and requires IKEv2/IPsec servers instead of WireGuard servers.

---

## 5. Remaining Unknowns

| Unknown | Status | Notes |
|---------|--------|-------|
| Exact AAR size with ABI splits (arm64-v8a only) | Not measured | Would reduce from 5.8 MB significantly. Test with `abiFilters += listOf("arm64-v8a")` |
| Whether `GoBackend` can be subclassed for custom VpnService setup | Not verified | `GoBackend.VpnService` is a public class per Javadoc. Should be subclassable. |
| Config parsing of QR-scanned text | Not verified end-to-end | `Config.parse()` expects `Iterable<String>` of `KEY = VALUE` lines. QR codes typically encode a full WireGuard config as text — should work after splitting on newlines. |
| Compatibility with Android 10 (API 29) specific VpnService behavior | Not tested | Tunnel library declares `minSdk = 24`, so API 29 is within range. No known API 29-specific issues. |
| Whether the pre-built AAR's native libs are compatible with Android 10 | Likely yes | The AAR targets `minSdk = 24`; native libs should work on API 24+. |
| ProGuard/R8 shrinking compatibility | Not tested | The official app has ProGuard rules in `ui/proguard-android-optimize.txt`. ZeroVPN should keep `@Keep`-annotated classes and the native method names. |
| Whether `flock` availability on Windows would help for source builds | Not needed | Option A avoids source builds entirely. |

---

## 6. Sources

### Primary repositories

| Source | URL |
|--------|-----|
| wireguard-android (canonical) | https://git.zx2c4.com/wireguard-android/ |
| wireguard-android (GitHub mirror) | https://github.com/WireGuard/wireguard-android |
| wireguard-go (canonical) | https://git.zx2c4.com/wireguard-go/ |
| wireguard-go (GitHub mirror) | https://github.com/WireGuard/wireguard-go |
| wireguard-tools (canonical) | https://git.zx2c4.com/wireguard-tools/ |
| WireGuard official site | https://www.wireguard.com/ |

### License files

| File | URL | License |
|------|-----|---------|
| wireguard-android COPYING | https://git.zx2c4.com/wireguard-android/plain/COPYING | Apache-2.0 |
| wireguard-go LICENSE | https://git.zx2c4.com/wireguard-go/plain/LICENSE | MIT |
| wireguard-tools COPYING | https://git.zx2c4.com/wireguard-tools/about/COPYING | GPL-2.0 |
| License change discussion (GPL→MIT) | https://lists.zx2c4.com/pipermail/wireguard/2020-December/006157.html | Mailing list |

### Maven Central

| Artifact | URL |
|----------|-----|
| com.wireguard.android:tunnel (artifact page) | https://central.sonatype.com/artifact/com.wireguard.android/tunnel |
| Version 1.0.20260102 (latest) | https://central.sonatype.com/artifact/com.wireguard.android/tunnel/1.0.20260102 |
| Maven repo directory | https://repo1.maven.org/maven2/com/wireguard/android/tunnel/1.0.20260102/ |
| Javadoc | https://javadoc.io/doc/com.wireguard.android/tunnel |

### Source files inspected

| File | URL | What it shows |
|------|-----|---------------|
| tunnel/build.gradle.kts | https://git.zx2c4.com/wireguard-android/tree/tunnel/build.gradle.kts | Build config, CMake integration, publishing metadata, Apache-2.0 license in POM |
| tunnel/tools/CMakeLists.txt | https://git.zx2c4.com/wireguard-android/tree/tunnel/tools/CMakeLists.txt | Native build targets (libwg.so, libwg-quick.so, libwg-go.so), Apache-2.0 SPDX headers |
| tunnel/tools/libwg-go/Makefile | https://git.zx2c4.com/wireguard-android/plain/tunnel/tools/libwg-go/Makefile | Go build process, Unix-only toolchain (flock, uname), Go 1.24.3, no Windows support |
| tunnel/tools/libwg-go/api-android.go | https://git.zx2c4.com/wireguard-android/plain/tunnel/tools/libwg-go/api-android.go | JNI bridge between Go and Java, Apache-2.0 SPDX header |
| GoBackend.java | https://git.zx2c4.com/wireguard-android/plain/tunnel/src/main/java/com/wireguard/android/backend/GoBackend.java | Userspace backend, VpnService integration, statistics, state management |
| WgQuickBackend.java | https://git.zx2c4.com/wireguard-android/plain/tunnel/src/main/java/com/wireguard/android/backend/WgQuickBackend.java | Kernel module backend (requires root), not suitable for ZeroVPN |
| README.md | https://github.com/WireGuard/wireguard-android/blob/master/README.md | Embedding instructions, Maven Central reference |

### AAR inspection (downloaded and verified)

| File | URL | Finding |
|------|-----|---------|
| tunnel-1.0.20260102.aar | https://repo1.maven.org/maven2/com/wireguard/android/tunnel/1.0.20260102/tunnel-1.0.20260102.aar | 5.8 MB AAR containing `jni/{arm64-v8a,armeabi-v7a,x86,x86_64}/{libwg-go.so,libwg-quick.so,libwg.so}` — pre-built native libraries for 4 ABIs |

### Android platform APIs

| API | URL |
|-----|-----|
| VpnService | https://developer.android.com/reference/kotlin/android/net/VpnService |
| Ikev2VpnProfile | https://developer.android.com/reference/android/net/Ikev2VpnProfile |
| Ikev2VpnProfile.Builder | https://developer.android.com/reference/kotlin/android/net/Ikev2VpnProfile.Builder |
| PlatformVpnProfile | https://developer.android.com/reference/kotlin/android/net/PlatformVpnProfile |
| VpnManager | https://developer.android.com/reference/kotlin/android/net/VpnManager |
| VPN developer guide | https://developer.android.com/develop/connectivity/vpn |
| NDK CMake guide | https://developer.android.com/ndk/guides/cmake |
| Native dependencies in AAR | https://developer.android.com/build/native-dependencies |