package com.example.colorpatterngenerator

import kotlinx.coroutines.delay as coroutineDelay

/**
 * Tracks when a brightness change has settled without blocking the calling thread.
 *
 * The caller must serialize every method with its shared display-command mutex.
 * Call [awaitReady] before the next display mutation or status snapshot, and call
 * [brightnessApplied] after applying the actual window brightness on the UI thread.
 */
class BrightnessSettler(
    settleMs: Long = 3_000L,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val delay: suspend (Long) -> Unit = { coroutineDelay(it) }
) {
    private var configuredDelayMs = 0L
    private var settledUntilMs: Long? = null

    val delayMs: Long
        get() = configuredDelayMs

    init {
        configure(settleMs)
    }

    /** Changes future settling periods without shortening one already in progress. */
    fun configure(delayMs: Long) {
        require(delayMs in 0L..60_000L) { "Brightness settling time must be 0..60000 ms" }
        configuredDelayMs = delayMs
    }

    fun brightnessApplied(previous: Float, current: Float) {
        require(previous.isFinite() && current.isFinite()) { "Brightness must be finite" }
        // Android stores this normalized Float directly; equal applied values do
        // not change brightness, while even a small requested change must settle.
        if (previous == current || configuredDelayMs == 0L) return
        val deadline = clock() + configuredDelayMs
        settledUntilMs = maxOf(settledUntilMs ?: deadline, deadline)
    }

    suspend fun awaitReady() {
        while (true) {
            val deadline = settledUntilMs ?: return
            val remainingMs = deadline - clock()
            if (remainingMs <= 0L) {
                settledUntilMs = null
                return
            }
            delay(remainingMs)
        }
    }
}
