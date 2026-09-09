package com.haseltonmediagroup.realdice3d

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.MobileAds
import com.haseltonmediagroup.realdice3d.databinding.ActivityMainBinding
import kotlin.math.abs
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), SensorEventListener {
    private lateinit var binding: ActivityMainBinding
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var lastRoll = 0L
    private var touchY = 0f
    private val tone by lazy { ToneGenerator(AudioManager.STREAM_MUSIC, 58) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        MobileAds.initialize(this)
        binding.adView.loadAd(AdRequest.Builder().build())

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        binding.dice3d.setOnRollSettledListener { first, second ->
            binding.resultText.text = "$first + $second = ${first + second}"
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 65)
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            vibrator.vibrate(VibrationEffect.createOneShot(32, VibrationEffect.DEFAULT_AMPLITUDE))
        }

        binding.diceArena.setOnClickListener { rollDice(1.0f) }
        binding.diceArena.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchY = event.y
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val flick = touchY - event.y
                    if (abs(flick) > 90f) {
                        rollDice((abs(flick) / 420f).coerceIn(0.85f, 2.4f))
                    } else {
                        rollDice(0.85f)
                    }
                    true
                }
                else -> true
            }
        }
    }

    override fun onResume() {
        super.onResume()
        binding.dice3d.onResume()
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onPause() {
        sensorManager.unregisterListener(this)
        binding.dice3d.onPause()
        super.onPause()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
        if (g > 2.35f && System.currentTimeMillis() - lastRoll > 800) {
            rollDice(g.coerceAtMost(3.2f))
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun rollDice(force: Float) {
        lastRoll = System.currentTimeMillis()
        binding.resultText.text = "ROLLING…"
        binding.dice3d.roll(force)
    }

    override fun onDestroy() {
        tone.release()
        super.onDestroy()
    }
}
