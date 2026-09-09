package com.haseltonmediagroup.realdice3d

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Procedural dice audio designed around short resonant clacks rather than broadband noise.
 * This keeps the prototype self contained while sounding closer to hard resin dice on a table.
 */
class DiceSoundEngine {
    private val sampleRate = 44100
    private val impacts = Array(7) { i ->
        makeTrack(buildImpact(seed = 200 + i * 31, variant = i))
    }
    private val launch = makeTrack(buildLaunchCluster())
    private var nextImpact = 0

    fun playLaunch(force: Float) {
        restart(
            launch,
            (0.34f + force * 0.12f).coerceIn(0.34f, 0.78f),
            (0.96f + Random.nextFloat() * 0.08f)
        )
    }

    fun playImpact(strength: Float) {
        val track = impacts[nextImpact]
        nextImpact = (nextImpact + 1) % impacts.size
        val pitch = 0.91f + Random.nextFloat() * 0.18f
        val volume = (0.18f + strength * 0.72f).coerceIn(0.16f, 0.92f)
        restart(track, volume, pitch)
    }

    fun release() {
        impacts.forEach { runCatching { it.release() } }
        runCatching { launch.release() }
    }

    private fun restart(track: AudioTrack, volume: Float, pitch: Float) {
        runCatching {
            track.pause()
            track.flush()
            track.setPlaybackHeadPosition(0)
            track.setVolume(volume)
            track.playbackRate = (sampleRate * pitch).toInt().coerceIn(32000, 48000)
            track.play()
        }
    }

    private fun makeTrack(samples: ShortArray): AudioTrack {
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
            .setBufferSizeInBytes(samples.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
            .also { it.write(samples, 0, samples.size) }
    }

    private fun buildImpact(seed: Int, variant: Int): ShortArray {
        val rnd = Random(seed)
        val length = (sampleRate * 0.075f).toInt()
        val f1 = 620f + variant * 34f
        val f2 = 1280f + variant * 61f
        val f3 = 2380f + variant * 83f
        val body = 165f + variant * 7f

        return ShortArray(length) { i ->
            val t = i.toFloat() / sampleRate
            val hardEnv = exp(-t * 74f)
            val ringEnv = exp(-t * 35f)
            val bodyEnv = exp(-t * 48f)

            val click = if (i < 18) {
                (rnd.nextFloat() * 2f - 1f) * (1f - i / 18f)
            } else 0f

            val resonances =
                sin(2f * PI.toFloat() * f1 * t) * 0.31f +
                sin(2f * PI.toFloat() * f2 * t + 0.7f) * 0.22f +
                sin(2f * PI.toFloat() * f3 * t + 1.1f) * 0.13f
            val lowBody = sin(2f * PI.toFloat() * body * t) * 0.24f

            val sample = click * hardEnv * 0.36f + resonances * ringEnv + lowBody * bodyEnv
            (sample * 28500f).toInt().coerceIn(-32767, 32767).toShort()
        }
    }

    /** Several separated micro-impacts; no continuous hiss/noise bed. */
    private fun buildLaunchCluster(): ShortArray {
        val duration = 0.18f
        val count = (sampleRate * duration).toInt()
        val out = FloatArray(count)
        val hitTimes = floatArrayOf(0.000f, 0.030f, 0.058f, 0.094f, 0.132f)
        val hitStrengths = floatArrayOf(0.82f, 0.64f, 0.56f, 0.43f, 0.31f)

        hitTimes.forEachIndexed { index, startSec ->
            val start = (startSec * sampleRate).toInt()
            val rnd = Random(700 + index * 19)
            val f1 = 720f + index * 55f
            val f2 = 1450f + index * 80f
            val f3 = 2550f + index * 95f
            val maxLen = (sampleRate * 0.050f).toInt()
            for (j in 0 until maxLen) {
                val pos = start + j
                if (pos >= count) break
                val t = j.toFloat() / sampleRate
                val env = exp(-t * 52f)
                val transient = if (j < 12) (rnd.nextFloat() * 2f - 1f) * (1f - j / 12f) * 0.22f else 0f
                val ring =
                    sin(2f * PI.toFloat() * f1 * t) * 0.34f +
                    sin(2f * PI.toFloat() * f2 * t + 0.5f) * 0.21f +
                    sin(2f * PI.toFloat() * f3 * t + 1.0f) * 0.11f
                out[pos] += (transient + ring) * env * hitStrengths[index]
            }
        }

        return ShortArray(count) { i ->
            (out[i].coerceIn(-1f, 1f) * 30000f).toInt().toShort()
        }
    }
}
