package com.haseltonmediagroup.realdice3d

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/** Lightweight procedural audio so prototype builds have synchronized dice sounds without bundled assets. */
class DiceSoundEngine {
    private val sampleRate = 22050
    private val impactTracks = Array(5) { index ->
        makeTrack(buildImpact(seed = 41 + index * 17, brightness = 0.85f + index * 0.07f))
    }
    private val launchTrack = makeTrack(buildLaunch())
    private var nextImpact = 0

    fun playLaunch(force: Float) {
        restart(launchTrack, (0.28f + force * 0.11f).coerceIn(0.28f, 0.72f), 1.0f)
    }

    fun playImpact(strength: Float) {
        val track = impactTracks[nextImpact]
        nextImpact = (nextImpact + 1) % impactTracks.size
        val pitch = 0.88f + Random.nextFloat() * 0.24f
        restart(track, (0.18f + strength * 0.64f).coerceIn(0.16f, 0.88f), pitch)
    }

    fun release() {
        impactTracks.forEach { runCatching { it.release() } }
        runCatching { launchTrack.release() }
    }

    private fun restart(track: AudioTrack, volume: Float, pitch: Float) {
        runCatching {
            track.pause()
            track.flush()
            track.setPlaybackHeadPosition(0)
            track.setVolume(volume)
            track.playbackRate = (sampleRate * pitch).toInt().coerceIn(16000, 30000)
            track.play()
        }
    }

    private fun makeTrack(samples: ShortArray): AudioTrack {
        val bytes = samples.size * 2
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bytes)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
            .also { it.write(samples, 0, samples.size) }
    }

    private fun buildImpact(seed: Int, brightness: Float): ShortArray {
        val rnd = Random(seed)
        val count = (sampleRate * 0.095f).toInt()
        return ShortArray(count) { i ->
            val t = i.toFloat() / sampleRate
            val env = exp(-t * 46f)
            val thud = sin(2f * PI.toFloat() * (150f + brightness * 40f) * t) * 0.42f
            val click = (rnd.nextFloat() * 2f - 1f) * 0.55f * exp(-t * 85f)
            ((thud + click) * env * 30000f).toInt().coerceIn(-32767, 32767).toShort()
        }
    }

    private fun buildLaunch(): ShortArray {
        val rnd = Random(917)
        val count = (sampleRate * 0.19f).toInt()
        return ShortArray(count) { i ->
            val t = i.toFloat() / sampleRate
            val env = exp(-t * 16f)
            val rattle = (rnd.nextFloat() * 2f - 1f) * 0.25f
            val body = sin(2f * PI.toFloat() * (115f + 90f * t) * t) * 0.18f
            ((rattle + body) * env * 26000f).toInt().coerceIn(-32767, 32767).toShort()
        }
    }
}
