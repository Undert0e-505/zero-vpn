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
