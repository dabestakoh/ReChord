package com.music.bitchord.ui.player

import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VinylRecordGeometryTest {

    @Test
    fun fullScreenPhoneAndDapReachFullMochyWidthRadiusWithUncroppedShadow() {
        for ((width, height) in listOf(393f to 851f, 360f to 640f)) {
            val g = RecordGeometry(Size(width, height), mountTopInset = 32)
            assertEquals(width * 0.703f, g.radius, 0.01f)
            val shadowBottom = g.center.y + g.radius * 0.057f + g.radius * 1.16f
            assertTrue("Drop shadow should fit inside full player height", shadowBottom < height)
            assertTrue("Tonearm mount should clear status bar inset", g.pivot.y >= 32f)
        }
    }

    @Test
    fun compactAndSquarePreviewBoundsCapRadiusSafely() {
        val compact = RecordGeometry(Size(360f, 440f))
        assertEquals(440f * 0.446f, compact.radius, 0.01f)

        val preview = RecordGeometry(Size(240f, 240f))
        assertEquals(240f * 0.446f, preview.radius, 0.01f)
    }

    @Test
    fun tonearmTracksOuterToInnerGroovesAccuratelyAtEnlargedSize() {
        val g = RecordGeometry(Size(393f, 851f), mountTopInset = 32)
        for (p in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val roundTrip = g.fraction(g.angle(p))
            assertEquals(p, roundTrip, 0.01f)
        }
    }
}
