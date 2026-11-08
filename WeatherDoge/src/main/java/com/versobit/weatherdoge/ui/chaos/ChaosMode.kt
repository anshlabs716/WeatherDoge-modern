/*
 * Copyright (C) 2026 Weather Doge contributors
 *
 * This file is part of Weather Doge.
 *
 * Weather Doge is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Weather Doge is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Weather Doge.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.versobit.weatherdoge.ui.chaos

/**
 * The 15 second CHAOS MODE sequence.
 *
 * Deliberately free of Android types so the timing, the script and — most importantly —
 * the end-of-chaos cleanup can be unit tested on a plain JVM. The activity supplies a
 * monotonic clock ([kotlin.system.getTimeMillis]-style, i.e. `SystemClock.elapsedRealtime`)
 * so wall clock jumps (NTP, user changing the time) can never truncate or extend the fun.
 *
 * Invariants the tests lock down:
 *  - a run lasts exactly [DEFAULT_DURATION_MS] of *monotonic* time,
 *  - [onFinished] fires exactly once, whether the run ends naturally or is cancelled,
 *  - reduced motion turns the visual beats off but never shortens the run,
 *  - the script never asks for a music track: only SFX.
 */
class ChaosMode(
    private val durationMs: Long = DEFAULT_DURATION_MS,
    private val clock: () -> Long
) {

    enum class State { IDLE, RUNNING, FINISHED }

    /** The only three sounds CHAOS MODE is allowed to play. */
    enum class ChaosSfx { KEYBOARD_CAT, MEOW, AIRHORN }

    /**
     * One scheduled sound effect.
     * @param atMs offset from the start of the run
     * @param sfx which effect to fire
     */
    data class Beat(val atMs: Long, val sfx: ChaosSfx)

    var state: State = State.IDLE
        private set

    /** Invoked exactly once when a run ends (naturally or by cancel). */
    var onFinished: (() -> Unit)? = null

    private var startedAt = 0L
    private var finished = false
    private var reduceMotion = false

    val isActive: Boolean get() = state == State.RUNNING

    /** Visual mayhem is skipped entirely when the user asked for reduced motion. */
    val visualsEnabled: Boolean get() = state == State.RUNNING && !reduceMotion

    fun elapsedMs(): Long = if (state == State.IDLE) 0L else (clock() - startedAt).coerceAtLeast(0L)

    fun remainingMs(): Long = (durationMs - elapsedMs()).coerceAtLeast(0L)

    /** 0f..1f progress through the run. */
    fun progress(): Float =
        if (durationMs <= 0L) 1f else (elapsedMs().toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)

    /**
     * Start a run. Returns false when one is already in progress.
     * @param reduceMotion freeze the visual effects (still runs the full 15 seconds)
     */
    fun start(reduceMotion: Boolean = false): Boolean {
        if (state == State.RUNNING) return false
        this.reduceMotion = reduceMotion
        state = State.RUNNING
        finished = false
        startedAt = clock()
        return true
    }

    /**
     * Advance the clock. Returns the state after the tick; when the run has reached its
     * end this performs the cleanup exactly once.
     */
    fun tick(): State {
        if (state != State.RUNNING) return state
        if (elapsedMs() >= durationMs) finish()
        return state
    }

    /** Abort early (activity going away). Cleanup still runs exactly once. */
    fun cancel() {
        if (state == State.RUNNING) finish()
    }

    private fun finish() {
        if (finished) return
        finished = true
        state = State.FINISHED
        // The `finished` guard (reset by start()) makes this fire exactly once per run
        // while keeping the callback registered for the next one.
        onFinished?.invoke()
    }

    /**
     * The sound script for a run of [durationMs]. Pure and deterministic so tests can
     * assert on it. No music track exists in here — chaos is SFX only.
     */
    fun script(): List<Beat> = scriptFor(durationMs)

    companion object {
        const val DEFAULT_DURATION_MS = 15_000L

        /** Visual beats are evaluated every [VISUAL_TICK_MS] while chaos is running. */
        const val VISUAL_TICK_MS = 250L

        fun scriptFor(durationMs: Long): List<Beat> = listOf(
            Beat(0L, ChaosSfx.KEYBOARD_CAT),
            Beat(2_500L, ChaosSfx.MEOW),
            Beat(5_000L, ChaosSfx.AIRHORN),
            Beat(7_500L, ChaosSfx.MEOW),
            Beat(10_000L, ChaosSfx.AIRHORN),
            Beat(12_500L, ChaosSfx.MEOW)
        ).filter { it.atMs < durationMs }

        /**
         * Which visual beat should be showing at [elapsedMs]. Used to drive the doge/sky
         * strobing without allocating anything per frame. Returns -1 when chaos is over or
         * visuals are disabled.
         */
        fun visualIndex(elapsedMs: Long, durationMs: Long, visualsEnabled: Boolean): Int {
            if (!visualsEnabled || elapsedMs < 0 || elapsedMs >= durationMs) return -1
            return (elapsedMs / VISUAL_TICK_MS).toInt()
        }
    }
}
