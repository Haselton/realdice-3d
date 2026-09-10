package com.haseltonmediagroup.realdice3d

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.AttributeSet
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.security.SecureRandom
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

class MultiDice3DView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    private val renderer3d = DiceRenderer()
    private var resultListener: ((List<Int>) -> Unit)? = null
    private var impactListener: ((Float) -> Unit)? = null

    init {
        setEGLContextClientVersion(2)
        setRenderer(renderer3d)
        renderMode = RENDERMODE_CONTINUOUSLY
        preserveEGLContextOnPause = true
    }

    fun setDiceCount(count: Int) {
        queueEvent { renderer3d.setDiceCount(count.coerceIn(1, 6)) }
    }

    fun setOnRollSettledListener(listener: (List<Int>) -> Unit) {
        resultListener = listener
        renderer3d.onSettled = { values -> post { resultListener?.invoke(values) } }
    }

    fun setOnImpactListener(listener: (Float) -> Unit) {
        impactListener = listener
        renderer3d.onImpact = { strength -> post { impactListener?.invoke(strength) } }
    }

    fun roll(force: Float = 1f) {
        queueEvent { renderer3d.roll(force.coerceIn(0.7f, 3.2f)) }
    }

    private class DiceRenderer : Renderer {
        var onSettled: ((List<Int>) -> Unit)? = null
        var onImpact: ((Float) -> Unit)? = null
        private val rng = SecureRandom()

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

        private val dice = MutableList(6) { DieState() }
        private var activeCount = 2
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
            -4.55f,-0.66f,-5.0f, 4.55f,-0.66f,-5.0f, 4.55f,-0.66f,4.25f,
            -4.55f,-0.66f,-5.0f, 4.55f,-0.66f,4.25f, -4.55f,-0.66f,4.25f
        ))
        private val trayNormals = buffer(FloatArray(18).also { a -> for (i in 0 until 6) a[i*3+1] = 1f })

        override fun onSurfaceCreated(gl: javax.microedition.khronos.opengles.GL10?, config: javax.microedition.khronos.egl.EGLConfig?) {
            GLES20.glClearColor(0.008f, 0.012f, 0.012f, 1f)
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glEnable(GLES20.GL_CULL_FACE)
            GLES20.glCullFace(GLES20.GL_BACK)
            program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            arrangeSettledDice()
        }

        override fun onSurfaceChanged(gl: javax.microedition.khronos.opengles.GL10?, w: Int, h: Int) {
            val width = w.coerceAtLeast(1); val height = h.coerceAtLeast(1)
            GLES20.glViewport(0, 0, width, height)
            Matrix.perspectiveM(projection, 0, 42f, width.toFloat()/height.toFloat(), 1f, 50f)
            Matrix.setLookAtM(view, 0, 0f, 8.1f, 12.9f, 0f, -0.2f, -0.9f, 0f, 1f, 0f)
        }

        override fun onDrawFrame(gl: javax.microedition.khronos.opengles.GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            updateAnimation(); drawTray()
            for (i in 0 until activeCount) drawShadow(dice[i])
            for (i in 0 until activeCount) drawDie(dice[i])
        }

        fun setDiceCount(count: Int) {
            activeCount = count
            dice.forEach { it.rolling = false }
            notified = true
            arrangeSettledDice()
        }

        private fun arrangeSettledDice() {
            val positions = settledPositions(activeCount)
            for (i in 0 until activeCount) {
                dice[i].px = positions[i].first
                dice[i].pz = positions[i].second
                dice[i].lift = 0f
                dice[i].rx = 0f; dice[i].ry = 0f; dice[i].rz = 0f
                dice[i].value = 1
            }
        }

        fun roll(force: Float) {
            rollForce = force; notified = false; collisionTriggered = false
            val now = System.nanoTime()
            val positions = settledPositions(activeCount)
            for (i in 0 until activeCount) {
                val d = dice[i]
                val targetValue = rng.nextInt(6) + 1
                d.value = targetValue
                d.startNs = now
                d.duration = (1.80f + force*0.30f + i*0.07f).coerceAtMost(2.75f)
                val spread = if (activeCount <= 2) 1.55f else 2.7f
                d.startPx = -spread + (2f*spread*i/(activeCount-1).coerceAtLeast(1))
                d.startPz = 3.5f + (i%2)*0.28f
                d.targetPx = positions[i].first + rand(-0.18f,0.18f)
                d.targetPz = positions[i].second + rand(-0.18f,0.18f)
                d.px=d.startPx; d.pz=d.startPz
                d.startRx=d.rx; d.startRy=d.ry; d.startRz=d.rz
                val target=orientationFor(targetValue)
                d.targetRx=target[0]; d.targetRy=target[1]; d.targetRz=target[2] + (rng.nextInt(5)-2)*90f
                val sign = if (i%2==0) 1f else -1f
                d.spinX=(1050+rng.nextInt(700))*force*sign
                d.spinY=(850+rng.nextInt(700))*force*-sign
                d.spinZ=(620+rng.nextInt(650))*force
                d.curve=rand(0.32f,0.90f)*sign
                d.phase=i*0.31f; d.lastBounce=-1; d.rolling=true
            }
        }

        private fun settledPositions(count: Int): List<Pair<Float,Float>> {
            val templates = mapOf(
                1 to listOf(0f to -1.2f),
                2 to listOf(-1.1f to -1.55f, 1.1f to -0.75f),
                3 to listOf(-1.7f to -1.65f, 0f to -0.65f, 1.7f to -1.65f),
                4 to listOf(-1.65f to -2.15f, 1.65f to -2.15f, -1.65f to -0.25f, 1.65f to -0.25f),
                5 to listOf(-1.8f to -2.15f, 0f to -2.15f, 1.8f to -2.15f, -0.95f to -0.25f, 0.95f to -0.25f),
                6 to listOf(-1.9f to -2.25f, 0f to -2.25f, 1.9f to -2.25f, -1.9f to -0.15f, 0f to -0.15f, 1.9f to -0.15f)
            )
            return templates[count]!!
        }

        private fun updateAnimation() {
            var any=false; var avg=0f; var count=0
            for (i in 0 until activeCount) {
                val d=dice[i]; if (!d.rolling) continue
                any=true; count++
                val t=(((System.nanoTime()-d.startNs)/1_000_000_000f)/d.duration).coerceIn(0f,1f); avg+=t
                val travel=1f-(1f-t)*(1f-t); val settle=1f-(1f-t)*(1f-t)*(1f-t)
                d.px=lerp(d.startPx,d.targetPx,travel)+sin(t*PI.toFloat())*d.curve*(1f-0.58f*t)
                d.pz=lerp(d.startPz,d.targetPz,travel)+sin(t*PI.toFloat()*1.3f+d.phase)*0.42f*(1f-t)
                val bp=t*4.25f; val bounce=abs(sin(bp*PI.toFloat())); val env=(1f-t)*(1f-0.28f*t)
                d.lift=(2.15f+0.28f*rollForce)*env*bounce+0.08f*sin(t*PI.toFloat())
                val bi=floor(bp).toInt()
                if (bi>d.lastBounce && bi>0 && t<0.96f) { d.lastBounce=bi; onImpact?.invoke((0.88f-bi*0.15f+rollForce*0.05f).coerceIn(0.16f,1f)) }
                val spinEnv=(1f-settle)*(1f-0.35f*t)
                d.rx=lerp(d.startRx,d.targetRx,settle)+d.spinX*spinEnv
                d.ry=lerp(d.startRy,d.targetRy,settle)+d.spinY*spinEnv
                d.rz=lerp(d.startRz,d.targetRz,settle)+d.spinZ*spinEnv
                if (t>=1f) {
                    d.px=d.targetPx; d.pz=d.targetPz; d.lift=0f; d.rx=d.targetRx; d.ry=d.targetRy; d.rz=d.targetRz
                    d.value=topFaceForRotation(d.rx,d.ry,d.rz); d.rolling=false; onImpact?.invoke(0.14f)
                }
            }
            if (count>0) avg/=count
            if (!collisionTriggered && any && avg>0.36f) { collisionTriggered=true; onImpact?.invoke(0.50f) }
            if (!any && !notified) { notified=true; onSettled?.invoke((0 until activeCount).map { dice[it].value }) }
        }

        private fun drawTray() {
            Matrix.setIdentityM(model,0); updateMatrices(); drawGeometry(trayPositions,trayNormals,6,floatArrayOf(0.035f,0.125f,0.085f,1f),0.38f)
            drawRim(-4.50f,-0.35f,-0.38f,0.18f,8.75f,0.24f); drawRim(4.50f,-0.35f,-0.38f,0.18f,8.75f,0.24f)
            drawRim(0f,-0.35f,-4.90f,9.0f,0.18f,0.24f); drawRim(0f,-0.35f,4.15f,9.0f,0.18f,0.24f)
        }
        private fun drawRim(x:Float,y:Float,z:Float,sx:Float,sz:Float,sy:Float) { Matrix.setIdentityM(model,0); Matrix.translateM(model,0,x,y,z); Matrix.scaleM(model,0,sx,sy,sz); updateMatrices(); drawGeometry(cubePositions,cubeNormals,cubeVertexCount,floatArrayOf(0.13f,0.085f,0.05f,1f),0.22f) }
        private fun drawShadow(d:DieState) { val verts=circleOnPlane(0f,-0.645f,0f,0.60f+d.lift*0.03f,30); val norms=FloatArray(verts.size).also{a->for(i in 0 until verts.size/3)a[i*3+1]=1f}; Matrix.setIdentityM(model,0); Matrix.translateM(model,0,d.px,0f,d.pz); updateMatrices(); val alpha=(0.28f-d.lift*0.055f).coerceIn(0.045f,0.28f); drawGeometry(buffer(verts),buffer(norms),verts.size/3,floatArrayOf(0.002f,0.003f,0.003f,alpha),0f,GLES20.GL_TRIANGLE_FAN) }
        private fun drawDie(d:DieState) { Matrix.setIdentityM(model,0); Matrix.translateM(model,0,d.px,d.lift,d.pz); val s=if(activeCount>=5)0.47f else if(activeCount>=3)0.52f else 0.56f; Matrix.scaleM(model,0,s,s,s); Matrix.rotateM(model,0,d.rx,1f,0f,0f); Matrix.rotateM(model,0,d.ry,0f,1f,0f); Matrix.rotateM(model,0,d.rz,0f,0f,1f); updateMatrices(); drawGeometry(cubePositions,cubeNormals,cubeVertexCount,floatArrayOf(0.965f,0.95f,0.90f,1f),0.92f); GLES20.glDisable(GLES20.GL_CULL_FACE); drawAllPips(); GLES20.glEnable(GLES20.GL_CULL_FACE); GLES20.glCullFace(GLES20.GL_BACK) }
        private fun updateMatrices(){ Matrix.multiplyMM(mv,0,view,0,model,0); Matrix.multiplyMM(mvp,0,projection,0,mv,0) }
        private fun drawGeometry(pbuf:FloatBuffer,nbuf:FloatBuffer,count:Int,color:FloatArray,light:Float,mode:Int=GLES20.GL_TRIANGLES){ GLES20.glUseProgram(program); val p=GLES20.glGetAttribLocation(program,"aPosition"); val n=GLES20.glGetAttribLocation(program,"aNormal"); GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program,"uMVP"),1,false,mvp,0); GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program,"uMV"),1,false,mv,0); GLES20.glUniform4fv(GLES20.glGetUniformLocation(program,"uColor"),1,color,0); GLES20.glUniform1f(GLES20.glGetUniformLocation(program,"uLightStrength"),light); pbuf.position(0); nbuf.position(0); GLES20.glEnableVertexAttribArray(p); GLES20.glEnableVertexAttribArray(n); GLES20.glVertexAttribPointer(p,3,GLES20.GL_FLOAT,false,12,pbuf); GLES20.glVertexAttribPointer(n,3,GLES20.GL_FLOAT,false,12,nbuf); GLES20.glDrawArrays(mode,0,count); GLES20.glDisableVertexAttribArray(p); GLES20.glDisableVertexAttribArray(n) }
        private enum class Face{TOP,BOTTOM,FRONT,BACK,RIGHT,LEFT}
        private fun drawAllPips(){ drawFacePips(1,Face.TOP);drawFacePips(6,Face.BOTTOM);drawFacePips(2,Face.FRONT);drawFacePips(5,Face.BACK);drawFacePips(3,Face.RIGHT);drawFacePips(4,Face.LEFT) }
        private fun drawFacePips(v:Int,f:Face){ pipPattern(v).forEach{(u,w)->drawPip(f,u,w)} }
        private fun drawPip(face:Face,u:Float,v:Float){ val radius=0.118f;val seg=24;val verts=FloatArray((seg+2)*3);val norms=FloatArray((seg+2)*3);val o=1.009f;fun point(i:Int,a:Float,b:Float){val xyz=when(face){Face.TOP->floatArrayOf(a,o,b);Face.BOTTOM->floatArrayOf(a,-o,-b);Face.FRONT->floatArrayOf(a,b,o);Face.BACK->floatArrayOf(-a,b,-o);Face.RIGHT->floatArrayOf(o,b,-a);Face.LEFT->floatArrayOf(-o,b,a)};val nn=when(face){Face.TOP->floatArrayOf(0f,1f,0f);Face.BOTTOM->floatArrayOf(0f,-1f,0f);Face.FRONT->floatArrayOf(0f,0f,1f);Face.BACK->floatArrayOf(0f,0f,-1f);Face.RIGHT->floatArrayOf(1f,0f,0f);Face.LEFT->floatArrayOf(-1f,0f,0f)};verts[i*3]=xyz[0];verts[i*3+1]=xyz[1];verts[i*3+2]=xyz[2];norms[i*3]=nn[0];norms[i*3+1]=nn[1];norms[i*3+2]=nn[2]};point(0,u,v);for(i in 0..seg){val a=2.0*PI*i/seg;point(i+1,u+radius*cos(a).toFloat(),v+radius*sin(a).toFloat())};drawGeometry(buffer(verts),buffer(norms),seg+2,floatArrayOf(0.014f,0.015f,0.016f,1f),0.10f,GLES20.GL_TRIANGLE_FAN) }
        private fun pipPattern(value:Int):List<Pair<Float,Float>>{val l=-0.46f;val c=0f;val r=0.46f;val t=0.46f;val b=-0.46f;return when(value){1->listOf(c to c);2->listOf(l to t,r to b);3->listOf(l to t,c to c,r to b);4->listOf(l to t,r to t,l to b,r to b);5->listOf(l to t,r to t,c to c,l to b,r to b);else->listOf(l to t,r to t,l to c,r to c,l to b,r to b)}}
        private fun orientationFor(v:Int)=when(v){1->floatArrayOf(0f,0f,0f);6->floatArrayOf(180f,0f,0f);2->floatArrayOf(-90f,0f,0f);5->floatArrayOf(90f,0f,0f);3->floatArrayOf(0f,0f,90f);else->floatArrayOf(0f,0f,-90f)}
        private fun topFaceForRotation(rx:Float,ry:Float,rz:Float):Int{val rot=FloatArray(16);Matrix.setIdentityM(rot,0);Matrix.rotateM(rot,0,rx,1f,0f,0f);Matrix.rotateM(rot,0,ry,0f,1f,0f);Matrix.rotateM(rot,0,rz,0f,0f,1f);val faces=arrayOf(1 to floatArrayOf(0f,1f,0f,0f),6 to floatArrayOf(0f,-1f,0f,0f),2 to floatArrayOf(0f,0f,1f,0f),5 to floatArrayOf(0f,0f,-1f,0f),3 to floatArrayOf(1f,0f,0f,0f),4 to floatArrayOf(-1f,0f,0f,0f));var best=1;var bestY=-Float.MAX_VALUE;for((v,n) in faces){val out=FloatArray(4);Matrix.multiplyMV(out,0,rot,0,n,0);if(out[1]>bestY){bestY=out[1];best=v}};return best}
        private fun rand(min:Float,max:Float)=min+rng.nextFloat()*(max-min)

        companion object {
            private const val VERTEX_SHADER="""uniform mat4 uMVP; uniform mat4 uMV; attribute vec4 aPosition; attribute vec3 aNormal; varying vec3 vNormal; varying vec3 vPosition; void main(){gl_Position=uMVP*aPosition;vPosition=vec3(uMV*aPosition);vNormal=normalize(mat3(uMV)*aNormal);}"""
            private const val FRAGMENT_SHADER="""precision mediump float; uniform vec4 uColor; uniform float uLightStrength; varying vec3 vNormal; varying vec3 vPosition; void main(){vec3 lightDir=normalize(vec3(-0.35,0.82,0.52));float diffuse=max(dot(normalize(vNormal),lightDir),0.0);vec3 viewDir=normalize(-vPosition);vec3 halfDir=normalize(lightDir+viewDir);float spec=pow(max(dot(normalize(vNormal),halfDir),0.0),28.0);float lighting=0.54+diffuse*uLightStrength;vec3 rgb=uColor.rgb*lighting+vec3(spec*0.16*uLightStrength);gl_FragColor=vec4(rgb,uColor.a);}"""
            private fun createProgram(v:String,f:String):Int{fun c(t:Int,s:String):Int{val sh=GLES20.glCreateShader(t);GLES20.glShaderSource(sh,s);GLES20.glCompileShader(sh);return sh};val p=GLES20.glCreateProgram();GLES20.glAttachShader(p,c(GLES20.GL_VERTEX_SHADER,v));GLES20.glAttachShader(p,c(GLES20.GL_FRAGMENT_SHADER,f));GLES20.glLinkProgram(p);return p}
            private fun buildRoundedCube(sub:Int,radius:Float):Pair<FloatArray,FloatArray>{val pos=ArrayList<Float>();val nor=ArrayList<Float>();val inner=1f-radius;fun rp(x:Float,y:Float,z:Float):Pair<FloatArray,FloatArray>{val qx=x.coerceIn(-inner,inner);val qy=y.coerceIn(-inner,inner);val qz=z.coerceIn(-inner,inner);var dx=x-qx;var dy=y-qy;var dz=z-qz;var len=sqrt(dx*dx+dy*dy+dz*dz);if(len<0.00001f){val ax=abs(x);val ay=abs(y);val az=abs(z);if(ax>=ay&&ax>=az)dx=if(x>=0)1f else -1f else if(ay>=ax&&ay>=az)dy=if(y>=0)1f else -1f else dz=if(z>=0)1f else -1f;len=1f};val nx=dx/len;val ny=dy/len;val nz=dz/len;return floatArrayOf(qx+nx*radius,qy+ny*radius,qz+nz*radius) to floatArrayOf(nx,ny,nz)};fun add(p:FloatArray,n:FloatArray){pos.add(p[0]);pos.add(p[1]);pos.add(p[2]);nor.add(n[0]);nor.add(n[1]);nor.add(n[2])};fun cross(a:FloatArray,b:FloatArray,c:FloatArray)=floatArrayOf((b[1]-a[1])*(c[2]-a[2])-(b[2]-a[2])*(c[1]-a[1]),(b[2]-a[2])*(c[0]-a[0])-(b[0]-a[0])*(c[2]-a[2]),(b[0]-a[0])*(c[1]-a[1])-(b[1]-a[1])*(c[0]-a[0]));fun dot(a:FloatArray,b:FloatArray)=a[0]*b[0]+a[1]*b[1]+a[2]*b[2];fun tri(a:Pair<FloatArray,FloatArray>,b:Pair<FloatArray,FloatArray>,c:Pair<FloatArray,FloatArray>,e:FloatArray){if(dot(cross(a.first,b.first,c.first),e)>=0){add(a.first,a.second);add(b.first,b.second);add(c.first,c.second)}else{add(a.first,a.second);add(c.first,c.second);add(b.first,b.second)}};data class S(val axis:Int,val sign:Float,val e:FloatArray);val specs=listOf(S(0,1f,floatArrayOf(1f,0f,0f)),S(0,-1f,floatArrayOf(-1f,0f,0f)),S(1,1f,floatArrayOf(0f,1f,0f)),S(1,-1f,floatArrayOf(0f,-1f,0f)),S(2,1f,floatArrayOf(0f,0f,1f)),S(2,-1f,floatArrayOf(0f,0f,-1f)));fun raw(s:S,u:Float,v:Float):Pair<FloatArray,FloatArray>{val p=when(s.axis){0->floatArrayOf(s.sign,u,v);1->floatArrayOf(u,s.sign,v);else->floatArrayOf(u,v,s.sign)};return rp(p[0],p[1],p[2])};for(s in specs)for(iy in 0 until sub){val v0=-1f+2f*iy/sub;val v1=-1f+2f*(iy+1)/sub;for(ix in 0 until sub){val u0=-1f+2f*ix/sub;val u1=-1f+2f*(ix+1)/sub;val p00=raw(s,u0,v0);val p10=raw(s,u1,v0);val p11=raw(s,u1,v1);val p01=raw(s,u0,v1);tri(p00,p10,p11,s.e);tri(p00,p11,p01,s.e)}};return pos.toFloatArray() to nor.toFloatArray()}
            private fun circleOnPlane(cx:Float,y:Float,cz:Float,r:Float,seg:Int):FloatArray{val out=FloatArray((seg+2)*3);out[0]=cx;out[1]=y;out[2]=cz;for(i in 0..seg){val a=2.0*PI*i/seg;val k=(i+1)*3;out[k]=cx+cos(a).toFloat()*r;out[k+1]=y;out[k+2]=cz+sin(a).toFloat()*r};return out}
            private fun buffer(v:FloatArray):FloatBuffer=ByteBuffer.allocateDirect(v.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply{put(v);position(0)}
            private fun lerp(a:Float,b:Float,t:Float)=a+(b-a)*t
        }
    }
}
