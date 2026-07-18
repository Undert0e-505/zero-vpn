package com.zerovpn.app.oci

/** Region authority for foreground setup. Bootstrap transport metadata is deliberately absent. */
object OciRegionAuthority {
    enum class Source { USER_SELECTED, PERSISTED_VERIFIED, TRUSTED_AUTH_RESULT, REQUIRED }

    data class Resolution(val regionId: String?, val source: Source) {
        val isAuthoritative: Boolean get() = regionId != null
    }

    fun resolve(
        userSelectedRegion: String?,
        persistedVerifiedHomeRegion: String?,
        trustedAuthHomeRegion: String? = null,
    ): Resolution = when {
        !userSelectedRegion.isNullOrBlank() -> Resolution(userSelectedRegion, Source.USER_SELECTED)
        !persistedVerifiedHomeRegion.isNullOrBlank() -> Resolution(persistedVerifiedHomeRegion, Source.PERSISTED_VERIFIED)
        !trustedAuthHomeRegion.isNullOrBlank() -> Resolution(trustedAuthHomeRegion, Source.TRUSTED_AUTH_RESULT)
        else -> Resolution(null, Source.REQUIRED)
    }
}
