package com.music.bitchord

import com.music.bitchord.data.AppDiagnostics
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppDiagnosticsTest {
    @Test
    fun reportKeepsCodeLocationsAndOmitsExceptionAndFileContents() {
        val error = IllegalStateException(
            "Account user@example.test",
            IllegalArgumentException("https://example.test/audio?token=secret"),
        ).apply {
            stackTrace = arrayOf(StackTraceElement("com.music.Player", "open", "/private/library.flac", 42))
        }

        val event = AppDiagnostics.formatEvent("Playback error", error)

        assertTrue(event.contains("java.lang.IllegalStateException"))
        assertTrue(event.contains("java.lang.IllegalArgumentException"))
        assertTrue(event.contains("com.music.Player.open:42"))
        assertFalse(event.contains("example.test"))
        assertFalse(event.contains("secret"))
        assertFalse(event.contains("library.flac"))
    }
}
