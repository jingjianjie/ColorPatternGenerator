package com.example.colorpatterngenerator

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrightnessSettlerTest {
    private class FakeClock {
        var nowMs = 0L
        val waits = mutableListOf<Long>()

        fun settler(initialMs: Long = 3_000L) = BrightnessSettler(
            settleMs = initialMs,
            clock = { nowMs },
            delay = { wait ->
                waits += wait
                nowMs += wait
            }
        )
    }

    @Test
    fun actualBrightnessChangeWaitsThreeSecondsByDefault() = runBlocking {
        val clock = FakeClock()
        val settler = clock.settler()
        assertEquals(3_000L, settler.delayMs)

        settler.brightnessApplied(1f, 0.5f)
        settler.awaitReady()

        assertEquals(listOf(3_000L), clock.waits)
        assertEquals(3_000L, clock.nowMs)
        settler.awaitReady()
        assertEquals(1, clock.waits.size)
    }

    @Test
    fun unchangedOrOmittedBrightnessDoesNotStartAWait() = runBlocking {
        val clock = FakeClock()
        val settler = clock.settler()

        // Both explicit unchanged brightness and an omitted value leave the
        // effective window brightness unchanged.
        settler.brightnessApplied(0.5f, 0.5f)
        settler.awaitReady()
        settler.brightnessApplied(0.5f, 0.5f)
        settler.awaitReady()

        assertTrue(clock.waits.isEmpty())
    }

    @Test
    fun unchangedBrightnessDoesNotClearOrExtendPendingWait() = runBlocking {
        val clock = FakeClock()
        val settler = clock.settler()

        settler.brightnessApplied(1f, 0.5f)
        clock.nowMs = 1_000L
        settler.brightnessApplied(0.5f, 0.5f)
        settler.awaitReady()

        assertEquals(listOf(2_000L), clock.waits)
        assertEquals(3_000L, clock.nowMs)
    }

    @Test
    fun elapsedSettlingPeriodNeedsNoDelay() = runBlocking {
        val clock = FakeClock()
        val settler = clock.settler()

        settler.brightnessApplied(1f, 0.5f)
        clock.nowMs = 3_001L
        settler.awaitReady()

        assertTrue(clock.waits.isEmpty())
    }

    @Test
    fun disablingSettlingAffectsFutureChangesOnly() = runBlocking {
        val clock = FakeClock()
        val settler = clock.settler()

        settler.brightnessApplied(1f, 0.5f)
        clock.nowMs = 1_000L
        settler.configure(0L)
        settler.awaitReady()
        settler.brightnessApplied(0.5f, 0.2f)
        settler.awaitReady()

        assertEquals(0L, settler.delayMs)
        assertEquals(listOf(2_000L), clock.waits)
    }

    @Test
    fun changingConfigurationDoesNotRewritePendingDeadline() = runBlocking {
        val clock = FakeClock()
        val settler = clock.settler()

        settler.brightnessApplied(1f, 0.5f)
        clock.nowMs = 500L
        settler.configure(6_000L)
        settler.awaitReady()
        settler.brightnessApplied(0.5f, 0.2f)
        settler.awaitReady()

        assertEquals(listOf(2_500L, 6_000L), clock.waits)
    }

    @Test
    fun overlappingChangeCannotShortenExistingSettlingPeriod() = runBlocking {
        val clock = FakeClock()
        val settler = clock.settler()

        settler.brightnessApplied(1f, 0.5f)
        clock.nowMs = 1_000L
        settler.configure(500L)
        settler.brightnessApplied(0.5f, 0.2f)
        settler.awaitReady()

        assertEquals(listOf(2_000L), clock.waits)
    }

    @Test
    fun restoringBrightnessAfterIdleWaitsEvenForPreviousPatternPercent() = runBlocking {
        val clock = FakeClock()
        val settler = clock.settler()

        settler.brightnessApplied(1f, 0.5f)
        settler.awaitReady()
        // Idle dimming occurred between commands. Compare the actual current
        // window value, rather than remembering the previous pattern's 50%.
        settler.brightnessApplied(0.01f, 0.5f)
        settler.awaitReady()

        assertEquals(listOf(3_000L, 3_000L), clock.waits)
    }

    @Test
    fun awaitReadyRechecksClockAfterEarlyDelayReturn() = runBlocking {
        var nowMs = 0L
        val waits = mutableListOf<Long>()
        val settler = BrightnessSettler(
            clock = { nowMs },
            delay = { wait ->
                waits += wait
                nowMs += minOf(wait, 1_000L)
            }
        )

        settler.brightnessApplied(1f, 0.5f)
        settler.awaitReady()

        assertEquals(listOf(3_000L, 2_000L, 1_000L), waits)
    }

    @Test
    fun configurationAcceptsBoundsAndRejectsInvalidValues() {
        val settler = FakeClock().settler(0L)
        assertEquals(0L, settler.delayMs)
        settler.configure(60_000L)
        assertEquals(60_000L, settler.delayMs)
        for (invalid in listOf(-1L, 60_001L)) {
            try {
                settler.configure(invalid)
                throw AssertionError("Accepted invalid delay $invalid")
            } catch (_: IllegalArgumentException) {
                assertEquals(60_000L, settler.delayMs)
            }
        }
    }
}
