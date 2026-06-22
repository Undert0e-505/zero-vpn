# hev-socks5-tunnel Native Integration Spike

Date: 2026-06-22

## Purpose

Create a reproducible native-source path for using `hev-socks5-tunnel` as the
Volunteer Network tun2socks bridge:

`Android VpnService TUN fd -> hev-socks5-tunnel -> 127.0.0.1:9050`

This remains a Developer Mode-only spike. It is not production Volunteer
Network mode and does not route device traffic yet.

## Source Pinning

Use a git submodule instead of checked-in prebuilt binaries.

* path: `android/native/hev-socks5-tunnel`
* upstream: `https://github.com/heiher/hev-socks5-tunnel.git`
* tag: `2.15.0`
* commit: `00c7eb9ad7ca381b0f1fee880abc1077fe9b93be`
* license: MIT

Recursive source dependencies from the pinned tag:

* `src/core`: `ee0f24505d344f14b14624fa2249e6ccfaed138b`
* `third-part/hev-task-system`: `8d83bbbf79557138726c8ee5a5fae99cbb978d61`
* `third-part/lwip`: `8c69dfbe537835d5f2a5fd8c08c859f667b108ea`
* `third-part/yaml`: `efa36117a8646d26d12b58e05bac472d7854a70d`

Do not track a floating branch.

## Native Build Design

Preferred build route:

1. Build upstream with Android NDK `ndk-build`.
2. Point Gradle `externalNativeBuild.ndkBuild.path` at upstream `Android.mk`.
3. Keep ABI selection in the app Gradle file.
4. Use an app-local `Application.mk` wrapper without `APP_ABI`, because Android
   Gradle Plugin configures one ABI at a time.
5. Start with `arm64-v8a`, `armeabi-v7a`, and `x86_64`.
6. Do not commit generated `.so` files.

The initial attempted NDK version was `27.0.12077973`, installed into the
repo-local Android SDK.

## JNI Boundary

Upstream exposes JNI registration in `src/hev-jni.c`.

Minimal Kotlin shell for the next attempt:

* package: `com.zerovpn.app.volunteer.tun2socks`
* class: `HevTun2SocksNative`
* native library: `hev-socks5-tunnel`
* native calls:
  * `TProxyStartService(configPath: String, tunFd: Int)`
  * `TProxyStopService()`
  * `TProxyGetStats(): LongArray`

The Gradle/NDK build should pass:

`APP_CFLAGS+=-DPKGNAME=com/zerovpn/app/volunteer/tun2socks -DCLSNAME=HevTun2SocksNative`

Higher-level Kotlin should wrap this in a coroutine-backed controller that owns
the duplicated TUN fd, config file, running state, and stop timeout.

## Config Shape

The first generated config should be temporary app-private data, not a bundled
asset. It should target:

* SOCKS target: `127.0.0.1:9050`
* DNS mode: explicit, likely `map-dns` only after validation
* UDP mode: unsupported or blocked unless proven safe with Tor SOCKS

Diagnostics must include `dnsMode`, `udpMode`, and `dnsLeakRisk`.

## Current Blocker

The native build did not complete on this Windows checkout.

`hev-task-system` records headers in `include/` as git symlinks. With Windows
symlink creation disabled, Git materialized those entries as text files whose
contents are relative targets, for example:

`../src/lib/object/hev-object-atomic.h`

Clang then treats that line as C source and fails before `hev-socks5-tunnel`
can build. A forced checkout with `core.symlinks=true` failed because Windows
denied symlink creation.

This is a host checkout/tooling blocker, not evidence that the upstream Android
source cannot build.

The pinned source was moved to a dedicated branch,
`spike/hev-native-build`, instead of being committed to `main`. This keeps the
main branch free of the hev submodule until the native build is validated on a
host that can check out upstream symlinks correctly. No Gradle native build
wiring is active on the branch, so the Android app build remains independent of
the experiment.

## Linux CI Validation

Linux validation is required because the current blocker is Windows symlink
materialization, not an Android API or C compiler error.

Workflow:

* file: `.github/workflows/hev-native-build.yml`
* runner: `ubuntu-latest`
* checkout: `actions/checkout@v4` with `submodules: recursive`
* NDK: `27.0.12077973`
* build system: upstream `ndk-build`
* ABIs: `arm64-v8a`, `armeabi-v7a`, `x86_64`

The workflow invokes upstream source directly:

```sh
"$ANDROID_NDK_HOME/ndk-build" \
  NDK_PROJECT_PATH="$GITHUB_WORKSPACE/android/native/hev-socks5-tunnel" \
  APP_BUILD_SCRIPT="$GITHUB_WORKSPACE/android/native/hev-socks5-tunnel/Android.mk" \
  NDK_APPLICATION_MK="$GITHUB_WORKSPACE/android/native/hev-socks5-tunnel/Application.mk" \
  APP_ABI="arm64-v8a armeabi-v7a x86_64"
```

The validation step checks for:

* `android/native/hev-socks5-tunnel/libs/arm64-v8a/libhev-socks5-tunnel.so`
* `android/native/hev-socks5-tunnel/libs/armeabi-v7a/libhev-socks5-tunnel.so`
* `android/native/hev-socks5-tunnel/libs/x86_64/libhev-socks5-tunnel.so`

The workflow does not upload release artifacts and does not commit generated
native outputs.

The Linux source-only validation passed on run `27960996947`, proving the
pinned submodule can build `libhev-socks5-tunnel.so` for `arm64-v8a`,
`armeabi-v7a`, and `x86_64` on a host where upstream symlink headers check out
correctly.

## Gated Android App Integration

The app build uses an explicit Gradle property gate:

```sh
./gradlew assembleDebug -PenableHevNative=true
```

Default behavior when the property is missing or false:

* no `externalNativeBuild` configuration is added
* no hev native source build is attempted
* `BuildConfig.HEV_NATIVE_ENABLED=false`
* Windows `assembleDebug` remains safe even when upstream symlink headers cannot
  be materialized locally

Enabled behavior when `-PenableHevNative=true`:

* NDK version is pinned to `27.0.12077973`
* Gradle uses upstream `android/native/hev-socks5-tunnel/Android.mk`
* an app-local `Application.mk` wrapper lets Android Gradle Plugin own ABI
  selection
* ABIs are `arm64-v8a`, `armeabi-v7a`, and `x86_64`
* `BuildConfig.HEV_NATIVE_ENABLED=true`
* JNI macros target
  `com/zerovpn/app/volunteer/tun2socks/HevNativeLoader`

Developer diagnostics now include a minimal HEV native smoke block. The smoke
path only attempts `System.loadLibrary("hev-socks5-tunnel")` when
`HEV_NATIVE_ENABLED=true`; default builds report the native library as
unavailable and do not load it. The current smoke test proves loadability only.
It does not start hev, create a TUN fd, or route traffic.

CI was extended to run:

1. standalone upstream hev native build
2. default Android `assembleDebug`
3. Android `assembleDebug -PenableHevNative=true`
4. APK inspection for `libhev-socks5-tunnel.so` under all selected ABIs

The workflow update must be pushed before the hev-enabled APK packaging result
is known.

## Developer Mode VpnService Pipe Spike

The next spike step adds a Developer Mode-only pipe:

`Android VpnService TUN fd -> HEV tun2socks -> 127.0.0.1:9050`

HEV start API found:

* upstream Android JNI is in `src/hev-jni.c`
* `JNI_OnLoad` registers methods on the class named by `PKGNAME` and `CLSNAME`
* ZeroVPN points those macros at
  `com/zerovpn/app/volunteer/tun2socks/HevNativeLoader`
* registered native methods:
  * `TProxyStartService(configPath: String, tunFd: Int)`
  * `TProxyStopService()`
  * `TProxyGetStats(): LongArray`

No extra C glue was required for this spike.

The Developer Mode flow now:

1. checks that the HEV native library is loaded
2. starts or reuses the embedded Tor SOCKS proof until it is Ready
3. requests Android VPN permission only from the Developer Mode diagnostics card
4. starts `VolunteerVpnService`
5. creates a TUN interface
6. writes a temporary HEV YAML config under app cache
7. calls `TProxyStartService(configPath, tunFd)`
8. exposes diagnostics and a stop button

TUN config:

* session: `ZeroVPN Volunteer Spike`
* address: `10.111.0.2/32`
* route: `0.0.0.0/0`
* MTU: `1500`

DNS mode:

* `dnsMode=not-safe-yet-system-resolver`
* `dnsLeakRisk=true`
* HEV `mapdns` is not wired yet

This means routing validation is useful as a pipe signal only. It must not be
described as leak-safe or production-ready.

UDP mode:

* `udpMode=unsupported-socks-udp-over-tcp-configured`
* Tor SOCKS is not a general UDP transport
* ZeroVPN must still treat UDP as unsupported for product purposes

Validation levels:

* Level 1: native library loaded, Tor Ready, permission granted, TUN created,
  HEV started, Android VPN active detected.
* Level 2: in-app request without explicit SOCKS proxy to
  `https://check.torproject.org/api/ip` returns `HTTP 200 IsTor=true`.
* Level 3: browser check confirms traffic through the Android VPN path.

The service stops HEV first, closes the TUN fd, updates diagnostics, and leaves
Tor running explicitly. Tor can still be stopped separately through the existing
embedded Tor controls.

## Routing Failure Diagnostic Pass

Device validation after the initial `VpnService` spike passed Level 1:

* HEV native loaded on `arm64-v8a`
* embedded Tor reached `Ready`
* Android VPN permission was granted
* TUN was created
* HEV was running
* Android VPN transport was detected
* HEV counters moved: `txPackets=5`, `txBytes=300`, `rxPackets=1`,
  `rxBytes=44`

Level 2/3 failed:

* in-app validation to `https://check.torproject.org/api/ip` timed out
* browser traffic had no internet access while the Volunteer VPN test was active

The first diagnostic fix makes the generated HEV config visible in Developer
Mode diagnostics:

* config file path
* config existence
* file size
* last modified time
* full YAML contents
* HEV start call result

The current generated YAML shape is:

```yaml
tunnel:
  mtu: 1500

socks5:
  port: 9050
  address: '127.0.0.1'
  udp: 'tcp'

mapdns:
  address: 198.18.0.2
  port: 53
  network: 100.64.0.0
  netmask: 255.192.0.0
  cache-size: 10000

misc:
  task-stack-size: 86016
  connect-timeout: 10000
  tcp-read-write-timeout: 300000
  log-file: stderr
  log-level: info
```

This deliberately follows the upstream/SocksTun Android shape more closely than
the first attempt:

* `VpnService.Builder.setBlocking(false)`
* `Builder.addDnsServer(198.18.0.2)`
* `mapdns` enabled in HEV config
* app traffic intentionally included in the VPN for in-app validation

Diagnostics now separate validation layers:

* `torSocksBaselineStatus`: explicit SOCKS request through local Tor
* `vpnDnsValidationStatus`: unproxied HTTPS request requiring DNS after VPN
  activation
* `vpnTcpValidationStatus`: direct TCP connect to `1.1.1.1:443` after VPN
  activation
* `vpnTorValidationStatus`: Tor check result over the VPN path

DNS status is now `dnsMode=hev-map-dns-through-socks` and
`dnsLeakRisk=unknown`. This is still not production leak-safe until device tests
prove browser and app DNS behavior.

UDP status remains
`udpMode=unsupported-udp-over-tcp-requested-tor-compat-unknown`. Tor SOCKS is
not a general UDP transport, so product Volunteer Network must still treat UDP
as unsupported unless a separate safe design is proven.

## Next Options

Recommended order:

1. Push the gated app integration branch and confirm CI builds the hev-enabled
   APK with packaged native libraries.
2. If APK packaging passes, run a device build with `enableHevNative=true` and
   confirm Developer Mode diagnostics report `loaded=true`.
3. Add a minimal JNI start/stop wrapper around upstream's registered methods,
   still without `VpnService`.
4. Then add the Developer Mode-only `VpnService` TUN fd wiring.
5. If Linux CI passes but Windows remains unsupported, decide whether native
   builds only need to run in CI/Linux or whether Windows developers must enable
   symlink support.
6. If Windows support is required, add a small reviewed build-prep step that
   materializes upstream symlink headers into a generated ignored directory and
   points `LOCAL_C_INCLUDES` there.
7. If neither is acceptable, evaluate vendoring a minimal reviewed source patch
   to upstream include handling before moving to another tun2socks candidate.

Do not manually commit generated headers or prebuilt native libraries.

## Test Plan

Level 0:

* `assembleDebug` compiles `libhev-socks5-tunnel.so` for selected ABIs.
* App loads `hev-socks5-tunnel` successfully.

Level 1:

* Developer Mode starts embedded Tor and reaches SOCKS ready.
* `VpnService` permission flow completes.
* TUN interface is created.
* hev starts with the TUN fd and `127.0.0.1:9050`.
* Android VPN key appears.
* Stop shuts down hev and closes the TUN fd.

Level 2:

* In-app request without explicit SOCKS proxy returns
  `HTTP 200 IsTor=true` through the VPN path.

Level 3:

* Browser validation confirms traffic reaches Tor through the VPN path.

## Rollback Plan

If the native route is abandoned:

1. Remove the Gradle `externalNativeBuild` wiring.
2. Remove the JNI/Kotlin wrapper package.
3. Remove the `android/native/hev-socks5-tunnel` submodule and `.gitmodules`
   entry.
4. Keep the feasibility docs as rationale for the decision.
