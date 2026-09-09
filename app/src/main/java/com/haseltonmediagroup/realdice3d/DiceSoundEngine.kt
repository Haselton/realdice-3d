package com.haseltonmediagroup.realdice3d

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import java.io.File

/**
 * Dice audio sourced only from the user-supplied real dice recording.
 * No synthesized sounds and no pitch shifting.
 */
class DiceSoundEngine(private val context: Context) {
    companion object { private const val TAG = "DiceSoundEngine" }

    private val handler = Handler(Looper.getMainLooper())
    private val soundPool: SoundPool = SoundPool.Builder()
        .setMaxStreams(12)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private var diceClack = 0
    @Volatile private var ready = false

    init {
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (sampleId == diceClack && status == 0) {
                ready = true
            } else if (status != 0) {
                Log.e(TAG, "Real dice sample failed to load: status=$status")
            }
        }

        runCatching {
            val sample = decodeResourceToCache(R.raw.dice_hit_1, "real_dice_clack.wav")
            diceClack = soundPool.load(sample.absolutePath, 1)
        }.onFailure {
            Log.e(TAG, "Real dice audio setup failed", it)
        }
    }

    fun playLaunch(force: Float) {
        if (!ready || diceClack == 0) return

        // Build a short tumble entirely from the authentic recorded clack.
        // Timing/volume change, but the audio itself is not synthesized or pitch-shifted.
        val strength = force.coerceIn(0.7f, 3.2f)
        val times = longArrayOf(0L, 34L, 72L, 118L, 172L, 235L, 305L)
        val volumes = floatArrayOf(0.92f, 0.78f, 0.88f, 0.68f, 0.72f, 0.57f, 0.45f)

        times.forEachIndexed { i, delay ->
            handler.postDelayed({
                if (ready) {
                    val volume = (volumes[i] * (0.82f + strength * 0.06f)).coerceIn(0.25f, 1.0f)
                    runCatching { soundPool.play(diceClack, volume, volume, 2, 0, 1.0f) }
                }
            }, delay)
        }
    }

    fun playImpact(strength: Float) {
        if (!ready || diceClack == 0) return
        val volume = (0.16f + strength * 0.74f).coerceIn(0.14f, 0.92f)
        runCatching { soundPool.play(diceClack, volume, volume, 1, 0, 1.0f) }
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        runCatching { soundPool.release() }
    }

    private fun decodeResourceToCache(resourceId: Int, fileName: String): File {
        val output = File(context.cacheDir, fileName)
        val encoded = context.resources.openRawResource(resourceId)
            .bufferedReader(Charsets.US_ASCII)
            .use { it.readText() }
            .trim()
        val decoded = Base64.decode(encoded, Base64.DEFAULT)
        require(decoded.size > 44) { "Decoded real dice WAV sample is invalid" }
        output.writeBytes(decoded)
        return output
    }
}
