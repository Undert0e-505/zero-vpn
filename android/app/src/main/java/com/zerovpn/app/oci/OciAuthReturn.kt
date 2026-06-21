package com.zerovpn.app.oci

import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object OciAuthReturn {
    const val CALLBACK_URI = "zerovpn://oci-auth/callback"

    private val _returns = MutableSharedFlow<Uri>(replay = 1, extraBufferCapacity = 1)
    val returns: SharedFlow<Uri> = _returns.asSharedFlow()

    fun handleIntent(intent: Intent?): Boolean {
        if (intent?.action != Intent.ACTION_VIEW) return false
        val uri = intent.data ?: return false
        if (uri.scheme != "zerovpn" || uri.host != "oci-auth" || uri.path != "/callback") {
            return false
        }
        _returns.tryEmit(uri)
        return true
    }
}
