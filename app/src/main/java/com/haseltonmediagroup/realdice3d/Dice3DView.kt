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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class Dice3DView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    private val diceRenderer = DiceRenderer()
    private var resultListener: ((Int, Int) -> Unit)? = null

    init {
        setEGLContextClientVersion(2)
        setRenderer(diceRenderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        preserveEGLContextOnPause = true
    }

    fun setOnRollSettledListener(listener: (Int, Int) -> Unit) {
        resultListener = listener
        diceRenderer.onSettled = { a, b -> post { resultListener?.invoke(a, b) } }
    }

    fun roll(force: Float = 1f) {
        queueEvent { diceRenderer.roll(force.coerceIn(0.7f, 3.2f)) }
    }

    private class DiceRenderer : Renderer {
        var onSettled: ((Int, Int) -> Unit)? = null

        private data class DieState(
            var value: Int = 1,
            var rolling: Boolean = false,
            var startNs: Long = 0L,
            var duration: Float = 1.8f,
            var startPx: Float = 0f,
            var startPz: Float = 0f,
            var targetPx: Float = 0f,
            var targetPz: Float = 0f,
            var px: Float = 0f,
            var pz: Float = 0f,
            var rx: Float = 0f,
            var ry: Float = 0f,
            var rz: Float = 0f,
            var startRx: Float = 0f,
            var startRy: Float = 0f,
            var startRz: Float = 0f,
            var targetRx: Float = 0f,
            var targetRy: Float = 0f,
            var targetRz: Float = 0f,
            var spinX: Float = 900f,
            var spinY: Float = 760f,
            var spinZ: Float = 620f,
            var lift: Float = 0f,
            var phase: Float = 0f
        )

        private val dice = arrayOf(
            DieState(px = -1.15f, pz = 0.25f, rx = 0f),
            DieState(px = 1.15f, pz = -0.15f, rx = 90f)
        )

        private val projection = FloatArray(16)
        private val view = FloatArray(16)
        private val model = FloatArray(16)
        private val mv = FloatArray(16)
        private val mvp = FloatArray(16)

        private var program = 0
        private var notified = true

        private val rounded = buildRoundedCube(7, 0.24f)
        private val cubePositions = buffer(rounded.first)
        private val cubeNormals = buffer(rounded.second)
        private val cubeVertexCount = rounded.first.size / 3

        private val trayPositions = buffer(floatArrayOf(
            -4.6f,-0.69f,-4.8f,   4.6f,-0.69f,-4.8f,   4.6f,-0.69f, 4.0f,
            -4.6f,-0.69f,-4.8f,   4.6f,-0.69f, 4.0f,  -4.6f,-0.69f, 4.0f
        ))
        private val trayNormals = buffer(FloatArray(18).also { a ->
            for (i in 0 until 6) { a[i*3+1] = 1f }
        })

        override fun onSurfaceCreated(gl: javax.microedition.khronos.opengles.GL10?, config: javax.microedition.khronos.egl.EGLConfig?) {
            GLES20.glClearColor(0.012f, 0.018f, 0.018f, 1f)
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glEnable(GLES20.GL_CULL_FACE)
            GLES20.glCullFace(GLES20.GL_BACK)
            program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        }

        override fun onSurfaceChanged(gl: javax.microedition.khronos.opengles.GL10?, w: Int, h: Int) {
            val width = w.coerceAtLeast(1)
            val height = h.coerceAtLeast(1)
            GLES20.glViewport(0, 0, width, height)
            val ratio = width.toFloat() / height.toFloat()
            Matrix.perspectiveM(projection, 0, 39f, ratio, 1f, 40f)
            Matrix.setLookAtM(view, 0,
                0f, 6.7f, 9.2f,
                0f, -0.15f, -0.25f,
                0f, 1f, 0f
            )
        }

        override fun onDrawFrame(gl: javax.microedition.khronos.opengles.GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            updateAnimation()
            drawTray()
            dice.forEach { drawShadow(it) }
            dice.forEach { drawDie(it) }
        }

        fun roll(force: Float) {
            notified = false
            val now = System.nanoTime()
            val leftTarget = Random.nextFloat() * 1.3f - 1.9f
            val rightTarget = Random.nextFloat() * 1.3f + 0.6f
            val targets = floatArrayOf(leftTarget, rightTarget)

            dice.forEachIndexed { index, d ->
                d.value = Random.nextInt(1, 7)
                d.startNs = now
                d.duration = (1.55f + force * 0.28f + index * 0.08f).coerceAtMost(2.45f)
                d.startPx = if (index == 0) -2.0f else 2.0f
                d.startPz = -3.35f - index * 0.45f
                d.targetPx = targets[index]
                d.targetPz = Random.nextFloat() * 2.2f - 0.55f
                d.px = d.startPx
                d.pz = d.startPz
                d.startRx = d.rx
                d.startRy = d.ry
                d.startRz = d.rz
                val target = orientationFor(d.value)
                d.targetRx = target[0]
                d.targetRy = target[1]
                d.targetRz = target[2] + Random.nextInt(-3, 4) * 90f
                d.spinX = Random.nextInt(920, 1540) * force * if (index == 0) 1f else -1f
                d.spinY = Random.nextInt(760, 1420) * force * if (index == 0) -1f else 1f
                d.spinZ = Random.nextInt(520, 1050) * force
                d.phase = index * 0.55f
                d.rolling = true
            }
        }

        private fun updateAnimation() {
            var anyRolling = false
            dice.forEach { d ->
                if (!d.rolling) return@forEach
                anyRolling = true
                val elapsed = (System.nanoTime() - d.startNs) / 1_000_000_000f
                val t = (elapsed / d.duration).coerceIn(0f, 1f)
                val ease = 1f - (1f - t) * (1f - t) * (1f - t)
                val damping = exp(-3.8f * t)

                d.px = lerp(d.startPx, d.targetPx, ease) + sin(t * PI.toFloat() * 2.1f + d.phase) * 0.32f * (1f - t)
                d.pz = lerp(d.startPz, d.targetPz, ease)

                val bounce = abs(sin((t * 4.7f + d.phase) * PI.toFloat()))
                d.lift = 2.45f * (1f - t) * bounce + 0.22f * sin(t * PI.toFloat())

                d.rx = lerp(d.startRx, d.targetRx, ease) + d.spinX * (1f - ease) * damping
                d.ry = lerp(d.startRy, d.targetRy, ease) + d.spinY * (1f - ease) * damping
                d.rz = lerp(d.startRz, d.targetRz, ease) + d.spinZ * (1f - ease) * damping

                if (t >= 1f) {
                    d.px = d.targetPx
                    d.pz = d.targetPz
                    d.lift = 0f
                    d.rx = d.targetRx
                    d.ry = d.targetRy
                    d.rz = d.targetRz
                    d.rolling = false
                }
            }

            if (!anyRolling && !notified) {
                notified = true
                onSettled?.invoke(dice[0].value, dice[1].value)
            }
        }

        private fun drawTray() {
            Matrix.setIdentityM(model, 0)
            updateMatrices()
            drawGeometry(trayPositions, trayNormals, 6, floatArrayOf(0.045f, 0.16f, 0.115f, 1f), 0.42f)

            drawRim(-4.55f, -0.38f, -0.38f, 0.22f, 8.3f, 0.24f)
            drawRim( 4.55f, -0.38f, -0.38f, 0.22f, 8.3f, 0.24f)
            drawRim( 0f, -0.38f, -4.72f, 9.2f, 0.22f, 0.24f)
            drawRim( 0f, -0.38f,  3.92f, 9.2f, 0.22f, 0.24f)
        }

        private fun drawRim(x: Float, y: Float, z: Float, sx: Float, sz: Float, sy: Float) {
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, x, y, z)
            Matrix.scaleM(model, 0, sx, sy, sz)
            updateMatrices()
            drawGeometry(cubePositions, cubeNormals, cubeVertexCount, floatArrayOf(0.12f, 0.085f, 0.055f, 1f), 0.25f)
        }

        private fun drawShadow(d: DieState) {
            val verts = circleOnPlane(0f, -0.675f, 0f, 0.78f + d.lift * 0.07f, 36)
            val normals = FloatArray(verts.size).also { a ->
                for (i in 0 until verts.size / 3) a[i*3+1] = 1f
            }
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, d.px, 0f, d.pz)
            updateMatrices()
            val alpha = (0.30f - d.lift * 0.07f).coerceIn(0.08f, 0.30f)
            drawGeometry(buffer(verts), buffer(normals), verts.size / 3, floatArrayOf(0.005f,0.006f,0.006f,alpha), 0f, GLES20.GL_TRIANGLE_FAN)
        }

        private fun drawDie(d: DieState) {
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, d.px, d.lift, d.pz)
            Matrix.scaleM(model, 0, 0.72f, 0.72f, 0.72f)
            Matrix.rotateM(model, 0, d.rx, 1f, 0f, 0f)
            Matrix.rotateM(model, 0, d.ry, 0f, 1f, 0f)
            Matrix.rotateM(model, 0, d.rz, 0f, 0f, 1f)
            updateMatrices()
            drawGeometry(cubePositions, cubeNormals, cubeVertexCount, floatArrayOf(0.94f,0.925f,0.865f,1f), 0.88f)
            drawAllPips()
        }

        private fun updateMatrices() {
            Matrix.multiplyMM(mv, 0, view, 0, model, 0)
            Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
        }

        private fun drawGeometry(
            positions: FloatBuffer,
            normals: FloatBuffer,
            count: Int,
            color: FloatArray,
            lightStrength: Float,
            mode: Int = GLES20.GL_TRIANGLES
        ) {
            GLES20.glUseProgram(program)
            val p = GLES20.glGetAttribLocation(program, "aPosition")
            val n = GLES20.glGetAttribLocation(program, "aNormal")
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uMVP"), 1, false, mvp, 0)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uMV"), 1, false, mv, 0)
            GLES20.glUniform4fv(GLES20.glGetUniformLocation(program, "uColor"), 1, color, 0)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uLightStrength"), lightStrength)
            positions.position(0); normals.position(0)
            GLES20.glEnableVertexAttribArray(p); GLES20.glEnableVertexAttribArray(n)
            GLES20.glVertexAttribPointer(p, 3, GLES20.GL_FLOAT, false, 12, positions)
            GLES20.glVertexAttribPointer(n, 3, GLES20.GL_FLOAT, false, 12, normals)
            GLES20.glDrawArrays(mode, 0, count)
            GLES20.glDisableVertexAttribArray(p); GLES20.glDisableVertexAttribArray(n)
        }

        private fun drawAllPips() {
            drawFacePips(1, Face.TOP)
            drawFacePips(6, Face.BOTTOM)
            drawFacePips(2, Face.FRONT)
            drawFacePips(5, Face.BACK)
            drawFacePips(3, Face.RIGHT)
            drawFacePips(4, Face.LEFT)
        }

        private fun drawFacePips(value: Int, face: Face) {
            pipPattern(value).forEach { (u, v) -> drawPip(face, u, v) }
        }

        private fun drawPip(face: Face, u: Float, v: Float) {
            val radius = 0.118f
            val segments = 18
            val verts = FloatArray((segments + 2) * 3)
            val normals = FloatArray((segments + 2) * 3)
            val o = 1.007f

            fun point(index: Int, a: Float, b: Float) {
                val xyz = when (face) {
                    Face.TOP -> floatArrayOf(a, o, b)
                    Face.BOTTOM -> floatArrayOf(a, -o, -b)
                    Face.FRONT -> floatArrayOf(a, b, o)
                    Face.BACK -> floatArrayOf(-a, b, -o)
                    Face.RIGHT -> floatArrayOf(o, b, -a)
                    Face.LEFT -> floatArrayOf(-o, b, a)
                }
                val nn = when (face) {
                    Face.TOP -> floatArrayOf(0f,1f,0f)
                    Face.BOTTOM -> floatArrayOf(0f,-1f,0f)
                    Face.FRONT -> floatArrayOf(0f,0f,1f)
                    Face.BACK -> floatArrayOf(0f,0f,-1f)
                    Face.RIGHT -> floatArrayOf(1f,0f,0f)
                    Face.LEFT -> floatArrayOf(-1f,0f,0f)
                }
                verts[index*3]=xyz[0]; verts[index*3+1]=xyz[1]; verts[index*3+2]=xyz[2]
                normals[index*3]=nn[0]; normals[index*3+1]=nn[1]; normals[index*3+2]=nn[2]
            }

            point(0,u,v)
            for (i in 0..segments) {
                val a = 2.0 * PI * i / segments
                point(i+1, u + radius*cos(a).toFloat(), v + radius*sin(a).toFloat())
            }
            drawGeometry(buffer(verts), buffer(normals), segments+2, floatArrayOf(0.035f,0.038f,0.04f,1f), 0.15f, GLES20.GL_TRIANGLE_FAN)
        }

        private fun pipPattern(value: Int): List<Pair<Float,Float>> {
            val l=-0.46f; val c=0f; val r=0.46f; val t=0.46f; val b=-0.46f
            return when(value) {
                1 -> listOf(c to c)
                2 -> listOf(l to t, r to b)
                3 -> listOf(l to t, c to c, r to b)
                4 -> listOf(l to t, r to t, l to b, r to b)
                5 -> listOf(l to t, r to t, c to c, l to b, r to b)
                else -> listOf(l to t, r to t, l to c, r to c, l to b, r to b)
            }
        }

        private fun orientationFor(value: Int): FloatArray = when(value) {
            1 -> floatArrayOf(0f,0f,0f)
            6 -> floatArrayOf(180f,0f,0f)
            2 -> floatArrayOf(-90f,0f,0f)
            5 -> floatArrayOf(90f,0f,0f)
            3 -> floatArrayOf(0f,0f,90f)
            else -> floatArrayOf(0f,0f,-90f)
        }

        private enum class Face { TOP, BOTTOM, FRONT, BACK, RIGHT, LEFT }

        companion object {
            private const val VERTEX_SHADER = """
                uniform mat4 uMVP;
                uniform mat4 uMV;
                attribute vec4 aPosition;
                attribute vec3 aNormal;
                varying float vLight;
                void main() {
                    gl_Position = uMVP * aPosition;
                    vec3 normal = normalize(mat3(uMV) * aNormal);
                    vec3 lightDir = normalize(vec3(-0.45, 0.8, 0.65));
                    vLight = max(dot(normal, lightDir), 0.0);
                }
            """

            private const val FRAGMENT_SHADER = """
                precision mediump float;
                uniform vec4 uColor;
                uniform float uLightStrength;
                varying float vLight;
                void main() {
                    float lighting = 1.0 - uLightStrength + uLightStrength * (0.35 + 0.65 * vLight);
                    gl_FragColor = vec4(uColor.rgb * lighting, uColor.a);
                }
            """

            private fun buildRoundedCube(steps: Int, radius: Float): Pair<FloatArray,FloatArray> {
                val p = ArrayList<Float>()
                val n = ArrayList<Float>()
                val inner = 1f - radius

                fun emit(x: Float,y: Float,z: Float) {
                    val cx = x.coerceIn(-inner, inner)
                    val cy = y.coerceIn(-inner, inner)
                    val cz = z.coerceIn(-inner, inner)
                    var dx=x-cx; var dy=y-cy; var dz=z-cz
                    val len = sqrt(dx*dx+dy*dy+dz*dz).coerceAtLeast(0.0001f)
                    dx/=len; dy/=len; dz/=len
                    p.add(cx+dx*radius); p.add(cy+dy*radius); p.add(cz+dz*radius)
                    n.add(dx); n.add(dy); n.add(dz)
                }

                fun face(axis: Int, sign: Float) {
                    for (i in 0 until steps) for (j in 0 until steps) {
                        val u0=-1f+2f*i/steps; val u1=-1f+2f*(i+1)/steps
                        val v0=-1f+2f*j/steps; val v1=-1f+2f*(j+1)/steps
                        fun e(u:Float,v:Float) {
                            when(axis) {
                                0 -> emit(sign,u,v)
                                1 -> emit(u,sign,v)
                                else -> emit(u,v,sign)
                            }
                        }
                        if (sign > 0f) {
                            e(u0,v0); e(u1,v0); e(u1,v1); e(u0,v0); e(u1,v1); e(u0,v1)
                        } else {
                            e(u0,v0); e(u1,v1); e(u1,v0); e(u0,v0); e(u0,v1); e(u1,v1)
                        }
                    }
                }
                face(0,1f); face(0,-1f); face(1,1f); face(1,-1f); face(2,1f); face(2,-1f)
                return p.toFloatArray() to n.toFloatArray()
            }

            private fun circleOnPlane(cx:Float, y:Float, cz:Float, r:Float, segments:Int):FloatArray {
                val a = FloatArray((segments+2)*3)
                a[0]=cx; a[1]=y; a[2]=cz
                for(i in 0..segments) {
                    val ang=2.0*PI*i/segments
                    a[(i+1)*3]=cx+r*cos(ang).toFloat()
                    a[(i+1)*3+1]=y
                    a[(i+1)*3+2]=cz+r*sin(ang).toFloat()
                }
                return a
            }

            private fun createProgram(vertex:String, fragment:String):Int {
                fun shader(type:Int, source:String):Int {
                    val id=GLES20.glCreateShader(type)
                    GLES20.glShaderSource(id,source)
                    GLES20.glCompileShader(id)
                    return id
                }
                val p=GLES20.glCreateProgram()
                GLES20.glAttachShader(p,shader(GLES20.GL_VERTEX_SHADER,vertex))
                GLES20.glAttachShader(p,shader(GLES20.GL_FRAGMENT_SHADER,fragment))
                GLES20.glLinkProgram(p)
                return p
            }

            private fun buffer(values:FloatArray):FloatBuffer = ByteBuffer
                .allocateDirect(values.size*4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply { put(values); position(0) }

            private fun lerp(a:Float,b:Float,t:Float)=a+(b-a)*t
        }
    }
}
