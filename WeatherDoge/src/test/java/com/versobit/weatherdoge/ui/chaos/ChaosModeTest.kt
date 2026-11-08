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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks down the CHAOS MODE contract: 15 monotonic seconds, cleanup exactly once,
 * reduced motion never shortens the run, and the script is SFX-only.
 */
class ChaosModeTest {

    private var now = 0L
    private var finishedCount = 0
    private val chaos = ChaosMode(clock = { now }).apply {
        onFinished = { finishedCount++ }
    }

    @Test
    fun defaultDurationIsFifteenSeconds() {
        assertEquals(15_000L, ChaosMode.DEFAULT_DURATION_MS)
        // A fresh instance still has the full run ahead of it.
        assertEquals(15_000L, chaos.remainingMs())
    }

    @Test
    fun startsIdle() {
        assertEquals(ChaosMode.State.IDLE, chaos.state)
        assertFalse(chaos.isActive)
        assertFalse(chaos.visualsEnabled)
        assertEquals(0L, chaos.elapsedMs())
        assertEquals(0, finishedCount)
    }

    @Test
    fun runsForExactlyFifteenSecondsOfMonotonicTime() {
        assertTrue(chaos.start())
        assertEquals(ChaosMode.State.RUNNING, chaos.state)
        assertTrue(chaos.isActive)

        now = 14_999L
        chaos.tick()
        assertEquals(ChaosMode.State.RUNNING, chaos.state)
        assertEquals(0, finishedCount)

        now = 15_000L
        chaos.tick()
        assertEquals(ChaosMode.State.FINISHED, chaos.state)
        assertEquals(1, finishedCount)
    }

    @Test
    fun cleanupFiresExactlyOnceEvenIfTickedAgain() {
        chaos.start()
        now = 15_000L
        chaos.tick()
        chaos.tick()
        chaos.tick()
        chaos.cancel()
        assertEquals(1, finishedCount)
    }

    @Test
    fun cancelMidRunFinishesAndCleansUpOnce() {
        chaos.start()
        now = 5_000L
        chaos.cancel()
        assertEquals(ChaosMode.State.FINISHED, chaos.state)
        assertEquals(1, finishedCount)

        // A late tick or second cancel must not run cleanup again.
        now = 15_000L
        chaos.tick()
        chaos.cancel()
        assertEquals(1, finishedCount)
    }

    @Test
    fun reducedMotionKeepsTheFullFifteenSeconds() {
        assertTrue(chaos.start(reduceMotion = true))
        assertFalse(chaos.visualsEnabled)

        now = 14_999L
        chaos.tick()
        assertEquals(ChaosMode.State.RUNNING, chaos.state)

        now = 15_000L
        chaos.tick()
        assertEquals(ChaosMode.State.FINISHED, chaos.state)
        assertEquals(1, finishedCount)
    }

    @Test
    fun visualsEnabledOnlyWhileRunningWithoutReducedMotion() {
        chaos.start(reduceMotion = false)
        assertTrue(chaos.visualsEnabled)
        now = 15_000L
        chaos.tick()
        assertFalse(chaos.visualsEnabled)
    }

    @Test
    fun secondStartWhileRunningIsRejected() {
        chaos.start()
        now = 1_000L
        assertFalse(chaos.start())
        assertEquals(ChaosMode.State.RUNNING, chaos.state)
        assertEquals(0, finishedCount) // still running, nothing finished
    }

    @Test
    fun restartAfterFinishIsAllowed() {
        chaos.start()
        now = 15_000L
        chaos.tick()
        assertEquals(1, finishedCount)

        now = 20_000L
        assertTrue(chaos.start())
        assertEquals(ChaosMode.State.RUNNING, chaos.state)
        now = 35_000L
        chaos.tick()
        assertEquals(2, finishedCount)
    }

    @Test
    fun remainingAndProgressTrackTheClock() {
        chaos.start()
        now = 7_500L
        assertEquals(7_500L, chaos.remainingMs())
        assertEquals(0.5f, chaos.progress(), 0.0001f)
        now = 15_000L
        assertEquals(0L, chaos.remainingMs())
        assertEquals(1f, chaos.progress(), 0.0001f)
    }

    @Test
    fun tickWhenIdleIsANoop() {
        chaos.tick()
        assertEquals(ChaosMode.State.IDLE, chaos.state)
        assertEquals(0, finishedCount)
    }

    @Test
    fun scriptIsSfxOnlyAndFiresWithinTheRun() {
        val script = chaos.script()
        assertTrue(script.isNotEmpty())

        // The very first beat is the keyboard cat that opens the run.
        assertEquals(0L, script.first().atMs)
        assertEquals(ChaosMode.ChaosSfx.KEYBOARD_CAT, script.first().sfx)

        for (beat in script) {
            assertTrue("beat must happen inside the run", beat.atMs < ChaosMode.DEFAULT_DURATION_MS)
            // Only these three effects exist; there is deliberately no music track.
            when (beat.sfx) {
                ChaosMode.ChaosSfx.KEYBOARD_CAT,
                ChaosMode.ChaosSfx.MEOW,
                ChaosMode.ChaosSfx.AIRHORN -> Unit
            }
        }

        // Beats are strictly increasing so the ticker fires each exactly once.
        for (i in 1 until script.size) {
            assertTrue(script[i].atMs > script[i - 1].atMs)
        }
    }

    @Test
    fun shortRunsTrimTheirScript() {
        val short = ChaosMode.scriptFor(3_000L)
        assertTrue(short.all { it.atMs < 3_000L })
        // The t=0 keyboard cat and the t=2500 meow fit; the t=5000 airhorn does not.
        assertEquals(2, short.size)
        assertEquals(ChaosMode.ChaosSfx.KEYBOARD_CAT, short[0].sfx)
        assertEquals(ChaosMode.ChaosSfx.MEOW, short[1].sfx)
    }

    @Test
    fun visualIndexRespectsBoundsAndReducedMotion() {
        assertEquals(-1, ChaosMode.visualIndex(0L, 15_000L, visualsEnabled = false))
        assertEquals(0, ChaosMode.visualIndex(0L, 15_000L, visualsEnabled = true))
        assertEquals(4, ChaosMode.visualIndex(1_000L, 15_000L, visualsEnabled = true))
        assertEquals(-1, ChaosMode.visualIndex(15_000L, 15_000L, visualsEnabled = true))
        assertEquals(-1, ChaosMode.visualIndex(-5L, 15_000L, visualsEnabled = true))
    }
}
