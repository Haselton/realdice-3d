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
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class Dice3DView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    private val diceRenderer = DiceRenderer()
    private var resultListener: ((Int, Int) -> Unit)? = null
    private var impactListener: ((Float) -> Unit)? = null

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

    fun setOnImpactListener(listener: (Float) -> Unit) {
        impactListener = listener
        diceRenderer.onImpact = { strength -> post { impactListener?.invoke(strength) } }
    }

    fun roll(force: Float = 1f) {
        queueEvent { diceRenderer.roll(force.coerceIn(0.7f, 3.2f)) }
    }

    private class DiceRenderer : Renderer {
        var onSettled: ((Int, Int) -> Unit)? = null
        var onImpact: ((Float) -> Unit)? = null

        private data class DieState(
            var value: Int = 1,
            var rolling: Boolean = false,
            var startNs: Long = 0L,
            var duration: Float = 2f,
            var startPx: Float = 0f,
            var startPz: Float = 0f,
            var targetPx: Float = 0f,
            var targetPz: Float = 0f,
            var px: Float = 0f,
            var pz: Float = 0f,
            var lift: Float = 0f,
            var rx: Float = 0f,
            var ry: Float = 0f,
            var rz: Float = 0f,
            var startRx: Float = 0f,
            var startRy: Float = 0f,
            var startRz: Float = 0f,
            var targetRx: Float = 0f,
            var targetRy: Float = 0f,
            var targetRz: Float = 0f,
            var spinX: Float = 0f,
            var spinY: Float = 0f,
            var spinZ: Float = 0f,
            var curve: Float = 0f,
            var phase: Float = 0f,
            var lastBounce: Int = -1
        )

        private val dice = arrayOf(
            DieState(px = -1.0f, pz = -1.2f),
            DieState(px = 1.0f, pz = -0.5f)
        )

        private val projection = FloatArray(16)
        private val view = FloatArray(16)
        private val model = FloatArray(16)
        private val mv = FloatArray(16)
        private val mvp = FloatArray(16)

        private var program = 0
        private var notified = true
        private var collisionTriggered = false
        private var rollForce = 1f

        private val rounded = buildRoundedCube(12, 0.24f)
        private val cubePositions = buffer(rounded.first)
        private val cubeNormals = buffer(rounded.second)
        private val cubeVertexCount = rounded.first.size / 3

        private val trayPositions = buffer(floatArrayOf(
            -4.55f,-0.66f,-5.0f,   4.55f,-0.66f,-5.0f,   4.55f,-0.66f,4.25f,
            -4.55f,-0.66f,-5.0f,   4.55f,-0.66f,4.25f,  -4.55f,-0.66f,4.25f
        ))
        private val trayNormals = buffer(FloatArray(18).also { a ->
            for (i in 0 until 6) a[i * 3 + 1] = 1f
        })

        override fun onSurfaceCreated(
            gl: javax.microedition.khronos.opengles.GL10?,
            config: javax.microedition.khronos.egl.EGLConfig?
        ) {
            GLES20.glClearColor(0.008f, 0.012f, 0.012f, 1f)
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glEnable(GLES20.GL_CULL_FACE)
            GLES20.glCullFace(GLES20.GL_BACK)
            program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        }

        override fun onSurfaceChanged(
            gl: javax.microedition.khronos.opengles.GL10?,
            w: Int,
            h: Int
        ) {
            val width = w.coerceAtLeast(1)
            val height = h.coerceAtLeast(1)
            GLES20.glViewport(0, 0, width, height)
            Matrix.perspectiveM(projection, 0, 42f, width.toFloat() / height.toFloat(), 1f, 50f)
            Matrix.setLookAtM(
                view, 0,
                0f, 7.9f, 12.2f,
                0f, -0.25f, -0.9f,
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
            rollForce = force
            notified = false
            collisionTriggered = false
            val now = System.nanoTime()

            val endX = floatArrayOf(
                Random.nextFloat() * 1.25f - 2.0f,
                Random.nextFloat() * 1.25f + 0.75f
            )
            val endZ = floatArrayOf(
                Random.nextFloat() * 1.6f - 3.35f,
                Random.nextFloat() * 1.75f - 1.7f
            )

            dice.forEachIndexed { index, d ->
                val targetValue = Random.nextInt(1, 7)
                d.value = targetValue
                d.startNs = now
                d.duration = (1.82f + force * 0.31f + index * 0.10f).coerceAtMost(2.70f)
                d.startPx = if (index == 0) -1.55f else 1.55f
                d.startPz = 3.75f + index * 0.25f
                d.targetPx = endX[index]
                d.targetPz = endZ[index]
                d.px = d.startPx
                d.pz = d.startPz
                d.startRx = d.rx
                d.startRy = d.ry
                d.startRz = d.rz
                val target = orientationFor(targetValue)
                d.targetRx = target[0]
                d.targetRy = target[1]
                d.targetRz = target[2] + Random.nextInt(-2, 3) * 90f
                d.spinX = Random.nextInt(1100, 1780) * force * if (index == 0) 1f else -1f
                d.spinY = Random.nextInt(900, 1580) * force * if (index == 0) -1f else 1f
                d.spinZ = Random.nextInt(650, 1260) * force
                d.curve = (Random.nextFloat() * 0.9f + 0.45f) * if (index == 0) 1f else -1f
                d.phase = index * 0.38f
                d.lastBounce = -1
                d.rolling = true
            }
        }

        private fun updateAnimation() {
            var anyRolling = false
            var avgT = 0f
            var count = 0

            dice.forEach { d ->
                if (!d.rolling) return@forEach
                anyRolling = true
                count++
                val elapsed = (System.nanoTime() - d.startNs) / 1_000_000_000f
                val t = (elapsed / d.duration).coerceIn(0f, 1f)
                avgT += t

                val travelEase = 1f - (1f - t) * (1f - t)
                val settleEase = 1f - (1f - t) * (1f - t) * (1f - t)

                d.px = lerp(d.startPx, d.targetPx, travelEase) +
                    sin(t * PI.toFloat()) * d.curve * (1f - 0.58f * t)
                d.pz = lerp(d.startPz, d.targetPz, travelEase) +
                    sin(t * PI.toFloat() * 1.30f + d.phase) * 0.50f * (1f - t)

                val bounceCycles = 4.25f
                val bouncePhase = t * bounceCycles
                val bounce = abs(sin(bouncePhase * PI.toFloat()))
                val envelope = (1f - t) * (1f - 0.28f * t)
                d.lift = (2.35f + 0.30f * rollForce) * envelope * bounce +
                    0.10f * sin(t * PI.toFloat())

                val bounceIndex = floor(bouncePhase).toInt()
                if (bounceIndex > d.lastBounce && bounceIndex > 0 && t < 0.96f) {
                    d.lastBounce = bounceIndex
                    onImpact?.invoke((0.90f - bounceIndex * 0.16f + rollForce * 0.05f).coerceIn(0.16f, 1f))
                }

                val spinEnvelope = (1f - settleEase) * (1f - 0.35f * t)
                d.rx = lerp(d.startRx, d.targetRx, settleEase) + d.spinX * spinEnvelope
                d.ry = lerp(d.startRy, d.targetRy, settleEase) + d.spinY * spinEnvelope
                d.rz = lerp(d.startRz, d.targetRz, settleEase) + d.spinZ * spinEnvelope

                if (t >= 1f) {
                    d.px = d.targetPx
                    d.pz = d.targetPz
                    d.lift = 0f
                    d.rx = d.targetRx
                    d.ry = d.targetRy
                    d.rz = d.targetRz
                    // Critical V5 fix: derive the score from the face that is actually uppermost.
                    d.value = topFaceForRotation(d.rx, d.ry, d.rz)
                    d.rolling = false
                    onImpact?.invoke(0.14f)
                }
            }

            if (count > 0) avgT /= count
            if (!collisionTriggered && anyRolling && avgT > 0.36f) {
                collisionTriggered = true
                onImpact?.invoke(0.50f)
            }

            if (!anyRolling && !notified) {
                notified = true
                onSettled?.invoke(dice[0].value, dice[1].value)
            }
        }

        private fun drawTray() {
            Matrix.setIdentityM(model, 0)
            updateMatrices()
            drawGeometry(trayPositions, trayNormals, 6, floatArrayOf(0.035f,0.125f,0.085f,1f), 0.38f)
            drawRim(-4.50f, -0.35f, -0.38f, 0.18f, 8.75f, 0.24f)
            drawRim( 4.50f, -0.35f, -0.38f, 0.18f, 8.75f, 0.24f)
            drawRim(0f, -0.35f, -4.90f, 9.0f, 0.18f, 0.24f)
            drawRim(0f, -0.35f,  4.15f, 9.0f, 0.18f, 0.24f)
        }

        private fun drawRim(x: Float, y: Float, z: Float, sx: Float, sz: Float, sy: Float) {
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, x, y, z)
            Matrix.scaleM(model, 0, sx, sy, sz)
            updateMatrices()
            drawGeometry(cubePositions, cubeNormals, cubeVertexCount, floatArrayOf(0.13f,0.085f,0.05f,1f), 0.22f)
        }

        private fun drawShadow(d: DieState) {
            val verts = circleOnPlane(0f, -0.645f, 0f, 0.62f + d.lift * 0.035f, 34)
            val normals = FloatArray(verts.size).also { a ->
                for (i in 0 until verts.size / 3) a[i * 3 + 1] = 1f
            }
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, d.px, 0f, d.pz)
            updateMatrices()
            val alpha = (0.28f - d.lift * 0.055f).coerceIn(0.045f, 0.28f)
            drawGeometry(buffer(verts), buffer(normals), verts.size / 3, floatArrayOf(0.002f,0.003f,0.003f,alpha), 0f, GLES20.GL_TRIANGLE_FAN)
        }

        private fun drawDie(d: DieState) {
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, d.px, d.lift, d.pz)
            Matrix.scaleM(model, 0, 0.56f, 0.56f, 0.56f)
            Matrix.rotateM(model, 0, d.rx, 1f, 0f, 0f)
            Matrix.rotateM(model, 0, d.ry, 0f, 1f, 0f)
            Matrix.rotateM(model, 0, d.rz, 0f, 0f, 1f)
            updateMatrices()
            drawGeometry(cubePositions, cubeNormals, cubeVertexCount, floatArrayOf(0.965f,0.95f,0.90f,1f), 0.92f)

            // Pip fans have face-dependent winding. Disable culling so every visible face keeps its pips.
            GLES20.glDisable(GLES20.GL_CULL_FACE)
            drawAllPips()
            GLES20.glEnable(GLES20.GL_CULL_FACE)
            GLES20.glCullFace(GLES20.GL_BACK)
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
            positions.position(0)
            normals.position(0)
            GLES20.glEnableVertexAttribArray(p)
            GLES20.glEnableVertexAttribArray(n)
            GLES20.glVertexAttribPointer(p, 3, GLES20.GL_FLOAT, false, 12, positions)
            GLES20.glVertexAttribPointer(n, 3, GLES20.GL_FLOAT, false, 12, normals)
            GLES20.glDrawArrays(mode, 0, count)
            GLES20.glDisableVertexAttribArray(p)
            GLES20.glDisableVertexAttribArray(n)
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
            val segments = 24
            val verts = FloatArray((segments + 2) * 3)
            val normals = FloatArray((segments + 2) * 3)
            val o = 1.009f

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
                verts[index*3] = xyz[0]; verts[index*3+1] = xyz[1]; verts[index*3+2] = xyz[2]
                normals[index*3] = nn[0]; normals[index*3+1] = nn[1]; normals[index*3+2] = nn[2]
            }

            point(0, u, v)
            for (i in 0..segments) {
                val a = 2.0 * PI * i / segments
                point(i + 1, u + radius * cos(a).toFloat(), v + radius * sin(a).toFloat())
            }
            drawGeometry(
                buffer(verts), buffer(normals), segments + 2,
                floatArrayOf(0.014f,0.015f,0.016f,1f), 0.10f,
                GLES20.GL_TRIANGLE_FAN
            )
        }

        private fun pipPattern(value: Int): List<Pair<Float, Float>> {
            val l = -0.46f; val c = 0f; val r = 0.46f; val t = 0.46f; val b = -0.46f
            return when (value) {
                1 -> listOf(c to c)
                2 -> listOf(l to t, r to b)
                3 -> listOf(l to t, c to c, r to b)
                4 -> listOf(l to t, r to t, l to b, r to b)
                5 -> listOf(l to t, r to t, c to c, l to b, r to b)
                else -> listOf(l to t, r to t, l to c, r to c, l to b, r to b)
            }
        }

        private fun orientationFor(value: Int): FloatArray = when (value) {
            1 -> floatArrayOf(0f, 0f, 0f)
            6 -> floatArrayOf(180f, 0f, 0f)
            2 -> floatArrayOf(-90f, 0f, 0f)
            5 -> floatArrayOf(90f, 0f, 0f)
            3 -> floatArrayOf(0f, 0f, 90f)
            else -> floatArrayOf(0f, 0f, -90f)
        }

        /** Return the pip value whose face normal points most strongly upward after the actual final rotation. */
        private fun topFaceForRotation(rx: Float, ry: Float, rz: Float): Int {
            val rotation = FloatArray(16)
            Matrix.setIdentityM(rotation, 0)
            Matrix.rotateM(rotation, 0, rx, 1f, 0f, 0f)
            Matrix.rotateM(rotation, 0, ry, 0f, 1f, 0f)
            Matrix.rotateM(rotation, 0, rz, 0f, 0f, 1f)

            val faces = arrayOf(
                1 to floatArrayOf(0f, 1f, 0f, 0f),
                6 to floatArrayOf(0f,-1f, 0f, 0f),
                2 to floatArrayOf(0f, 0f, 1f, 0f),
                5 to floatArrayOf(0f, 0f,-1f, 0f),
                3 to floatArrayOf(1f, 0f, 0f, 0f),
                4 to floatArrayOf(-1f,0f, 0f, 0f)
            )

            var bestValue = 1
            var bestY = -Float.MAX_VALUE
            for ((value, normal) in faces) {
                val out = FloatArray(4)
                Matrix.multiplyMV(out, 0, rotation, 0, normal, 0)
                if (out[1] > bestY) {
                    bestY = out[1]
                    bestValue = value
                }
            }
            return bestValue
        }

        private enum class Face { TOP, BOTTOM, FRONT, BACK, RIGHT, LEFT }

        companion object {
            private const val VERTEX_SHADER = """
                uniform mat4 uMVP;
                uniform mat4 uMV;
                attribute vec4 aPosition;
                attribute vec3 aNormal;
                varying vec3 vNormal;
                varying vec3 vPosition;
                void main() {
                    gl_Position = uMVP * aPosition;
                    vPosition = vec3(uMV * aPosition);
                    vNormal = normalize(mat3(uMV) * aNormal);
                }
            """

            private const val FRAGMENT_SHADER = """
                precision mediump float;
                uniform vec4 uColor;
                uniform float uLightStrength;
                varying vec3 vNormal;
                varying vec3 vPosition;
                void main() {
                    vec3 lightDir = normalize(vec3(-0.35, 0.82, 0.52));
                    float diffuse = max(dot(normalize(vNormal), lightDir), 0.0);
                    vec3 viewDir = normalize(-vPosition);
                    vec3 halfDir = normalize(lightDir + viewDir);
                    float spec = pow(max(dot(normalize(vNormal), halfDir), 0.0), 28.0);
                    float lighting = 0.54 + diffuse * uLightStrength;
                    vec3 rgb = uColor.rgb * lighting + vec3(spec * 0.16 * uLightStrength);
                    gl_FragColor = vec4(rgb, uColor.a);
                }
            """

            private fun createProgram(vertex: String, fragment: String): Int {
                fun compile(type: Int, source: String): Int {
                    val shader = GLES20.glCreateShader(type)
                    GLES20.glShaderSource(shader, source)
                    GLES20.glCompileShader(shader)
                    return shader
                }
                val program = GLES20.glCreateProgram()
                GLES20.glAttachShader(program, compile(GLES20.GL_VERTEX_SHADER, vertex))
                GLES20.glAttachShader(program, compile(GLES20.GL_FRAGMENT_SHADER, fragment))
                GLES20.glLinkProgram(program)
                return program
            }

            private fun buildRoundedCube(subdivisions: Int, radius: Float): Pair<FloatArray, FloatArray> {
                val positions = ArrayList<Float>()
                val normals = ArrayList<Float>()
                val inner = 1f - radius

                fun roundedPoint(x: Float, y: Float, z: Float): Pair<FloatArray, FloatArray> {
                    val qx = x.coerceIn(-inner, inner)
                    val qy = y.coerceIn(-inner, inner)
                    val qz = z.coerceIn(-inner, inner)
                    var dx = x - qx; var dy = y - qy; var dz = z - qz
                    var len = sqrt(dx*dx + dy*dy + dz*dz)
                    if (len < 0.00001f) {
                        val ax = abs(x); val ay = abs(y); val az = abs(z)
                        if (ax >= ay && ax >= az) dx = if (x >= 0f) 1f else -1f
                        else if (ay >= ax && ay >= az) dy = if (y >= 0f) 1f else -1f
                        else dz = if (z >= 0f) 1f else -1f
                        len = 1f
                    }
                    val nx = dx / len; val ny = dy / len; val nz = dz / len
                    return floatArrayOf(qx + nx*radius, qy + ny*radius, qz + nz*radius) to floatArrayOf(nx,ny,nz)
                }

                fun add(p: FloatArray, n: FloatArray) {
                    positions.add(p[0]); positions.add(p[1]); positions.add(p[2])
                    normals.add(n[0]); normals.add(n[1]); normals.add(n[2])
                }

                fun cross(a: FloatArray, b: FloatArray, c: FloatArray): FloatArray {
                    val ux=b[0]-a[0]; val uy=b[1]-a[1]; val uz=b[2]-a[2]
                    val vx=c[0]-a[0]; val vy=c[1]-a[1]; val vz=c[2]-a[2]
                    return floatArrayOf(uy*vz-uz*vy, uz*vx-ux*vz, ux*vy-uy*vx)
                }
                fun dot(a: FloatArray,b: FloatArray)=a[0]*b[0]+a[1]*b[1]+a[2]*b[2]
                fun tri(a: Pair<FloatArray,FloatArray>, b: Pair<FloatArray,FloatArray>, c: Pair<FloatArray,FloatArray>, expected: FloatArray) {
                    if (dot(cross(a.first,b.first,c.first), expected) >= 0f) {
                        add(a.first,a.second); add(b.first,b.second); add(c.first,c.second)
                    } else {
                        add(a.first,a.second); add(c.first,c.second); add(b.first,b.second)
                    }
                }

                data class FaceSpec(val axis:Int,val sign:Float,val expected:FloatArray)
                val specs = listOf(
                    FaceSpec(0,1f,floatArrayOf(1f,0f,0f)), FaceSpec(0,-1f,floatArrayOf(-1f,0f,0f)),
                    FaceSpec(1,1f,floatArrayOf(0f,1f,0f)), FaceSpec(1,-1f,floatArrayOf(0f,-1f,0f)),
                    FaceSpec(2,1f,floatArrayOf(0f,0f,1f)), FaceSpec(2,-1f,floatArrayOf(0f,0f,-1f))
                )
                fun raw(spec: FaceSpec,u:Float,v:Float): Pair<FloatArray,FloatArray> {
                    val p = when(spec.axis){0->floatArrayOf(spec.sign,u,v);1->floatArrayOf(u,spec.sign,v);else->floatArrayOf(u,v,spec.sign)}
                    return roundedPoint(p[0],p[1],p[2])
                }
                for (spec in specs) {
                    for (iy in 0 until subdivisions) {
                        val v0=-1f+2f*iy/subdivisions; val v1=-1f+2f*(iy+1)/subdivisions
                        for (ix in 0 until subdivisions) {
                            val u0=-1f+2f*ix/subdivisions; val u1=-1f+2f*(ix+1)/subdivisions
                            val p00=raw(spec,u0,v0); val p10=raw(spec,u1,v0); val p11=raw(spec,u1,v1); val p01=raw(spec,u0,v1)
                            tri(p00,p10,p11,spec.expected); tri(p00,p11,p01,spec.expected)
                        }
                    }
                }
                return positions.toFloatArray() to normals.toFloatArray()
            }

            private fun circleOnPlane(cx: Float, y: Float, cz: Float, radius: Float, segments: Int): FloatArray {
                val out = FloatArray((segments + 2) * 3)
                out[0]=cx; out[1]=y; out[2]=cz
                for (i in 0..segments) {
                    val a=2.0*PI*i/segments
                    val k=(i+1)*3
                    out[k]=(cx + cos(a).toFloat()*radius)
                    out[k+1]=y
                    out[k+2]=(cz + sin(a).toFloat()*radius)
                }
                return out
            }

            private fun buffer(values: FloatArray): FloatBuffer = ByteBuffer
                .allocateDirect(values.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply { put(values); position(0) }

            private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
        }
    }
}
