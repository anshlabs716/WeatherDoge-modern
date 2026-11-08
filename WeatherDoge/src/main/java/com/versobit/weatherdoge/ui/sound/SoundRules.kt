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

/**
 * The mute/enable decision table for the sound system, extracted so it can be unit tested
 * without a device. [com.versobit.weatherdoge.ui.sound.SoundManager] is the only consumer.
 *
 * Rules (locked by product decision):
 *  - master mute silences everything, including the NYAN video,
 *  - sound effects obey the SFX toggle, and the bonk additionally obeys its own toggle,
 *  - background music obeys the music toggle and is never autoplayed by anything in here.
 */
object SoundRules {

    /** May a one-shot sound effect play right now? */
    fun canPlaySfx(masterMute: Boolean, sfxEnabled: Boolean): Boolean =
        !masterMute && sfxEnabled

    /** May the bonk play? It is gated by the SFX toggle *and* its dedicated toggle. */
    fun canPlayBonk(masterMute: Boolean, sfxEnabled: Boolean, bonkEnabled: Boolean): Boolean =
        canPlaySfx(masterMute, sfxEnabled) && bonkEnabled

    /** May the background music start or resume? */
    fun canPlayMusic(masterMute: Boolean, musicEnabled: Boolean): Boolean =
        !masterMute && musicEnabled

    /** Left/right gain for the NYAN video. 0f when everything is muted. */
    fun videoVolume(masterMute: Boolean, sfxEnabled: Boolean): Float =
        if (canPlaySfx(masterMute, sfxEnabled)) 1f else 0f
}
