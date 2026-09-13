package com.haseltonmediagroup.frostline3d;

import android.content.Context;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import java.nio.*;
import java.util.*;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class FrostSurface extends GLSurfaceView {
    public final FrostRenderer renderer;
    public FrostSurface(Context c){ super(c); setEGLContextClientVersion(3); renderer=new FrostRenderer(); setRenderer(renderer); setRenderMode(RENDERMODE_CONTINUOUSLY); }

    public static class FrostRenderer implements Renderer {
        private int program,aPos,aNormal,uMvp,uModel,uColor,uLight;
        private final float[] proj=new float[16], view=new float[16], vp=new float[16], model=new float[16], mvp=new float[16];
        private Mesh cube,sphere,terrain;
        private volatile float steer=0, stance=0;
        private volatile boolean jumpRequest=false;
        private volatile float grindPulse=0, resetPulse=0;
        private float px=0,pz=-4,py=73, heading=0,speed=19,vy=0,lean=0;
        private long last=0;
        private final Random rng=new Random(122193);
        private final ArrayList<Obstacle> obs=new ArrayList<>();
        private int width=1920,height=1080;

        static class Mesh{
            int vbo, count;
            Mesh(float[] data){
                count=data.length/6;
                FloatBuffer fb=ByteBuffer.allocateDirect(data.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer(); fb.put(data).position(0);
                int[] id=new int[1]; GLES30.glGenBuffers(1,id,0); vbo=id[0]; GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,vbo); GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER,data.length*4,fb,GLES30.GL_STATIC_DRAW);
            }
            void bind(int pos,int normal){ GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,vbo); GLES30.glEnableVertexAttribArray(pos); GLES30.glVertexAttribPointer(pos,3,GLES30.GL_FLOAT,false,24,0); GLES30.glEnableVertexAttribArray(normal); GLES30.glVertexAttribPointer(normal,3,GLES30.GL_FLOAT,false,24,12); }
        }
        static class Obstacle{float x,z,s;int type;Obstacle(float X,float Z,float S,int T){x=X;z=Z;s=S;type=T;}}

        public void setControls(float s,float p){steer=s;stance=p;}
        public void jump(){jumpRequest=true;}
        public void grind(){grindPulse=1f;}
        public void cameraResetPulse(){resetPulse=1f;}

        @Override public void onSurfaceCreated(GL10 gl,EGLConfig cfg){
            GLES30.glClearColor(.10f,.18f,.25f,1); GLES30.glEnable(GLES30.GL_DEPTH_TEST); GLES30.glEnable(GLES30.GL_CULL_FACE); GLES30.glCullFace(GLES30.GL_BACK);
            program=link(VS,FS); aPos=GLES30.glGetAttribLocation(program,"aPos");aNormal=GLES30.glGetAttribLocation(program,"aNormal");uMvp=GLES30.glGetUniformLocation(program,"uMVP");uModel=GLES30.glGetUniformLocation(program,"uModel");uColor=GLES30.glGetUniformLocation(program,"uColor");uLight=GLES30.glGetUniformLocation(program,"uLight");
            cube=new Mesh(makeCube()); sphere=new Mesh(makeSphere(14,10)); terrain=new Mesh(makeTerrain()); seedObstacles(); py=terrainHeight(px,pz)+1.2f;
        }
        @Override public void onSurfaceChanged(GL10 gl,int w,int h){width=w;height=h;GLES30.glViewport(0,0,w,h);Matrix.perspectiveM(proj,0,64f,w/(float)Math.max(1,h),.15f,700f);}
        @Override public void onDrawFrame(GL10 gl){
            long now=System.nanoTime();float dt=last==0?.016f:Math.min(.033f,(now-last)/1e9f);last=now; update(dt);
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT|GLES30.GL_DEPTH_BUFFER_BIT); GLES30.glUseProgram(program); GLES30.glUniform3f(uLight,-.35f,.8f,.42f);
            float fx=(float)Math.sin(heading), fz=-(float)Math.cos(heading); float rx=(float)Math.cos(heading), rz=(float)Math.sin(heading);
            float camX=px-fx*7.6f+rx*1.0f*steer, camZ=pz-fz*7.6f+rz*1.0f*steer, camY=py+3.1f;
            Matrix.setLookAtM(view,0,camX,camY,camZ,px+fx*8,py+1.0f,pz+fz*8,0,1,0); Matrix.multiplyMM(vp,0,proj,0,view,0);
            drawMesh(terrain,0,0,0,1,1,1,0,0,0,.91f,.95f,.98f);
            drawBackdrop(); drawObstacles(); drawRider();
        }

        private void update(float dt){
            float t=Math.max(0,Math.min(1,-pz/1200f));
            float slopeAccel=8.0f+7.5f*t;
            float target=stance>0?25+stance*17:25+stance*9;
            speed+=(target-speed)*Math.min(1,dt*1.5f); speed+=slopeAccel*dt*.18f;
            if(stance<0)speed-=(-stance)*17f*dt; speed=Math.max(9,Math.min(43,speed));
            float turn=(float)Math.toRadians(20+48*Math.min(1,speed/34f))*steer; heading+=turn*dt;
            // Edge carving follows board heading. Small residual slip is preserved for snowboard feel.
            float fx=(float)Math.sin(heading),fz=-(float)Math.cos(heading); px+=fx*speed*dt; pz+=fz*speed*dt;
            float ground=terrainHeight(px,pz)+1.0f;
            if(jumpRequest && py<=ground+.18f){vy=7.8f;jumpRequest=false;} else jumpRequest=false;
            if(py>ground+.05f||vy>0){vy-=18.2f*dt;py+=vy*dt;if(py<ground){py=ground;vy=0;}}else py=ground;
            lean+=( -steer*.52f-lean)*Math.min(1,dt*6f); grindPulse=Math.max(0,grindPulse-dt*2);resetPulse=Math.max(0,resetPulse-dt*2);
            if(pz<-1190){pz=-4;px=0;heading=0;speed=19;py=terrainHeight(0,-4)+1.0f;}
        }

        private float terrainHeight(float x,float z){float t=Math.max(0,Math.min(1,-z/1200f));return 72-126*t+7.2f*(float)Math.sin(t*12.8+x*.024)+3.0f*(float)Math.sin(t*28-x*.05)+.0026f*x*x+1.6f*(float)Math.sin(t*56+x*.07);}

        private void seedObstacles(){obs.clear();for(int i=0;i<105;i++){float z=-35-i*10.8f-rng.nextFloat()*6;float x=-57+rng.nextFloat()*114;if(Math.abs(x)<6&&rng.nextFloat()<.55)x+=(x>=0?1:-1)*10;float r=rng.nextFloat();int type=r<.56?0:r<.83?1:2;obs.add(new Obstacle(x,z,.7f+rng.nextFloat()*.65f,type));}obs.add(new Obstacle(-8,-620,1.35f,3));}

        private void drawBackdrop(){
            drawMesh(sphere,0,125,-330,115,120,80,0,0,0,.76f,.83f,.88f);
            drawMesh(sphere,-95,82,-300,70,75,55,0,0,0,.70f,.78f,.83f);
            drawMesh(sphere,100,75,-305,65,68,50,0,0,0,.72f,.80f,.85f);
        }
        private void drawObstacles(){
            for(Obstacle o:obs){if(o.z>pz+18||o.z<pz-190)continue;float y=terrainHeight(o.x,o.z);
                if(o.type==0){ // conifer
                    drawMesh(cube,o.x,y+1.2f,o.z,.18f,1.2f,.18f,0,0,0,.24f,.15f,.08f);
                    drawMesh(sphere,o.x,y+2.2f,o.z,1.05f*o.s,1.8f*o.s,1.05f*o.s,0,0,0,.04f,.22f,.17f);
                }else if(o.type==1){drawMesh(sphere,o.x,y+.45f,o.z,1.0f*o.s,.62f*o.s,.8f*o.s,0,0,0,.26f,.29f,.31f);
                }else if(o.type==2){drawMesh(cube,o.x,y+.035f,o.z,2.2f*o.s,.025f,2.0f*o.s,0,0,0,.18f,.60f,.76f);
                }else{ // bear encounter
                    float bx=o.x; if(pz<-540&&pz>-700)bx=-8+Math.min(18,(-pz-540)*.11f);
                    drawMesh(sphere,bx,y+.75f,o.z,1.15f,.72f,.70f,0,0,0,.20f,.105f,.055f);
                    drawMesh(sphere,bx,y+1.0f,o.z-.75f,.48f,.46f,.48f,0,0,0,.16f,.075f,.035f);
                }
            }
        }
        private void drawRider(){
            float deg=(float)Math.toDegrees(heading), roll=(float)Math.toDegrees(lean);
            // board
            drawMesh(cube,px,py-.77f,pz,.24f,.035f,1.05f,0,deg,roll,.055f,.045f,.075f);
            // board color blocks
            drawMesh(cube,px,py-.725f,pz-.35f,.25f,.012f,.18f,0,deg,roll,.47f,.13f,.72f);
            drawMesh(cube,px,py-.72f,pz+.05f,.25f,.012f,.16f,0,deg,roll,.02f,.65f,.79f);
            // cargo legs
            drawMesh(sphere,px-.18f,py-.30f,pz,.20f,.55f,.20f,8,deg,roll,.27f,.30f,.24f);
            drawMesh(sphere,px+.18f,py-.30f,pz,.20f,.55f,.20f,-8,deg,roll,.27f,.30f,.24f);
            // boots
            drawMesh(cube,px-.19f,py-.69f,pz-.08f,.25f,.14f,.34f,0,deg,roll,.25f,.08f,.36f);
            drawMesh(cube,px+.19f,py-.69f,pz+.08f,.25f,.14f,.34f,0,deg,roll,.25f,.08f,.36f);
            // puffer torso
            drawMesh(sphere,px,py+.33f,pz,.50f,.60f,.42f,0,deg,roll,.025f,.035f,.05f);
            // graffiti accents
            drawMesh(cube,px-.36f,py+.42f,pz-.31f,.14f,.13f,.025f,0,deg,roll,.53f,.15f,.76f);
            drawMesh(cube,px+.28f,py+.22f,pz-.33f,.16f,.12f,.025f,0,deg,roll,.02f,.66f,.80f);
            // arms
            drawMesh(sphere,px-.49f,py+.30f,pz,.14f,.46f,.14f,0,deg,roll+24,.03f,.04f,.055f);
            drawMesh(sphere,px+.49f,py+.30f,pz,.14f,.46f,.14f,0,deg,roll-24,.03f,.04f,.055f);
            // head and cap
            drawMesh(sphere,px,py+1.03f,pz,.23f,.27f,.23f,0,deg,roll,.38f,.20f,.12f);
            drawMesh(sphere,px,py+1.22f,pz,.27f,.12f,.27f,0,deg,roll,.015f,.02f,.026f);
            // gold pendant impression
            drawMesh(sphere,px,py+.70f,pz-.38f,.09f,.11f,.04f,0,deg,roll,.90f,.61f,.10f);
        }

        private void drawMesh(Mesh mesh,float x,float y,float z,float sx,float sy,float sz,float rx,float ry,float rz,float r,float g,float b){
            Matrix.setIdentityM(model,0);Matrix.translateM(model,0,x,y,z);Matrix.rotateM(model,0,ry,0,1,0);Matrix.rotateM(model,0,rz,0,0,1);Matrix.rotateM(model,0,rx,1,0,0);Matrix.scaleM(model,0,sx,sy,sz);Matrix.multiplyMM(mvp,0,vp,0,model,0);
            GLES30.glUniformMatrix4fv(uMvp,1,false,mvp,0);GLES30.glUniformMatrix4fv(uModel,1,false,model,0);GLES30.glUniform4f(uColor,r,g,b,1);mesh.bind(aPos,aNormal);GLES30.glDrawArrays(GLES30.GL_TRIANGLES,0,mesh.count);
        }

        private float[] makeTerrain(){ArrayList<Float> a=new ArrayList<>();int rows=241,cols=41;float dz=1200f/(rows-1),dx=150f/(cols-1);for(int iz=0;iz<rows-1;iz++){for(int ix=0;ix<cols-1;ix++){float x0=-75+ix*dx,x1=x0+dx,z0=-iz*dz,z1=z0-dz;float[] A={x0,terrainHeight(x0,z0),z0},B={x1,terrainHeight(x1,z0),z0},C={x0,terrainHeight(x0,z1),z1},D={x1,terrainHeight(x1,z1),z1};tri(a,A,C,B);tri(a,B,C,D);}}float[] out=new float[a.size()];for(int i=0;i<out.length;i++)out[i]=a.get(i);return out;}
        private static void tri(ArrayList<Float>a,float[]p,float[]q,float[]r){float ux=q[0]-p[0],uy=q[1]-p[1],uz=q[2]-p[2],vx=r[0]-p[0],vy=r[1]-p[1],vz=r[2]-p[2];float nx=uy*vz-uz*vy,ny=uz*vx-ux*vz,nz=ux*vy-uy*vx;float l=(float)Math.sqrt(nx*nx+ny*ny+nz*nz);if(l<.0001)l=1;nx/=l;ny/=l;nz/=l;for(float[]v:new float[][]{p,q,r}){a.add(v[0]);a.add(v[1]);a.add(v[2]);a.add(nx);a.add(ny);a.add(nz);}}
        private static float[] makeCube(){float[][]p={{-1,-1,-1},{1,-1,-1},{1,1,-1},{-1,1,-1},{-1,-1,1},{1,-1,1},{1,1,1},{-1,1,1}};int[][]f={{0,1,2,3},{5,4,7,6},{4,0,3,7},{1,5,6,2},{3,2,6,7},{4,5,1,0}};float[][]n={{0,0,-1},{0,0,1},{-1,0,0},{1,0,0},{0,1,0},{0,-1,0}};float[]o=new float[36*6];int k=0;for(int j=0;j<6;j++){int[]v=f[j];for(int id:new int[]{v[0],v[1],v[2],v[0],v[2],v[3]}){o[k++]=p[id][0];o[k++]=p[id][1];o[k++]=p[id][2];o[k++]=n[j][0];o[k++]=n[j][1];o[k++]=n[j][2];}}return o;}
        private static float[] makeSphere(int seg,int rings){ArrayList<Float>a=new ArrayList<>();for(int y=0;y<rings;y++){double v0=Math.PI*y/rings-Math.PI/2,v1=Math.PI*(y+1)/rings-Math.PI/2;for(int x=0;x<seg;x++){double u0=2*Math.PI*x/seg,u1=2*Math.PI*(x+1)/seg;float[]p00=sph(u0,v0),p10=sph(u1,v0),p01=sph(u0,v1),p11=sph(u1,v1);sTri(a,p00,p01,p10);sTri(a,p10,p01,p11);}}float[]o=new float[a.size()];for(int i=0;i<o.length;i++)o[i]=a.get(i);return o;}
        private static float[] sph(double u,double v){return new float[]{(float)(Math.cos(v)*Math.cos(u)),(float)Math.sin(v),(float)(Math.cos(v)*Math.sin(u))};}
        private static void sTri(ArrayList<Float>a,float[]...vs){for(float[]v:vs){a.add(v[0]);a.add(v[1]);a.add(v[2]);a.add(v[0]);a.add(v[1]);a.add(v[2]);}}
        private static int link(String vs,String fs){int v=shader(GLES30.GL_VERTEX_SHADER,vs),f=shader(GLES30.GL_FRAGMENT_SHADER,fs),p=GLES30.glCreateProgram();GLES30.glAttachShader(p,v);GLES30.glAttachShader(p,f);GLES30.glLinkProgram(p);return p;}
        private static int shader(int type,String src){int s=GLES30.glCreateShader(type);GLES30.glShaderSource(s,src);GLES30.glCompileShader(s);return s;}
        private static final String VS="#version 300 es\nprecision mediump float;in vec3 aPos;in vec3 aNormal;uniform mat4 uMVP;uniform mat4 uModel;out vec3 n;void main(){gl_Position=uMVP*vec4(aPos,1.0);n=normalize(mat3(uModel)*aNormal);}";
        private static final String FS="#version 300 es\nprecision mediump float;in vec3 n;uniform vec4 uColor;uniform vec3 uLight;out vec4 frag;void main(){float d=max(.0,dot(normalize(n),normalize(uLight)));float l=.38+.62*d;frag=vec4(uColor.rgb*l,uColor.a);}";
    }
}
