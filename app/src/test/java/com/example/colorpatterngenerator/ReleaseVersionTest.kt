package com.example.colorpatterngenerator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseVersionTest {
    private fun version(value: String): ReleaseVersion = requireNotNull(ReleaseVersion.parseOrNull(value))

    @Test
    fun comparesComponentsNumericallyInsteadOfAlphabetically() {
        assertTrue(version("v1.10") > version("1.9"))
        assertTrue(version("2.0") > version("1.99.99"))
        assertTrue(version("1.1.1") > version("1.1"))
        assertTrue(version("1.1") < version("1.2"))
    }

    @Test
    fun normalizesMissingZeroComponentsAndTagPrefixes() {
        assertEquals(version("1"), version("v1.0.0"))
        assertEquals(version("1.1"), version("V1.1.0"))
        assertEquals(0, version("1.1.0").compareTo(version("1.1")))
        assertEquals(version("1.1").hashCode(), version("1.1.0").hashCode())
    }

    @Test
    fun buildMetadataDoesNotOfferAnUpdateOfTheSameVersion() {
        assertEquals(version("1.1"), version("v1.1.0+build.20261019"))
        assertTrue(version("1.2+build-2") > version("1.1+build-99"))
    }

    @Test
    fun rejectsPrereleaseAndMalformedTags() {
        listOf(
            "", "latest", "release-1.2", "v", "1..2", ".1.2", "1.2.",
            " 1.2", "1.2 ", "1.2\n", "1.2-rc.1", "1.2-beta", "-1.2",
            "01.2", "1.02", "1.2+", "1.2+build..2", "1.2+build/2"
        ).forEach { assertNull("Accepted invalid tag: $it", ReleaseVersion.parseOrNull(it)) }
    }

    @Test
    fun acceptsLargeNumericComponentsWithoutOverflow() {
        assertTrue(version("1.9999999999999999999999999") > version("1.2147483647"))
        assertNotNull(ReleaseVersion.parseOrNull("0.0.0"))
    }

    @Test
    fun rejectsUnreasonablyLongTags() {
        assertNull(ReleaseVersion.parseOrNull("1." + "9".repeat(128)))
    }
}
