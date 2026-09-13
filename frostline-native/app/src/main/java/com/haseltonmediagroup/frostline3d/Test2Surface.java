package com.haseltonmediagroup.frostline3d;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class Test2Surface extends GLSurfaceView {
    public Test2Surface(Context c) {
        super(c);
        setEGLContextClientVersion(2);
        setRenderer(new Scene());
        setRenderMode(RENDERMODE_CONTINUOUSLY);
    }

    private static class Scene implements Renderer {
        private int program, pos, color;
        private FloatBuffer vertices;
        private float phase;
        private final float[] data = {
            -0.85f,-0.65f,  0.85f,-0.65f,  0.0f,0.70f,
            -0.65f,-0.45f,  0.65f,-0.45f,  0.0f,0.48f
        };

        @Override public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            GLES20.glClearColor(0.08f,0.16f,0.23f,1f);
            program = makeProgram(VS, FS);
            pos = GLES20.glGetAttribLocation(program,"aPos");
            color = GLES20.glGetUniformLocation(program,"uColor");
            vertices = ByteBuffer.allocateDirect(data.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            vertices.put(data).position(0);
        }

        @Override public void onSurfaceChanged(GL10 gl,int w,int h) { GLES20.glViewport(0,0,w,h); }

        @Override public void onDrawFrame(GL10 gl) {
            phase += 0.01f;
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glUseProgram(program);
            vertices.position(0);
            GLES20.glEnableVertexAttribArray(pos);
            GLES20.glVertexAttribPointer(pos,2,GLES20.GL_FLOAT,false,0,vertices);
            GLES20.glUniform4f(color,0.92f,0.96f,1f,1f);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES,0,3);
            GLES20.glUniform4f(color,0.45f,0.68f,0.82f,1f);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES,3,3);
        }

        private static int makeProgram(String vs,String fs) {
            int v=compile(GLES20.GL_VERTEX_SHADER,vs), f=compile(GLES20.GL_FRAGMENT_SHADER,fs);
            int p=GLES20.glCreateProgram(); GLES20.glAttachShader(p,v); GLES20.glAttachShader(p,f); GLES20.glLinkProgram(p); return p;
        }
        private static int compile(int type,String source) {
            int s=GLES20.glCreateShader(type); GLES20.glShaderSource(s,source); GLES20.glCompileShader(s); return s;
        }
        private static final String VS="attribute vec2 aPos; void main(){ gl_Position=vec4(aPos,0.0,1.0); }";
        private static final String FS="precision mediump float; uniform vec4 uColor; void main(){ gl_FragColor=uColor; }";
    }
}
