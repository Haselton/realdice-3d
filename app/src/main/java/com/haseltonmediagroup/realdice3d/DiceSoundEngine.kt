package com.haseltonmediagroup.realdice3d

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Base64
import android.util.Log
import java.io.File
import kotlin.random.Random

/** Recorded dice audio engine. Audio failures must never be able to crash the app. */
class DiceSoundEngine(private val context: Context) {
    companion object { private const val TAG = "DiceSoundEngine" }

    private var soundPool: SoundPool? = null
    private var launchSound = 0
    private var impactSound = 0
    private var initialized = false

    /**
     * Initialization is deliberately lazy and exception-safe. Some Android devices/codecs can
     * reject a particular encoded sample; that should result in silence, never an app crash.
     */
    private fun ensureInitialized() {
        if (initialized) return
        initialized = true
        runCatching {
            val pool = SoundPool.Builder()
                .setMaxStreams(8)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .build()

            val launchFile = decodeResourceToCache(R.raw.dice_launch, "dice_launch.ogg")
            val impactFile = decodeResourceToCache(R.raw.dice_hit_1, "dice_hit_1.ogg")
            launchSound = pool.load(launchFile.absolutePath, 1)
            impactSound = pool.load(impactFile.absolutePath, 1)
            soundPool = pool
        }.onFailure {
            Log.e(TAG, "Recorded dice audio could not initialize; continuing without audio", it)
            runCatching { soundPool?.release() }
            soundPool = null
            launchSound = 0
            impactSound = 0
        }
    }

    fun playLaunch(force: Float) {
        ensureInitialized()
        val pool = soundPool ?: return
        if (launchSound == 0) return
        val volume = (0.52f + force * 0.10f).coerceIn(0.52f, 0.88f)
        val rate = (0.97f + Random.nextFloat() * 0.06f).coerceIn(0.94f, 1.04f)
        runCatching { pool.play(launchSound, volume, volume, 2, 0, rate) }
    }

    fun playImpact(strength: Float) {
        ensureInitialized()
        val pool = soundPool ?: return
        if (impactSound == 0) return
        val volume = (0.13f + strength * 0.70f).coerceIn(0.12f, 0.86f)
        val rate = (0.90f + Random.nextFloat() * 0.20f).coerceIn(0.88f, 1.10f)
        runCatching { pool.play(impactSound, volume, volume, 1, 0, rate) }
    }

    fun release() {
        runCatching { soundPool?.release() }
        soundPool = null
    }

    private fun decodeResourceToCache(resourceId: Int, fileName: String): File {
        val output = File(context.cacheDir, fileName)
        if (!output.exists() || output.length() == 0L) {
            val encoded = context.resources.openRawResource(resourceId)
                .bufferedReader(Charsets.US_ASCII)
                .use { it.readText() }
                .trim()
            val decoded = Base64.decode(encoded, Base64.DEFAULT)
            require(decoded.isNotEmpty()) { "Decoded audio sample is empty" }
            output.writeBytes(decoded)
        }
        return output
    }
}
