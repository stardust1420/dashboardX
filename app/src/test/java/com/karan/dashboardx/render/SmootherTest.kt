package com.karan.dashboardx.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmootherTest {
    @Test fun smoother_snapsFirstThenEases() {
        val s = Smoother()
        s.target(1000.0)
        assertEquals(1000.0, s.advance(16)!!, 0.001)
        s.target(3000.0)
        assertEquals(2264.0, s.advance(80)!!, 5.0)
    }

    @Test fun smoother_nullTargetReportsNullThenResumesFromKept() {
        val s = Smoother()
        assertNull(s.advance(16))
        s.target(1000.0); s.advance(16)
        s.target(null)
        assertNull(s.advance(16))
        s.target(3000.0)
        assertEquals(2264.0, s.advance(80)!!, 5.0)
    }
}
