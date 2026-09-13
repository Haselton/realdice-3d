package com.haseltonmediagroup.frostline3d;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import java.nio.*;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class FrostSurface extends GLSurfaceView {
    public final FrostRenderer renderer;
    public FrostSurface(Context c){
        super(c);
        setEGLContextClientVersion(2);
        setPreserveEGLContextOnPause(true);
        renderer=new FrostRenderer();
        setRenderer(renderer);
        setRenderMode(RENDERMODE_CONTINUOUSLY);
    }

    public static class FrostRenderer implements Renderer {
        private int program,aPos,uMvp,uColor;
        private final float[] proj=new float[16],view=new float[16],vp=new float[16],model=new float[16],mvp=new float[16];
        private Mesh cube;
        private volatile float steer=0f, stance=0f;
        private volatile boolean jump=false;
        private float px=0f, py=2.2f, pz=0f, speed=9f, heading=0f, vy=0f;
        private long last=0L;

        public void setControls(float s,float p){steer=s;stance=p;}
        public void jump(){jump=true;}
        public void grind(){}
        public void cameraResetPulse(){}

        @Override public void onSurfaceCreated(GL10 gl,EGLConfig cfg){
            GLES20.glClearColor(0.12f,0.22f,0.31f,1f);
            GLES20.glEnable(GLES20.GL_DEPTH_TEST);
            program=createProgram(VS,FS);
            aPos=GLES20.glGetAttribLocation(program,"aPos");
            uMvp=GLES20.glGetUniformLocation(program,"uMVP");
            uColor=GLES20.glGetUniformLocation(program,"uColor");
            cube=new Mesh(makeCube());
        }

        @Override public void onSurfaceChanged(GL10 gl,int w,int h){
            GLES20.glViewport(0,0,w,h);
            Matrix.perspectiveM(proj,0,60f,w/(float)Math.max(1,h),0.1f,500f);
        }

        @Override public void onDrawFrame(GL10 gl){
            long now=System.nanoTime();
            float dt=last==0L?0.016f:Math.min(0.033f,(now-last)/1_000_000_000f);
            last=now;
            update(dt);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT|GLES20.GL_DEPTH_BUFFER_BIT);
            GLES20.glUseProgram(program);

            float fx=(float)Math.sin(heading), fz=-(float)Math.cos(heading);
            Matrix.setLookAtM(view,0,px-fx*7f,py+3.0f,pz-fz*7f,px+fx*8f,py+0.7f,pz+fz*8f,0,1,0);
            Matrix.multiplyMM(vp,0,proj,0,view,0);

            // snow slope
            drawCube(0,-2.5f,-70,45,1.5f,160,0.88f,0.94f,0.98f,8,0,0);
            // side banks / terrain variation
            drawCube(-34,0,-55,7,4,100,0.72f,0.82f,0.88f,12,0,-7);
            drawCube(34,0,-55,7,4,100,0.72f,0.82f,0.88f,-12,0,7);
            // rocks / trees simplified
            for(int i=0;i<12;i++){
                float z=-18f-i*12f;
                float x=(i%2==0?-1:1)*(10f+(i%4)*4f);
                drawCube(x,-0.8f,z,1.2f,2.2f,1.2f,0.22f,0.26f,0.29f,0,0,0);
                drawCube(-x*0.7f,0.8f,z-5f,0.45f,3.5f,0.45f,0.16f,0.11f,0.07f,0,0,0);
                drawCube(-x*0.7f,3.3f,z-5f,2.0f,2.5f,2.0f,0.05f,0.22f,0.16f,0,0,0);
            }

            // rider board + body
            drawCube(px,py-0.85f,pz,0.35f,0.08f,1.15f,0.06f,0.05f,0.09f,0,(float)Math.toDegrees(heading),-(float)Math.toDegrees(steer*0.35f));
            drawCube(px,py,pz,0.6f,0.9f,0.45f,0.03f,0.04f,0.06f,0,(float)Math.toDegrees(heading),-(float)Math.toDegrees(steer*0.25f));
            drawCube(px,py+1.15f,pz,0.28f,0.28f,0.28f,0.34f,0.18f,0.11f,0,(float)Math.toDegrees(heading),0);
        }

        private void update(float dt){
            float target=stance>0?14f+stance*8f:14f+stance*5f;
            speed += (target-speed)*Math.min(1f,dt*2f);
            speed=Math.max(6f,Math.min(23f,speed));
            heading += steer*(float)Math.toRadians(50f)*dt;
            float fx=(float)Math.sin(heading),fz=-(float)Math.cos(heading);
            px += fx*speed*dt;
            pz += fz*speed*dt;
            if(jump && py<=2.25f){vy=7f;} jump=false;
            if(py>2.2f || vy>0f){vy-=17f*dt;py+=vy*dt;if(py<2.2f){py=2.2f;vy=0f;}}
            if(pz<-135f){pz=0;px=0;heading=0;speed=9f;py=2.2f;}
        }

        private void drawCube(float x,float y,float z,float sx,float sy,float sz,float r,float g,float b,float rx,float ry,float rz){
            Matrix.setIdentityM(model,0);
            Matrix.translateM(model,0,x,y,z);
            Matrix.rotateM(model,0,ry,0,1,0);
            Matrix.rotateM(model,0,rz,0,0,1);
            Matrix.rotateM(model,0,rx,1,0,0);
            Matrix.scaleM(model,0,sx,sy,sz);
            Matrix.multiplyMM(mvp,0,vp,0,model,0);
            GLES20.glUniformMatrix4fv(uMvp,1,false,mvp,0);
            GLES20.glUniform4f(uColor,r,g,b,1f);
            cube.bind(aPos);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES,0,cube.count);
        }

        private static class Mesh{
            final FloatBuffer fb; final int count;
            Mesh(float[] data){
                count=data.length/3;
                fb=ByteBuffer.allocateDirect(data.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
                fb.put(data).position(0);
            }
            void bind(int pos){fb.position(0);GLES20.glEnableVertexAttribArray(pos);GLES20.glVertexAttribPointer(pos,3,GLES20.GL_FLOAT,false,12,fb);}
        }

        private static int createProgram(String vs,String fs){
            int v=compile(GLES20.GL_VERTEX_SHADER,vs), f=compile(GLES20.GL_FRAGMENT_SHADER,fs);
            int p=GLES20.glCreateProgram(); GLES20.glAttachShader(p,v); GLES20.glAttachShader(p,f); GLES20.glLinkProgram(p); return p;
        }
        private static int compile(int type,String src){int s=GLES20.glCreateShader(type);GLES20.glShaderSource(s,src);GLES20.glCompileShader(s);return s;}

        private static float[] makeCube(){
            return new float[]{
                -1,-1,-1, 1,-1,-1, 1,1,-1, -1,-1,-1, 1,1,-1, -1,1,-1,
                 1,-1, 1,-1,-1, 1,-1,1, 1, 1,-1, 1,-1,1, 1,1,1, 1,
                -1,-1, 1,-1,-1,-1,-1,1,-1, -1,-1, 1,-1,1,-1,-1,1, 1,
                 1,-1,-1, 1,-1, 1, 1,1, 1, 1,-1,-1, 1,1, 1, 1,1,-1,
                -1,1,-1, 1,1,-1, 1,1, 1, -1,1,-1, 1,1, 1,-1,1, 1,
                -1,-1, 1, 1,-1, 1, 1,-1,-1, -1,-1, 1, 1,-1,-1,-1,-1,-1
            };
        }

        private static final String VS="attribute vec3 aPos; uniform mat4 uMVP; void main(){gl_Position=uMVP*vec4(aPos,1.0);}";
        private static final String FS="precision mediump float; uniform vec4 uColor; void main(){gl_FragColor=uColor;}";
    }
}
