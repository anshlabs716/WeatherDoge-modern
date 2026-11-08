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

package com.versobit.weatherdoge.ui.sound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mute decision table is product-locked behavior: master mute silences everything,
 * the bonk has its own switch on top of the SFX switch, music never autoplays.
 */
class SoundRulesTest {

    @Test
    fun masterMuteSilencesEverything() {
        assertFalse(SoundRules.canPlaySfx(masterMute = true, sfxEnabled = true))
        assertFalse(SoundRules.canPlayBonk(masterMute = true, sfxEnabled = true, bonkEnabled = true))
        assertFalse(SoundRules.canPlayMusic(masterMute = true, musicEnabled = true))
        assertEquals(0f, SoundRules.videoVolume(masterMute = true, sfxEnabled = true), 0f)
    }

    @Test
    fun soundEffectsObeyTheSfxToggle() {
        assertTrue(SoundRules.canPlaySfx(masterMute = false, sfxEnabled = true))
        assertFalse(SoundRules.canPlaySfx(masterMute = false, sfxEnabled = false))
    }

    @Test
    fun bonkNeedsBothTheSfxToggleAndItsOwnToggle() {
        assertTrue(SoundRules.canPlayBonk(false, sfxEnabled = true, bonkEnabled = true))
        assertFalse(SoundRules.canPlayBonk(false, sfxEnabled = true, bonkEnabled = false))
        assertFalse(SoundRules.canPlayBonk(false, sfxEnabled = false, bonkEnabled = true))
        assertFalse(SoundRules.canPlayBonk(false, sfxEnabled = false, bonkEnabled = false))
        // Master mute wins over everything.
        assertFalse(SoundRules.canPlayBonk(true, sfxEnabled = true, bonkEnabled = true))
    }

    @Test
    fun musicNeedsItsOwnToggleAndIsNeverAutoplayedByTheRules() {
        assertTrue(SoundRules.canPlayMusic(masterMute = false, musicEnabled = true))
        assertFalse(SoundRules.canPlayMusic(masterMute = false, musicEnabled = false))
        // SFX settings must not influence music in either direction.
        assertFalse(SoundRules.canPlayMusic(masterMute = false, musicEnabled = false))
    }

    @Test
    fun nyanVideoVolumeFollowsTheSfxAndMasterSwitches() {
        assertEquals(1f, SoundRules.videoVolume(masterMute = false, sfxEnabled = true), 0f)
        assertEquals(0f, SoundRules.videoVolume(masterMute = false, sfxEnabled = false), 0f)
        assertEquals(0f, SoundRules.videoVolume(masterMute = true, sfxEnabled = true), 0f)
    }
}
