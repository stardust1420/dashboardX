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

    @Test fun toLogical_invertsTheFit() {
        val v = Viewport.fit(0, 0, 1280, 720) // scale 1.5, dx 40
        assertEquals(0f, v.toLogicalX(40f), 0.0001f)
        assertEquals(800f, v.toLogicalX(1240f), 0.0001f)
        assertEquals(480f, v.toLogicalY(720f), 0.0001f)
        assertEquals(400f, v.toLogicalX(640f), 0.0001f)
    }

    @Test fun fitSurface_fillsWholeSurface() {
        assertEquals(Viewport.fit(0, 0, 1280, 720), Viewport.fitSurface(1280, 720))
        val v = Viewport.fitSurface(800, 480)
        assertEquals(1f, v.scale, 0.0001f)
        assertEquals(0f, v.dx, 0.0001f)
        assertEquals(0f, v.dy, 0.0001f)
    }

    @Test fun fitSurface_zeroSurface_isFinite() {
        val v = Viewport.fitSurface(0, 0)
        assertEquals(true, v.scale.isFinite() && v.scale > 0f)
    }
}
