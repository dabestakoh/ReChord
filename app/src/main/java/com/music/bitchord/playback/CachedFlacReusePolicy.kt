package com.music.bitchord.playback

/**
 * Replaying an already-proved FLAC is optional; every mismatch uses the
 * existing resolver instead of returning potentially wrong bytes.
 */
internal object CachedFlacReusePolicy {
    fun mayReuse(
        sourceEnabled: Boolean,
        sourceComplete: Boolean,
        sourcePermitted: Boolean,
        renditionMatchesPreference: Boolean,
    ): Boolean = sourceEnabled && sourceComplete && sourcePermitted && renditionMatchesPreference
}
