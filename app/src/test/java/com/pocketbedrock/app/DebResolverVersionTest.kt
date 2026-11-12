package com.pocketbedrock.app

import com.pocketbedrock.app.runtime.DebResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebResolverVersionTest {

    private fun v(s: String) = DebResolver.Version(s)

    @Test
    fun `newer patch sorts higher`() {
        assertTrue(v("2.35-0ubuntu3.8") > v("2.35-0ubuntu3.4"))
        assertTrue(v("7.81.0-1ubuntu1.18") > v("7.81.0-1ubuntu1.8"))
    }

    @Test
    fun `sub-revisions sort higher than base`() {
        assertTrue(v("12.3.0-1ubuntu1.2") > v("12.3.0-1ubuntu1"))
    }

    @Test
    fun `major versions dominate`() {
        assertTrue(v("3.0.12-1ubuntu1") > v("2.35-0ubuntu3.99"))
    }

    @Test
    fun `equal versions compare equal`() {
        assertEquals(0, v("2.35-0ubuntu3.8").compareTo(v("2.35-0ubuntu3.8")))
    }
}
