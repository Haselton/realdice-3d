package com.haseltonmediagroup.realdice3d

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
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
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.MobileAds
import com.haseltonmediagroup.realdice3d.databinding.ActivityMainBinding
import kotlin.math.sqrt
import kotlin.random.Random

class MainActivity : AppCompatActivity(), SensorEventListener {
    private lateinit var binding: ActivityMainBinding
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var lastRoll = 0L
    private var touchY = 0f
    private val faces = arrayOf("⚀", "⚁", "⚂", "⚃", "⚄", "⚅")
    private val tone by lazy { ToneGenerator(AudioManager.STREAM_MUSIC, 65) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        MobileAds.initialize(this)
        binding.adView.loadAd(AdRequest.Builder().build())

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        binding.diceArena.setOnClickListener { rollDice(1.0f) }
        binding.diceArena.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { touchY = event.y; true }
                MotionEvent.ACTION_UP -> {
                    val flick = touchY - event.y
                    if (kotlin.math.abs(flick) > 90f) rollDice((kotlin.math.abs(flick) / 450f).coerceIn(0.8f, 2.2f))
                    else rollDice(0.8f)
                    true
                }
                else -> true
            }
        }
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    override fun onPause() {
        sensorManager.unregisterListener(this)
        super.onPause()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val g = sqrt(x*x + y*y + z*z) / SensorManager.GRAVITY_EARTH
        if (g > 2.35f && System.currentTimeMillis() - lastRoll > 750) {
            rollDice(g.coerceAtMost(3.2f))
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun rollDice(force: Float) {
        lastRoll = System.currentTimeMillis()
        val value = Random.nextInt(1, 7)
        val die = binding.die
        val arena = binding.diceArena
        val maxX = (arena.width - die.width).coerceAtLeast(1) * 0.35f
        val maxY = (arena.height - die.height).coerceAtLeast(1) * 0.35f

        val spinX = ObjectAnimator.ofFloat(die, "rotationX", die.rotationX, die.rotationX + 720f * force)
        val spinY = ObjectAnimator.ofFloat(die, "rotationY", die.rotationY, die.rotationY + 900f * force)
        val spinZ = ObjectAnimator.ofFloat(die, "rotation", die.rotation, die.rotation + Random.nextInt(360, 900) * force)
        val moveX = ObjectAnimator.ofFloat(die, "translationX", die.translationX, Random.nextFloat() * maxX * 2 - maxX, 0f)
        val moveY = ObjectAnimator.ofFloat(die, "translationY", die.translationY, -maxY, Random.nextFloat() * maxY, 0f)

        AnimatorSet().apply {
            playTogether(spinX, spinY, spinZ, moveX, moveY)
            duration = (650 + force * 220).toLong()
            interpolator = DecelerateInterpolator()
            start()
        }

        die.postDelayed({
            die.text = faces[value - 1]
            binding.resultText.text = "ROLL: $value"
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 70)
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            vibrator.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE))
        }, 420)
    }

    override fun onDestroy() {
        tone.release()
        super.onDestroy()
    }
}
