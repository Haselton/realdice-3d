package com.haseltonmediagroup.realdice3d

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Base64
import java.io.File
import kotlin.random.Random

/**
 * Recorded dice audio engine.
 *
 * The source recording supplied for reference contains extra creator audio, so only the clean
 * dice-only sections are embedded in the app. A longer tumble sample is used for the throw and
 * a short hard-table impact sample is retriggered with small pitch/volume variations for the
 * individual physics collisions.
 */
class DiceSoundEngine(private val context: Context) {
    private val soundPool = SoundPool.Builder()
        .setMaxStreams(8)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val launchSound: Int
    private val impactSound: Int

    init {
        val launchFile = decodeResourceToCache(R.raw.dice_launch, "dice_launch.ogg")
        val impactFile = decodeResourceToCache(R.raw.dice_hit_1, "dice_hit_1.ogg")
        launchSound = soundPool.load(launchFile.absolutePath, 1)
        impactSound = soundPool.load(impactFile.absolutePath, 1)
    }

    fun playLaunch(force: Float) {
        val volume = (0.52f + force * 0.10f).coerceIn(0.52f, 0.88f)
        val rate = (0.97f + Random.nextFloat() * 0.06f).coerceIn(0.94f, 1.04f)
        soundPool.play(launchSound, volume, volume, 2, 0, rate)
    }

    fun playImpact(strength: Float) {
        // Tiny variations keep repeated collisions from sounding like the exact same sample.
        val volume = (0.13f + strength * 0.70f).coerceIn(0.12f, 0.86f)
        val rate = (0.90f + Random.nextFloat() * 0.20f).coerceIn(0.88f, 1.10f)
        soundPool.play(impactSound, volume, volume, 1, 0, rate)
    }

    fun release() {
        soundPool.release()
    }

    private fun decodeResourceToCache(resourceId: Int, fileName: String): File {
        val output = File(context.cacheDir, fileName)
        if (!output.exists() || output.length() == 0L) {
            val encoded = context.resources.openRawResource(resourceId)
                .bufferedReader()
                .use { it.readText() }
            output.writeBytes(Base64.decode(encoded, Base64.DEFAULT))
        }
        return output
    }
}
