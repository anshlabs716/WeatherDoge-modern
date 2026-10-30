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

import android.content.Context
import android.content.SharedPreferences
import android.content.res.AssetFileDescriptor
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.preference.PreferenceManager
import android.util.Log
import java.io.IOException

/**
 * Central audio service. Owns every sound the app can make.
 *
 * == Exact slot set (by design, per product decision) ==
 *  BONK        - every UI tap AND touching the doge
 *  BG_MUSIC    - background music loop; auto-pauses during CHAOS MODE, resumes after
 *  KEYBOARD_CAT- CHAOS MODE only
 *  MEOW        - CHAOS MODE only
 *  AIRHORN     - CHAOS MODE + dedicated airhorn button
 * There is deliberately NO chaos music track: during chaos the normal music stops and
 * only SFX play. Nothing else ever plays.
 *
 * Slot resolution: each slot's asset name matches the local-extras override filenames
 * exactly. A user-provided file (.mp3/.aac/.m4a/.ogg in the git-ignored local-extras
 * source set) shadows the built-in original (.wav) with the same base name — so a local
 * build plays ONLY the user's files, while published builds play only the originals.
 *
 * Rules enforced here:
 *  - No autoplay on launch: background music starts only when explicitly requested.
 *  - Master mute + independent music/SFX toggles + dedicated bonk toggle.
 *  - Audio focus is requested for music and abandoned when it stops.
 *  - Nothing keeps playing after cancellation or when the app pauses.
 */
class SoundManager(context: Context) {

    enum class Slot(val assetName: String) {
        BONK("bonk"),
        BG_MUSIC("bg_music"),
        KEYBOARD_CAT("keyboard_cat"),
        MEOW("meow"),
        AIRHORN("airhorn");

        /** User-provided extensions first so they always shadow the built-in wav. */
        val extensions: List<String> = listOf("mp3", "m4a", "ogg", "aac", "wav")
    }

    companion object {
        private const val TAG = "SoundManager"
        private const val SOUND_DIR = "sounds"

        const val PREF_MASTER_MUTE = "pref_sound_master_mute"
        const val PREF_MUSIC_ENABLED = "pref_sound_music"
        const val PREF_SFX_ENABLED = "pref_sound_sfx"
        const val PREF_BONK_ENABLED = "pref_sound_bonk"
    }

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(appContext)
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var soundPool: SoundPool? = null
    private val soundIds = HashMap<Slot, Int>()
    private val loaded = HashSet<Slot>()

    private var musicPlayer: MediaPlayer? = null
    private var musicSlot: Slot? = null

    private var focusRequest: AudioFocusRequest? = null
    private var focusListener: AudioManager.OnAudioFocusChangeListener? = null

    var isMusicPlaying: Boolean = false
        private set
    /** True while bg music is intentionally paused (CHAOS MODE) rather than stopped. */
    private var musicPausedForChaos = false

    // ---- settings ------------------------------------------------------------

    val masterMute: Boolean get() = prefs.getBoolean(PREF_MASTER_MUTE, false)
    val musicEnabled: Boolean get() = prefs.getBoolean(PREF_MUSIC_ENABLED, true)
    val sfxEnabled: Boolean get() = prefs.getBoolean(PREF_SFX_ENABLED, true)
    val bonkEnabled: Boolean get() = prefs.getBoolean(PREF_BONK_ENABLED, true)

    // ---- lifecycle -----------------------------------------------------------

    init {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = SoundPool.Builder().setMaxStreams(5).setAudioAttributes(attrs).build()
        soundPool!!.setOnLoadCompleteListener { _, sampleId, _ ->
            synchronized(loaded) {
                soundIds.entries.firstOrNull { it.value == sampleId }?.let { loaded.add(it.key) }
            }
        }
        Slot.entries.forEach { resolveAndLoad(it) }
    }

    /** Release everything. Call when the app/process is going away. */
    fun release() {
        stopMusic()
        soundPool?.release()
        soundPool = null
        soundIds.clear()
        loaded.clear()
    }

    // ---- slot resolution -----------------------------------------------------

    /**
     * Actual asset path for a slot, e.g. "sounds/bonk.mp3" when the user provided an
     * override, "sounds/bonk.wav" when only the built-in original exists, or null.
     */
    fun resolveAsset(slot: Slot): String? {
        for (ext in slot.extensions) {
            val candidate = "$SOUND_DIR/${slot.assetName}.$ext"
            if (assetExists(candidate)) return candidate
        }
        return null
    }

    private fun assetExists(path: String): Boolean = try {
        appContext.assets.open(path).close()
        true
    } catch (e: IOException) {
        false
    }

    private fun resolveAndLoad(slot: Slot) {
        val path = resolveAsset(slot) ?: return
        try {
            val afd: AssetFileDescriptor = appContext.assets.openFd(path)
            val id = soundPool?.load(afd, 1) ?: return
            soundIds[slot] = id
            afd.close()
        } catch (e: IOException) {
            Log.w(TAG, "Cannot load sound $path", e)
        }
    }

    // ---- one-shot SFX --------------------------------------------------------

    /**
     * Play a short sound effect if SFX are enabled and not globally muted.
     * Safe to call from any thread; playback is dispatched internally by SoundPool.
     */
    fun play(slot: Slot, volume: Float = 1f) {
        if (masterMute || !sfxEnabled) return
        if (slot == Slot.BONK && !bonkEnabled) return
        val id = soundIds[slot] ?: return
        soundPool?.play(id, volume, volume, 1, 0, 1f)
    }

    /** The bonk doubles as the UI tap sound and the doge touch sound. */
    fun playBonk() = play(Slot.BONK)

    // ---- background music ----------------------------------------------------

    /**
     * Start (or restart) the looping background music. Requests audio focus.
     * Never called on launch — only by explicit user-driven triggers.
     */
    @Synchronized
    fun startMusic() {
        if (masterMute || !musicEnabled) return
        val path = resolveAsset(Slot.BG_MUSIC) ?: run {
            Log.w(TAG, "No background music asset found"); return
        }
        if (musicPlayer != null && musicSlot == Slot.BG_MUSIC) {
            // Already have it; just make sure it's rolling.
            resumeMusic()
            return
        }
        stopMusicInternal()
        try {
            val afd = appContext.assets.openFd(path)
            val player = MediaPlayer()
            player.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            player.isLooping = true
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            player.setOnPreparedListener {
                if (requestFocus()) {
                    it.start()
                    isMusicPlaying = true
                    musicSlot = Slot.BG_MUSIC
                    musicPlayer = it
                    musicPausedForChaos = false
                } else {
                    it.release()
                    if (musicPlayer === it) musicPlayer = null
                }
            }
            player.setOnErrorListener { mp, what, extra ->
                Log.w(TAG, "MediaPlayer error what=$what extra=$extra")
                mp.release()
                if (musicPlayer === mp) musicPlayer = null
                isMusicPlaying = false
                true
            }
            musicPlayer = player
            player.prepareAsync()
        } catch (e: IOException) {
            Log.w(TAG, "Cannot start background music", e)
        }
    }

    /**
     * Pause the background music because CHAOS MODE started. Remembers the position so
     * [resumeMusicAfterChaos] restores the exact same spot.
     */
    @Synchronized
    fun pauseMusicForChaos() {
        val p = musicPlayer ?: return
        try {
            if (p.isPlaying) {
                p.pause()
                isMusicPlaying = false
                musicPausedForChaos = true
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "pauseMusicForChaos failed", e)
        }
    }

    /** CHAOS MODE finished: restore the background music where it left off. */
    @Synchronized
    fun resumeMusicAfterChaos() {
        if (!musicPausedForChaos) return
        musicPausedForChaos = false
        if (masterMute || !musicEnabled) return
        val p = musicPlayer ?: return
        try {
            if (!p.isPlaying) {
                if (requestFocus()) {
                    p.start()
                    isMusicPlaying = true
                }
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "resumeMusicAfterChaos failed", e)
        }
    }

    /** Toggle pause/resume for the settings preview button. */
    @Synchronized
    fun resumeMusic() {
        if (masterMute || !musicEnabled) return
        val p = musicPlayer ?: return
        try {
            if (!p.isPlaying && requestFocus()) {
                p.start()
                isMusicPlaying = true
            }
        } catch (e: IllegalStateException) { /* already released */ }
    }

    /** Stop music entirely (leaving the app, explicit stop). Abandons audio focus. */
    @Synchronized
    fun stopMusic() {
        musicPausedForChaos = false
        stopMusicInternal()
        abandonFocus()
    }

    private fun stopMusicInternal() {
        val p = musicPlayer
        musicPlayer = null
        isMusicPlaying = false
        musicSlot = null
        if (p != null) {
            try {
                if (p.isPlaying) p.stop()
            } catch (e: IllegalStateException) { /* already gone */ }
            p.release()
        }
    }

    fun hasBackgroundMusic(): Boolean = musicPlayer != null

    // ---- audio focus ---------------------------------------------------------

    private fun onAudioFocusChanged(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Another app wants the audio: pause cleanly, never fight over focus.
                musicPlayer?.let { p ->
                    try {
                        if (p.isPlaying) p.pause()
                    } catch (e: IllegalStateException) { }
                }
                isMusicPlaying = false
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                val p = musicPlayer ?: return
                try {
                    if (!p.isPlaying && musicEnabled && !masterMute && !musicPausedForChaos) {
                        p.start()
                        isMusicPlaying = true
                    }
                } catch (e: IllegalStateException) { }
            }
        }
    }

    private fun requestFocus(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val l = focusListener
                ?: AudioManager.OnAudioFocusChangeListener { onAudioFocusChanged(it) }
                    .also { focusListener = it }
            val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener(l)
                .build().also { focusRequest = it }
            return audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        @Suppress("DEPRECATION")
        return audioManager.requestAudioFocus(
            { onAudioFocusChanged(it) },
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN
        ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
        // Focus teardown is async; keep a no-op reference so the handler isn't GC'd mid-post.
        mainHandler.postDelayed({ Log.v(TAG, "focus abandoned") }, 80)
    }
}
