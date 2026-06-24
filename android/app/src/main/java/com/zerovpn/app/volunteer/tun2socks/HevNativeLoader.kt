package com.zerovpn.app.volunteer.tun2socks

import android.os.Build
import com.zerovpn.app.BuildConfig

class HevNativeLoader private constructor() {
    companion object {
        private const val LibraryName = "hev-socks5-tunnel"

        @Volatile
        private var cachedDiagnostics: HevNativeDiagnostics? = null

        fun smokeTest(): HevNativeDiagnostics {
            cachedDiagnostics?.let { return it }

            val diagnostics = if (!BuildConfig.HEV_NATIVE_ENABLED) {
                HevNativeDiagnostics(
                    enabled = false,
                    loadAttempted = false,
                    loaded = false,
                    libraryName = LibraryName,
                    abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
                    state = HevNativeSmokeState.Unavailable,
                )
            } else {
                loadNative()
            }
            cachedDiagnostics = diagnostics
            return diagnostics
        }

        private fun loadNative(): HevNativeDiagnostics {
            return try {
                System.loadLibrary(LibraryName)
                HevNativeDiagnostics(
                    enabled = true,
                    loadAttempted = true,
                    loaded = true,
                    libraryName = LibraryName,
                    abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
                    state = HevNativeSmokeState.Loaded,
                )
            } catch (e: UnsatisfiedLinkError) {
                HevNativeDiagnostics(
                    enabled = true,
                    loadAttempted = true,
                    loaded = false,
                    libraryName = LibraryName,
                    abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
                    state = HevNativeSmokeState.Failed,
                    lastLoadError = e.message,
                )
            }
        }

        @JvmStatic
        external fun TProxyStartService(configPath: String, tunFd: Int)

        @JvmStatic
        external fun TProxyStopService()

        @JvmStatic
        external fun TProxyGetStats(): LongArray
    }
}
