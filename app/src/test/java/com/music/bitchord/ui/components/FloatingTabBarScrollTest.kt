package com.music.bitchord.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import com.music.bitchord.ui.components.floatingtabbar.FloatingTabBarInlineBehavior
import com.music.bitchord.ui.components.floatingtabbar.FloatingTabBarScrollConnection
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingTabBarScrollTest {
    @Before @After fun clearMotion() {
        GlassMotion.scroll.settled()
        GlassMotion.nav.settled()
    }

    @Test fun verticalDragHoldsGlassWithoutCollapsingOrConsumingScroll() {
        val connection = FloatingTabBarScrollConnection(
            scrollThresholdPx = 10f,
            inlineBehavior = FloatingTabBarInlineBehavior.Never,
        )
        val consumed = connection.onPreScroll(Offset(0f, -100f), NestedScrollSource.UserInput)
        assertEquals(Offset.Zero, consumed)
        assertFalse(connection.isInline)
        assertTrue(GlassMotion.scroll.frozen())
    }

    @Test fun flingKeepsHoldingGlassAfterNavigationSettles() {
        val connection = FloatingTabBarScrollConnection(scrollThresholdPx = 10f)
        connection.onPreScroll(Offset(0f, -100f), NestedScrollSource.UserInput)
        assertTrue(connection.isInline)
        GlassMotion.nav.settled()
        GlassMotion.scroll.settled()
        val consumed = connection.onPreScroll(Offset(0f, -20f), NestedScrollSource.SideEffect)
        assertEquals(Offset.Zero, consumed)
        assertTrue(GlassMotion.scroll.frozen())
        assertFalse(GlassMotion.nav.frozen())
    }

    @Test fun horizontalOrEmptyScrollDoesNotHoldGlass() {
        val connection = FloatingTabBarScrollConnection(scrollThresholdPx = 10f)
        connection.onPreScroll(Offset(100f, 0f), NestedScrollSource.UserInput)
        connection.onPreScroll(Offset.Zero, NestedScrollSource.SideEffect)
        assertFalse(GlassMotion.scroll.frozen())
        assertFalse(connection.isInline)
    }
}
