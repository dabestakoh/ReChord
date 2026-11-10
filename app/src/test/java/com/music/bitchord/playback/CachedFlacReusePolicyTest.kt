package com.music.bitchord.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CachedFlacReusePolicyTest {
    private fun allowed(
        enabled: Boolean = true,
        complete: Boolean = true,
        permitted: Boolean = true,
        preferred: Boolean = true,
    ) = CachedFlacReusePolicy.mayReuse(enabled, complete, permitted, preferred)

    @Test fun validCompletedFlacMayBeReused() = assertTrue(allowed())
    @Test fun disabledSourceMustNotBeReused() = assertFalse(allowed(enabled = false))
    @Test fun incompleteSourceConfigurationMustNotBeReused() = assertFalse(allowed(complete = false))
    @Test fun qualityCeilingMustBeRespected() = assertFalse(allowed(permitted = false))
    @Test fun atmospherePreferenceMustBeRespected() = assertFalse(allowed(preferred = false))
}
