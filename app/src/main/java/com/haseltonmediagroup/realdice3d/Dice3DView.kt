package com.haseltonmediagroup.realdice3d

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.AttributeSet
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Lightweight OpenGL ES dice renderer.
 * The cube is true 3D geometry; pips are separate geometry placed just above each face.
 * Animation deliberately converges to a perfectly flat face so the result is readable.
 */
class Dice3DView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    private val diceRenderer = DiceRenderer()
    private var resultListener: ((Int) -> Unit)? = null

    init {
        setEGLContextClientVersion(2)
        setRenderer(diceRenderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        preserveEGLContextOnPause = true
    }

    fun setOnRollSettledListener(listener: (Int) -> Unit) {
        resultListener = listener
        diceRenderer.onSettled = { value -> post { resultListener?.invoke(value) } }
    }

    fun roll(force: Float = 1f) {
        queueEvent { diceRenderer.roll(force.coerceIn(0.7f, 3.2f)) }
    }

    private class DiceRenderer : Renderer {
        var onSettled: ((Int) -> Unit)? = null

        private val projection = FloatArray(16)
        private val view = FloatArray(16)
        private val model = FloatArray(16)
        private val mv = FloatArray(16)
        private val mvp = FloatArray(16)

        private var program = 0
        private var width = 1
        private var height = 1

        private var rolling = false
        private var notified = true
        private var result = 5
        private var startNs = 0L
        private var durationSec = 1.65f
        private var startX = 0f
        private var startY = 0f
        private var startZ = 0f
        private var targetX = 0f
        private var targetY = 0f
        private var targetZ = 0f
        private var spinX = 900f
        private var spinY = 760f
        private var spinZ = 620f

        private var displayX = 90f
        private var displayY = 0f
        private var displayZ = 0f

        private val faceVertices = floatArrayOf(
            // +Y top
            -1f, 1f,-1f,   1f, 1f,-1f,   1f, 1f, 1f,
            -1f, 1f,-1f,   1f, 1f, 1f,  -1f, 1f, 1f,
            // -Y bottom
            -1f,-1f, 1f,   1f,-1f, 1f,   1f,-1f,-1f,
            -1f,-1f, 1f,   1f,-1f,-1f,  -1f,-1f,-1f,
            // +Z front
            -1f,-1f, 1f,   1f,-1f, 1f,   1f, 1f, 1f,
            -1f,-1f, 1f,   1f, 1f, 1f,  -1f, 1f, 1f,
            // -Z back
             1f,-1f,-1f,  -1f,-1f,-1f,  -1f, 1f,-1f,
             1f,-1f,-1f,  -1f, 1f,-1f,   1f, 1f,-1f,
            // +X right
             1f,-1f, 1f,   1f,-1f,-1f,   1f, 1f,-1f,
             1f,-1f, 1f,   1f, 1f,-1f,   1f, 1f, 1f,
            // -X left
            -1f,-1f,-1f,  -1f,-1f, 1f,  -1f, 1f, 1f,
            -1f,-1f,-1f,  -1f, 1f, 1f,  -1f, 1f,-1f
        )

        private val faceColors = FloatArray(36 * 4).apply {
            for (i in 0 until 36) {
                val face = i / 6
                val shade = when (face) {
                    0 -> 1.00f
                    2, 4 -> 0.92f
                    3, 5 -> 0.78f
                    else -> 0.70f
                }
                this[i * 4] = 0.94f * shade
                this[i * 4 + 1] = 0.93f * shade
                this[i * 4 + 2] = 0.88f * shade
                this[i * 4 + 3] = 1f
            }
        }

        private val cubeBuffer = buffer(faceVertices)
        private val colorBuffer = buffer(faceColors)

        override fun onSurfaceCreated(gl: javax.microedition.khronos.opengles.GL10?, config: javax.microedition.khronos.egl.EGLConfig?) {
            GLES20.glClearColor(0.025f, 0.055f, 0.045f, 1f)
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        }

        override fun onSurfaceChanged(gl: javax.microedition.khronos.opengles.GL10?, w: Int, h: Int) {
            width = w.coerceAtLeast(1)
            height = h.coerceAtLeast(1)
            GLES20.glViewport(0, 0, width, height)
            val ratio = width.toFloat() / height.toFloat()
            Matrix.perspectiveM(projection, 0, 40f, ratio, 1f, 30f)
            Matrix.setLookAtM(view, 0, 0f, 4.7f, 7.0f, 0f, 0.15f, 0f, 0f, 1f, 0f)
        }

        override fun onDrawFrame(gl: javax.microedition.khronos.opengles.GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            updateAnimation()

            Matrix.setIdentityM(model, 0)
            val lift = if (rolling) currentLift() else 0f
            Matrix.translateM(model, 0, 0f, lift, 0f)
            Matrix.scaleM(model, 0, 1.18f, 1.18f, 1.18f)
            Matrix.rotateM(model, 0, displayX, 1f, 0f, 0f)
            Matrix.rotateM(model, 0, displayY, 0f, 1f, 0f)
            Matrix.rotateM(model, 0, displayZ, 0f, 0f, 1f)

            Matrix.multiplyMM(mv, 0, view, 0, model, 0)
            Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)

            drawCube()
            drawAllPips()
        }

        fun roll(force: Float) {
            result = Random.nextInt(1, 7)
            startX = displayX
            startY = displayY
            startZ = displayZ
            val target = orientationFor(result)
            targetX = target[0]
            targetY = target[1]
            targetZ = target[2]
            spinX = Random.nextInt(720, 1260) * force
            spinY = Random.nextInt(700, 1180) * force
            spinZ = Random.nextInt(450, 900) * force
            durationSec = (1.35f + 0.22f * force).coerceAtMost(2.05f)
            startNs = System.nanoTime()
            rolling = true
            notified = false
        }

        private fun updateAnimation() {
            if (!rolling) return
            val elapsed = (System.nanoTime() - startNs) / 1_000_000_000f
            val t = (elapsed / durationSec).coerceIn(0f, 1f)
            val ease = 1f - (1f - t) * (1f - t) * (1f - t)
            val damping = exp(-4.3f * t)

            displayX = lerp(startX, targetX, ease) + spinX * (1f - ease) * damping
            displayY = lerp(startY, targetY, ease) + spinY * (1f - ease) * damping
            displayZ = lerp(startZ, targetZ, ease) + spinZ * (1f - ease) * damping

            if (t >= 1f) {
                displayX = targetX
                displayY = targetY
                displayZ = targetZ
                rolling = false
                if (!notified) {
                    notified = true
                    onSettled?.invoke(result)
                }
            }
        }

        private fun currentLift(): Float {
            val elapsed = (System.nanoTime() - startNs) / 1_000_000_000f
            val t = (elapsed / durationSec).coerceIn(0f, 1f)
            val hops = kotlin.math.abs(sin(t * PI.toFloat() * 4.2f))
            return 1.65f * (1f - t) * hops
        }

        private fun orientationFor(value: Int): FloatArray = when (value) {
            1 -> floatArrayOf(0f, 0f, 0f)
            6 -> floatArrayOf(180f, 0f, 0f)
            2 -> floatArrayOf(-90f, 0f, 0f)
            5 -> floatArrayOf(90f, 0f, 0f)
            3 -> floatArrayOf(0f, 0f, 90f)
            else -> floatArrayOf(0f, 0f, -90f)
        }

        private fun drawCube() {
            GLES20.glUseProgram(program)
            val pos = GLES20.glGetAttribLocation(program, "aPosition")
            val color = GLES20.glGetAttribLocation(program, "aColor")
            val matrix = GLES20.glGetUniformLocation(program, "uMVP")
            GLES20.glUniformMatrix4fv(matrix, 1, false, mvp, 0)
            cubeBuffer.position(0)
            colorBuffer.position(0)
            GLES20.glEnableVertexAttribArray(pos)
            GLES20.glEnableVertexAttribArray(color)
            GLES20.glVertexAttribPointer(pos, 3, GLES20.GL_FLOAT, false, 12, cubeBuffer)
            GLES20.glVertexAttribPointer(color, 4, GLES20.GL_FLOAT, false, 16, colorBuffer)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 36)
            GLES20.glDisableVertexAttribArray(pos)
            GLES20.glDisableVertexAttribArray(color)
        }

        private fun drawAllPips() {
            // Numbering: +Y=1, -Y=6, +Z=2, -Z=5, +X=3, -X=4.
            drawFacePips(1, Face.TOP)
            drawFacePips(6, Face.BOTTOM)
            drawFacePips(2, Face.FRONT)
            drawFacePips(5, Face.BACK)
            drawFacePips(3, Face.RIGHT)
            drawFacePips(4, Face.LEFT)
        }

        private fun drawFacePips(value: Int, face: Face) {
            val spots = pipPattern(value)
            spots.forEach { (u, v) -> drawPip(face, u, v) }
        }

        private fun drawPip(face: Face, u: Float, v: Float) {
            val radius = 0.135f
            val segments = 18
            val verts = FloatArray((segments + 2) * 3)
            val normalOffset = 1.006f
            fun point(index: Int, a: Float, b: Float) {
                val xyz = when (face) {
                    Face.TOP -> floatArrayOf(a, normalOffset, b)
                    Face.BOTTOM -> floatArrayOf(a, -normalOffset, -b)
                    Face.FRONT -> floatArrayOf(a, b, normalOffset)
                    Face.BACK -> floatArrayOf(-a, b, -normalOffset)
                    Face.RIGHT -> floatArrayOf(normalOffset, b, -a)
                    Face.LEFT -> floatArrayOf(-normalOffset, b, a)
                }
                verts[index * 3] = xyz[0]
                verts[index * 3 + 1] = xyz[1]
                verts[index * 3 + 2] = xyz[2]
            }
            point(0, u, v)
            for (i in 0..segments) {
                val angle = 2.0 * PI * i / segments
                point(i + 1, u + radius * kotlin.math.cos(angle).toFloat(), v + radius * kotlin.math.sin(angle).toFloat())
            }
            val vb = buffer(verts)
            val colors = FloatArray((segments + 2) * 4)
            for (i in 0 until segments + 2) {
                colors[i * 4] = 0.055f
                colors[i * 4 + 1] = 0.06f
                colors[i * 4 + 2] = 0.065f
                colors[i * 4 + 3] = 1f
            }
            val cb = buffer(colors)
            GLES20.glUseProgram(program)
            val pos = GLES20.glGetAttribLocation(program, "aPosition")
            val color = GLES20.glGetAttribLocation(program, "aColor")
            val matrix = GLES20.glGetUniformLocation(program, "uMVP")
            GLES20.glUniformMatrix4fv(matrix, 1, false, mvp, 0)
            GLES20.glEnableVertexAttribArray(pos)
            GLES20.glEnableVertexAttribArray(color)
            GLES20.glVertexAttribPointer(pos, 3, GLES20.GL_FLOAT, false, 12, vb)
            GLES20.glVertexAttribPointer(color, 4, GLES20.GL_FLOAT, false, 16, cb)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, segments + 2)
            GLES20.glDisableVertexAttribArray(pos)
            GLES20.glDisableVertexAttribArray(color)
        }

        private fun pipPattern(value: Int): List<Pair<Float, Float>> {
            val l = -0.48f
            val c = 0f
            val r = 0.48f
            val t = 0.48f
            val b = -0.48f
            return when (value) {
                1 -> listOf(c to c)
                2 -> listOf(l to t, r to b)
                3 -> listOf(l to t, c to c, r to b)
                4 -> listOf(l to t, r to t, l to b, r to b)
                5 -> listOf(l to t, r to t, c to c, l to b, r to b)
                else -> listOf(l to t, r to t, l to c, r to c, l to b, r to b)
            }
        }

        private fun createProgram(vertex: String, fragment: String): Int {
            fun shader(type: Int, source: String): Int {
                val id = GLES20.glCreateShader(type)
                GLES20.glShaderSource(id, source)
                GLES20.glCompileShader(id)
                return id
            }
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vertex))
            GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fragment))
            GLES20.glLinkProgram(p)
            return p
        }

        private enum class Face { TOP, BOTTOM, FRONT, BACK, RIGHT, LEFT }

        companion object {
            private const val VERTEX_SHADER = """
                uniform mat4 uMVP;
                attribute vec4 aPosition;
                attribute vec4 aColor;
                varying vec4 vColor;
                void main() {
                    gl_Position = uMVP * aPosition;
                    vColor = aColor;
                }
            """
            private const val FRAGMENT_SHADER = """
                precision mediump float;
                varying vec4 vColor;
                void main() { gl_FragColor = vColor; }
            """

            private fun buffer(values: FloatArray): FloatBuffer = ByteBuffer
                .allocateDirect(values.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply { put(values); position(0) }

            private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
        }
    }
}
