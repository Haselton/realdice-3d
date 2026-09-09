package com.haseltonmediagroup.realdice3d

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Base64
import android.util.Log
import java.io.File
import kotlin.random.Random

/** Recorded dice audio engine backed by PCM WAV samples extracted from the supplied reference. */
class DiceSoundEngine(private val context: Context) {
    companion object { private const val TAG = "DiceSoundEngine" }

    private val soundPool: SoundPool = SoundPool.Builder()
        .setMaxStreams(8)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private var launchSound = 0
    private var impactSound = 0
    @Volatile private var launchReady = false
    @Volatile private var impactReady = false

    init {
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) {
                if (sampleId == launchSound) launchReady = true
                if (sampleId == impactSound) impactReady = true
            } else {
                Log.e(TAG, "SoundPool failed to load sample id=$sampleId status=$status")
            }
        }

        runCatching {
            val launchFile = decodeResourceToCache(R.raw.dice_launch, "dice_launch_v2.wav")
            val impactFile = decodeResourceToCache(R.raw.dice_hit_1, "dice_hit_1_v2.wav")
            launchSound = soundPool.load(launchFile.absolutePath, 1)
            impactSound = soundPool.load(impactFile.absolutePath, 1)
        }.onFailure {
            Log.e(TAG, "Recorded dice audio setup failed", it)
        }
    }

    fun playLaunch(force: Float) {
        if (!launchReady || launchSound == 0) return
        val volume = (0.62f + force * 0.09f).coerceIn(0.62f, 0.95f)
        val rate = (0.97f + Random.nextFloat() * 0.06f).coerceIn(0.95f, 1.04f)
        runCatching { soundPool.play(launchSound, volume, volume, 2, 0, rate) }
    }

    fun playImpact(strength: Float) {
        if (!impactReady || impactSound == 0) return
        val volume = (0.18f + strength * 0.75f).coerceIn(0.16f, 0.95f)
        val rate = (0.91f + Random.nextFloat() * 0.17f).coerceIn(0.90f, 1.08f)
        runCatching { soundPool.play(impactSound, volume, volume, 1, 0, rate) }
    }

    fun release() {
        runCatching { soundPool.release() }
    }

    private fun decodeResourceToCache(resourceId: Int, fileName: String): File {
        val output = File(context.cacheDir, fileName)
        val encoded = context.resources.openRawResource(resourceId)
            .bufferedReader(Charsets.US_ASCII)
            .use { it.readText() }
            .trim()
        val decoded = Base64.decode(encoded, Base64.DEFAULT)
        require(decoded.size > 44) { "Decoded WAV sample is invalid" }
        output.writeBytes(decoded)
        return output
    }
}
