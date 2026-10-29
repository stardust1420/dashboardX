package com.karan.dashboardx.render

import org.junit.Assert.assertEquals
import org.junit.Test

class ViewportTest {
    @Test fun viewport_wide() {
        val v = Viewport.fit(0, 0, 1280, 720)
        assertEquals(1.5f, v.scale, 0.0001f)
        assertEquals(40f, v.dx, 0.0001f)
        assertEquals(0f, v.dy, 0.0001f)
    }

    @Test fun viewport_offsetArea() {
        val v = Viewport.fit(100, 50, 800, 480)
        assertEquals(1f, v.scale, 0.0001f)
        assertEquals(100f, v.dx, 0.0001f)
        assertEquals(50f, v.dy, 0.0001f)
    }

    @Test fun fitVisibleOrSurface_usesVisibleArea() {
        // 1280x720 surface, action strip / chrome leaves a 1000x600 area at (200, 100).
        val v = Viewport.fitVisibleOrSurface(1280, 720, 200, 100, 1200, 700)
        assertEquals(1.25f, v.scale, 0.0001f)
        assertEquals(200f, v.dx, 0.0001f)
        assertEquals(100f, v.dy, 0.0001f)
    }

    @Test fun fitVisibleOrSurface_emptyArea_fallsBackToSurface() {
        assertEquals(Viewport.fit(0, 0, 1280, 720), Viewport.fitVisibleOrSurface(1280, 720, 0, 0, 0, 0))
        assertEquals(Viewport.fit(0, 0, 1280, 720), Viewport.fitVisibleOrSurface(1280, 720, 300, 100, 300, 700))
        assertEquals(Viewport.fit(0, 0, 1280, 720), Viewport.fitVisibleOrSurface(1280, 720, 10, 500, 900, 400))
    }

    @Test fun fitVisibleOrSurface_zeroSurface_isFinite() {
        val v = Viewport.fitVisibleOrSurface(0, 0, 0, 0, 0, 0)
        assertEquals(true, v.scale.isFinite() && v.scale > 0f)
    }
}
