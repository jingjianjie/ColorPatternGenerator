package com.example.colorpatterngenerator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdleSleepControllerTest {
    private val clock = FakeScheduler()
    private val events = mutableListOf<String>()
    private val controller = IdleSleepController(
        schedule = clock::schedule,
        cancel = clock::cancel,
        onSleep = { events.add("sleep") },
        onWake = { events.add("wake") }
    )

    @Test
    fun defaultIdleScreenSleepsAfterThirtySecondsInForeground() {
        assertTrue(controller.isIdle)
        clock.advanceBy(60_000)
        assertFalse(controller.isSleeping)

        controller.setForeground(true)
        clock.advanceBy(29_999)
        assertFalse(controller.isSleeping)
        clock.advanceBy(1)
        assertTrue(controller.isSleeping)
        assertEquals(listOf("sleep"), events)
    }

    @Test
    fun userActivityRestartsTheFullTimeout() {
        controller.setForeground(true)
        clock.advanceBy(20_000)
        controller.userActivity()

        clock.advanceBy(29_999)
        assertFalse(controller.isSleeping)
        clock.advanceBy(1)
        assertEquals(listOf("sleep"), events)
    }

    @Test
    fun activePatternsNeverScheduleSleepOrReactToUserActivity() {
        controller.setIdle(false)
        controller.setForeground(true)
        controller.userActivity()
        clock.advanceBy(120_000)

        assertFalse(controller.isIdle)
        assertFalse(controller.isSleeping)
        assertTrue(clock.tasks.isEmpty())
        assertTrue(events.isEmpty())
    }

    @Test
    fun canceledCallbackCannotDimANewPattern() {
        controller.setForeground(true)
        val oldCallback = clock.tasks.single().action
        controller.setIdle(false)
        oldCallback.run()
        clock.advanceBy(60_000)

        assertFalse(controller.isSleeping)
        assertTrue(events.isEmpty())
    }

    @Test
    fun canceledCallbackCannotDimAfterNewInteraction() {
        controller.setForeground(true)
        val oldCallback = clock.tasks.single().action
        clock.advanceBy(10_000)
        controller.userActivity()
        oldCallback.run()

        clock.advanceBy(29_999)
        assertTrue(events.isEmpty())
        clock.advanceBy(1)
        assertEquals(listOf("sleep"), events)
    }

    @Test
    fun leavingForegroundSuspendsAndReturningRestartsTimeout() {
        controller.setForeground(true)
        val oldCallback = clock.tasks.single().action
        clock.advanceBy(20_000)
        controller.setForeground(false)
        oldCallback.run()
        clock.advanceBy(60_000)
        controller.userActivity()
        assertTrue(events.isEmpty())

        controller.setForeground(true)
        oldCallback.run()
        clock.advanceBy(29_999)
        assertFalse(controller.isSleeping)
        clock.advanceBy(1)
        assertEquals(listOf("sleep"), events)
    }

    @Test
    fun touchWakesSleepingIdleScreenAndRestartsTimeout() {
        controller.setForeground(true)
        clock.advanceBy(30_000)
        controller.userActivity()
        assertFalse(controller.isSleeping)
        assertEquals(listOf("sleep", "wake"), events)

        clock.advanceBy(29_999)
        assertFalse(controller.isSleeping)
        clock.advanceBy(1)
        assertEquals(listOf("sleep", "wake", "sleep"), events)
    }

    @Test
    fun sleepingDoesNotRescheduleOrRepeatSleep() {
        controller.setForeground(true)
        val callback = clock.tasks.single().action
        clock.advanceBy(30_000)
        callback.run()
        clock.advanceBy(120_000)

        assertTrue(controller.isSleeping)
        assertEquals(1, clock.tasks.size)
        assertEquals(listOf("sleep"), events)
    }

    @Test
    fun changingToActivePatternWakesEvenInBackground() {
        controller.setForeground(true)
        clock.advanceBy(30_000)
        controller.setForeground(false)
        controller.setIdle(false)
        controller.setForeground(true)
        clock.advanceBy(60_000)

        assertFalse(controller.isSleeping)
        assertEquals(listOf("sleep", "wake"), events)
        assertEquals(1, clock.tasks.size)

        controller.setIdle(true)
        clock.advanceBy(30_000)
        assertEquals(listOf("sleep", "wake", "sleep"), events)
    }

    @Test
    fun foregroundChangesDoNotWakeAnAlreadySleepingScreen() {
        controller.setForeground(true)
        clock.advanceBy(30_000)
        controller.setForeground(false)
        controller.userActivity()
        controller.setForeground(true)
        clock.advanceBy(60_000)

        assertTrue(controller.isSleeping)
        assertEquals(listOf("sleep"), events)
        assertEquals(1, clock.tasks.size)
    }

    @Test
    fun explicitWakeWorksInBackgroundWithoutStartingTimer() {
        controller.setForeground(true)
        clock.advanceBy(30_000)
        controller.setForeground(false)
        controller.wake()
        controller.wake()
        clock.advanceBy(60_000)

        assertFalse(controller.isSleeping)
        assertEquals(listOf("sleep", "wake"), events)
        assertEquals(1, clock.tasks.size)

        controller.setForeground(true)
        clock.advanceBy(30_000)
        assertEquals(listOf("sleep", "wake", "sleep"), events)
    }

    @Test
    fun repeatingCurrentModeDoesNotPostponeSleep() {
        controller.setForeground(true)
        clock.advanceBy(20_000)
        controller.setIdle(true)
        controller.setForeground(true)
        clock.advanceBy(10_000)

        assertEquals(listOf("sleep"), events)
    }

    @Test
    fun closeCancelsTimerAndIgnoresAllSubsequentChanges() {
        controller.setForeground(true)
        val oldCallback = clock.tasks.single().action
        controller.close()
        controller.close()
        controller.setIdle(false)
        controller.setForeground(false)
        controller.setForeground(true)
        controller.userActivity()
        controller.wake()
        oldCallback.run()
        clock.advanceBy(60_000)

        assertTrue(controller.isIdle)
        assertFalse(controller.isSleeping)
        assertEquals(1, clock.tasks.size)
        assertTrue(clock.tasks.single().canceled)
        assertTrue(events.isEmpty())
    }

    @Test
    fun closeDoesNotWakeSleepingScreen() {
        controller.setForeground(true)
        clock.advanceBy(30_000)
        controller.close()
        controller.wake()
        controller.setIdle(false)
        controller.userActivity()

        assertTrue(controller.isSleeping)
        assertEquals(listOf("sleep"), events)
    }

    private class FakeScheduler {
        data class Task(val action: Runnable, val deadline: Long, var canceled: Boolean = false, var fired: Boolean = false)

        val tasks = mutableListOf<Task>()
        private var now = 0L

        fun schedule(action: Runnable, delayMs: Long) {
            tasks.add(Task(action, now + delayMs))
        }

        fun cancel(action: Runnable) {
            tasks.filter { it.action === action }.forEach { it.canceled = true }
        }

        fun advanceBy(durationMs: Long) {
            val target = now + durationMs
            while (true) {
                val next = tasks.filter { !it.canceled && !it.fired && it.deadline <= target }
                    .minByOrNull { it.deadline } ?: break
                now = next.deadline
                next.fired = true
                next.action.run()
            }
            now = target
        }
    }
}
