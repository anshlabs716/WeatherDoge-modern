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

class SoundManager(context: Context) {

    enum class Slot(val assetName: String) {
        BONK("bonk"),
        BG_MUSIC("bg_music"),
        KEYBOARD_CAT("keyboard_cat"),
        MEOW("meow"),
        AIRHORN("airhorn"),
        NYAN("nyan");

        val extensions: List<String> = listOf("mp3", "aac", "m4a", "ogg", "wav", "mp4")
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

    private var musicPausedForChaos = false

    val masterMute: Boolean get() = prefs.getBoolean(PREF_MASTER_MUTE, false)
    val musicEnabled: Boolean get() = prefs.getBoolean(PREF_MUSIC_ENABLED, true)
    val sfxEnabled: Boolean get() = prefs.getBoolean(PREF_SFX_ENABLED, true)
    val bonkEnabled: Boolean get() = prefs.getBoolean(PREF_BONK_ENABLED, true)

    init {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = SoundPool.Builder().setMaxStreams(5).setAudioAttributes(attrs).build()
        soundPool?.setOnLoadCompleteListener { _, sampleId, _ ->
            synchronized(loaded) {
                soundIds.entries.firstOrNull { it.value == sampleId }?.let { loaded.add(it.key) }
            }
        }
        Slot.entries.forEach { resolveAndLoad(it) }
    }

    fun release() {
        stopMusic()
        soundPool?.release()
        soundPool = null
        soundIds.clear()
        loaded.clear()
    }

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
        if (slot == Slot.NYAN || slot == Slot.BG_MUSIC) return
        val path = resolveAsset(slot) ?: return
        try {
            val afd: AssetFileDescriptor = appContext.assets.openFd(path)
            val id = soundPool?.load(afd, 1) ?: return
            soundIds[slot] = id
            afd.close()
        } catch (e: Exception) {
            Log.w(TAG, "Cannot load sound $path", e)
        }
    }

    fun play(slot: Slot, volume: Float = 1f) {
        if (masterMute || !sfxEnabled) return
        if (slot == Slot.BONK && !bonkEnabled) return
        val id = soundIds[slot] ?: return
        soundPool?.play(id, volume, volume, 1, 0, 1f)
    }

    fun playBonk() = play(Slot.BONK)

    @Synchronized
    fun startMusic() {
        if (masterMute || !musicEnabled) return
        val path = resolveAsset(Slot.BG_MUSIC) ?: run {
            Log.w(TAG, "No background music asset found")
            return
        }
        if (musicPlayer != null && musicSlot == Slot.BG_MUSIC) {
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
        } catch (e: Exception) {
            Log.w(TAG, "Cannot start background music", e)
        }
    }

    @Synchronized
    fun pauseMusicForChaos() {
        val p = musicPlayer ?: return
        try {
            if (p.isPlaying) {
                p.pause()
                isMusicPlaying = false
                musicPausedForChaos = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "pauseMusicForChaos failed", e)
        }
    }

    @Synchronized
    fun resumeMusicAfterChaos() {
        if (!musicPausedForChaos) return
        musicPausedForChaos = false
        if (masterMute || !musicEnabled) return
        val p = musicPlayer ?: return
        try {
            if (!p.isPlaying && requestFocus()) {
                p.start()
                isMusicPlaying = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "resumeMusicAfterChaos failed", e)
        }
    }

    @Synchronized
    fun resumeMusic() {
        if (masterMute || !musicEnabled) return
        val p = musicPlayer ?: return
        try {
            if (!p.isPlaying && requestFocus()) {
                p.start()
                isMusicPlaying = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "resumeMusic failed", e)
        }
    }

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
            } catch (e: Exception) { }
            p.release()
        }
    }

    fun hasBackgroundMusic(): Boolean = musicPlayer != null

    private fun onAudioFocusChanged(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                musicPlayer?.let { p ->
                    try {
                        if (p.isPlaying) p.pause()
                    } catch (e: Exception) { }
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
                } catch (e: Exception) { }
            }
        }
    }

    private fun requestFocus(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val l = focusListener ?: AudioManager.OnAudioFocusChangeListener { onAudioFocusChanged(it) }
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
    }
}
