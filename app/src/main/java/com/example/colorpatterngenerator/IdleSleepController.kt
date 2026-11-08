package com.example.colorpatterngenerator

/** Tracks automatic sleep for an idle screen. Call all methods on the main thread. */
class IdleSleepController(
    private val timeoutMs: Long = 30_000L,
    private val schedule: (Runnable, Long) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val onSleep: () -> Unit,
    private val onWake: () -> Unit
) {
    var isSleeping = false
        private set

    val isIdle: Boolean
        get() = idle

    private var idle = true
    private var foreground = false
    private var closed = false
    private var generation = 0L
    private var pending: Runnable? = null

    init {
        require(timeoutMs >= 0) { "timeoutMs must be nonnegative" }
    }

    fun setIdle(idle: Boolean) {
        if (closed || isIdle == idle) return
        this.idle = idle
        cancelTimeout()
        if (!idle) wakeIfSleeping()
        scheduleTimeout()
    }

    fun setForeground(value: Boolean) {
        if (closed || foreground == value) return
        foreground = value
        cancelTimeout()
        scheduleTimeout()
    }

    fun userActivity() {
        if (!closed && isIdle && foreground) wake()
    }

    fun wake() {
        if (closed) return
        cancelTimeout()
        wakeIfSleeping()
        scheduleTimeout()
    }

    fun close() {
        if (closed) return
        closed = true
        cancelTimeout()
    }

    private fun wakeIfSleeping() {
        if (!isSleeping) return
        isSleeping = false
        onWake()
    }

    private fun cancelTimeout() {
        generation++
        pending?.let(cancel)
        pending = null
    }

    private fun scheduleTimeout() {
        if (closed || !isIdle || !foreground || isSleeping) return
        val scheduledGeneration = generation
        val task = Runnable {
            if (!closed && generation == scheduledGeneration && isIdle && foreground && !isSleeping) {
                pending = null
                isSleeping = true
                onSleep()
            }
        }
        pending = task
        schedule(task, timeoutMs)
    }
}
