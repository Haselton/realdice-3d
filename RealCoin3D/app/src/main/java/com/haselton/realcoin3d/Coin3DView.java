package com.haselton.realcoin3d;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.view.MotionEvent;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Random;

public final class Coin3DView extends GLSurfaceView {
    public interface Listener { void onSettled(boolean heads); void onImpact(float strength); }
    private final CoinRenderer renderer;
    private Listener listener;
    private float downX, downY; private long downMs;

    public Coin3DView(Context context) {
        super(context);
        setEGLContextClientVersion(2);
        setPreserveEGLContextOnPause(true);
        renderer = new CoinRenderer(context);
        renderer.callback = new CoinRenderer.Callback() {
            public void impact(float strength){ post(() -> { if(listener!=null) listener.onImpact(strength); }); }
            public void settled(boolean heads){ post(() -> { if(listener!=null) listener.onSettled(heads); }); }
        };
        setRenderer(renderer);
        setRenderMode(RENDERMODE_CONTINUOUSLY);
    }
    public void setListener(Listener l){ listener=l; }
    public void setCoin(CoinDefinition coin){ queueEvent(() -> renderer.setCoin(coin)); }
    public CoinDefinition getCoin(){ return renderer.coin; }
    public boolean isAirborne(){ return renderer.airborne; }
    public void flipButton(){ queueEvent(() -> renderer.flip(1.05f,(float)((Math.random()-.5)*.35))); }
    public void flip(float power,float lateral){ queueEvent(() -> renderer.flip(power,lateral)); }

    @Override public boolean onTouchEvent(MotionEvent e){
        switch(e.getActionMasked()){
            case MotionEvent.ACTION_DOWN: downX=e.getX(); downY=e.getY(); downMs=System.currentTimeMillis(); return true;
            case MotionEvent.ACTION_UP:
                long ms=Math.max(16,System.currentTimeMillis()-downMs);
                float dy=downY-e.getY(), dx=e.getX()-downX;
                float density=getResources().getDisplayMetrics().density;
                if(dy>18*density){
                    float speed=dy/ms;
                    float strength=Math.max(.65f,Math.min(1.65f,.55f+speed*1.95f));
                    float lateral=Math.max(-.8f,Math.min(.8f,dx/ms*.95f));
                    flip(strength,lateral); performClick();
                } else if(Math.abs(dx)<20*density && Math.abs(dy)<20*density) { flipButton(); performClick(); }
                return true;
        }
        return true;
    }
    @Override public boolean performClick(){ super.performClick(); return true; }

    private static final class CoinRenderer implements Renderer {
        interface Callback { void impact(float strength); void settled(boolean heads); }
        private final Context context; private final Random rng=new Random(); Callback callback;
        volatile CoinDefinition coin; volatile boolean airborne=false;
        private int program,aPos,aNormal,uMvp,uModel,uBase,uMetal,uRough;
        private FloatBuffer coinVB,floorVB; private int coinVertexCount;
        private final float[] proj=new float[16],view=new float[16],model=new float[16],mv=new float[16],mvp=new float[16];
        private long lastNs; private float px=0,py=.115f,pz=0,vx=0,vy=0,vz=0; private final Quat q=new Quat(); private final Vec3 omega=new Vec3();
        private final float radius=1f, halfT=.10f; private int lowMotionFrames=0; private boolean impactArmed=true; private float cameraLift=0f;
        CoinRenderer(Context c){context=c.getApplicationContext();q.setFromAxisAngle(1,0,0,(float)-Math.PI/2f);}

        @Override public void onSurfaceCreated(javax.microedition.khronos.opengles.GL10 gl,javax.microedition.khronos.egl.EGLConfig config){
            GLES20.glClearColor(.015f,.018f,.025f,1f);GLES20.glEnable(GLES20.GL_DEPTH_TEST);GLES20.glEnable(GLES20.GL_CULL_FACE);GLES20.glEnable(GLES20.GL_BLEND);GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA,GLES20.GL_ONE_MINUS_SRC_ALPHA);
            program=link(VS,FS);aPos=GLES20.glGetAttribLocation(program,"aPos");aNormal=GLES20.glGetAttribLocation(program,"aNormal");uMvp=GLES20.glGetUniformLocation(program,"uMvp");uModel=GLES20.glGetUniformLocation(program,"uModel");uBase=GLES20.glGetUniformLocation(program,"uBase");uMetal=GLES20.glGetUniformLocation(program,"uMetal");uRough=GLES20.glGetUniformLocation(program,"uRough");floorVB=makeFloor();
        }
        @Override public void onSurfaceChanged(javax.microedition.khronos.opengles.GL10 gl,int w,int h){GLES20.glViewport(0,0,w,h);Matrix.perspectiveM(proj,0,29f,w/(float)Math.max(1,h),.1f,60f);updateCamera();}
        @Override public void onDrawFrame(javax.microedition.khronos.opengles.GL10 gl){GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT|GLES20.GL_DEPTH_BUFFER_BIT);step();updateCamera();drawFloor();drawCoin();}
        private void updateCamera(){float target=Math.max(0f,py-1.1f)*.22f;cameraLift+=(target-cameraLift)*.06f;Matrix.setLookAtM(view,0,0f,3.15f+cameraLift,8.6f,0f,1.05f+cameraLift*.55f,0f,0f,1f,0f);}
        void setCoin(CoinDefinition c){coin=c;if(coinVB==null)coinVB=loadObjOrFallback("quarter_high.obj");py=supportHeight();px=pz=0;vx=vy=vz=0;q.setFromAxisAngle(1,0,0,(float)-Math.PI/2f);omega.set(0,0,0);airborne=false;lowMotionFrames=0;}
        void flip(float strength,float lateral){if(airborne||coin==null)return;airborne=true;lowMotionFrames=0;impactArmed=true;px=pz=0;vx=lateral*1.45f;vz=(rng.nextFloat()-.5f)*.42f;vy=7.8f+Math.max(.55f,Math.min(1.65f,strength))*3.0f;float spin=15f+rng.nextFloat()*8f;omega.set((rng.nextBoolean()?1:-1)*spin,(rng.nextFloat()-.5f)*5.5f,lateral*6f+(rng.nextFloat()-.5f)*2.2f);lastNs=System.nanoTime();}
        private void step(){if(!airborne){lastNs=System.nanoTime();return;}long now=System.nanoTime();float dt=Math.min(.022f,Math.max(.001f,(now-lastNs)/1_000_000_000f));lastNs=now;int sub=3;float sdt=dt/sub;for(int k=0;k<sub;k++){vy-=9.81f*sdt;px+=vx*sdt;py+=vy*sdt;pz+=vz*sdt;q.integrate(omega,sdt);float support=supportHeight();if(py<support){float in=-vy;py=support;if(in>.22f){float strength=Math.min(1f,in/8.5f);if(impactArmed&&callback!=null)callback.impact(strength);impactArmed=false;vy=Math.max(.02f,in*(.23f+.055f*strength));vx*=.74f;vz*=.74f;omega.scale(.76f);omega.x+=(rng.nextFloat()-.5f)*1.9f*strength;omega.z+=(rng.nextFloat()-.5f)*1.9f*strength;}else{vy=0;vx*=.84f;vz*=.84f;Vec3 n=q.rotate(new Vec3(0,0,1));float sign=n.y>=0?1f:-1f;Vec3 torque=n.cross(new Vec3(0,sign,0)).scale(10.5f*sdt);omega.add(torque);omega.scale(.922f);}}else if(py>support+.10f)impactArmed=true;float edge=2.05f;if(px<-edge){px=-edge;vx=Math.abs(vx)*.36f;}if(px>edge){px=edge;vx=-Math.abs(vx)*.36f;}}float speed=(float)Math.sqrt(vx*vx+vy*vy+vz*vz),spin=omega.length();if(py<=supportHeight()+.012f&&speed<.075f&&spin<.13f)lowMotionFrames++;else lowMotionFrames=0;if(lowMotionFrames>20){Vec3 n=q.rotate(new Vec3(0,0,1));boolean heads=n.y>=0;q.setFromAxisAngle(1,0,0,heads?(float)-Math.PI/2f:(float)Math.PI/2f);py=halfT;vx=vy=vz=0;omega.set(0,0,0);airborne=false;if(callback!=null)callback.settled(heads);}}
        private float supportHeight(){Vec3 n=q.rotate(new Vec3(0,0,1));float ay=Math.min(1f,Math.abs(n.y));return ay*halfT+(float)Math.sqrt(Math.max(0,1-ay*ay))*radius;}
        private void drawFloor(){GLES20.glUseProgram(program);Matrix.setIdentityM(model,0);Matrix.multiplyMM(mv,0,view,0,model,0);Matrix.multiplyMM(mvp,0,proj,0,mv,0);GLES20.glUniformMatrix4fv(uMvp,1,false,mvp,0);GLES20.glUniformMatrix4fv(uModel,1,false,model,0);GLES20.glUniform3f(uBase,.055f,.065f,.082f);GLES20.glUniform1f(uMetal,0f);GLES20.glUniform1f(uRough,.92f);floorVB.position(0);GLES20.glVertexAttribPointer(aPos,3,GLES20.GL_FLOAT,false,24,floorVB);GLES20.glEnableVertexAttribArray(aPos);floorVB.position(3);GLES20.glVertexAttribPointer(aNormal,3,GLES20.GL_FLOAT,false,24,floorVB);GLES20.glEnableVertexAttribArray(aNormal);GLES20.glDrawArrays(GLES20.GL_TRIANGLES,0,6);}
        private void drawCoin(){if(coin==null||coinVB==null)return;GLES20.glUseProgram(program);Matrix.setIdentityM(model,0);Matrix.translateM(model,0,px,py,pz);float[] rot=new float[16];q.toMatrix(rot);float[] tmp=new float[16];Matrix.multiplyMM(tmp,0,model,0,rot,0);System.arraycopy(tmp,0,model,0,16);Matrix.multiplyMM(mv,0,view,0,model,0);Matrix.multiplyMM(mvp,0,proj,0,mv,0);GLES20.glUniformMatrix4fv(uMvp,1,false,mvp,0);GLES20.glUniformMatrix4fv(uModel,1,false,model,0);int bc=coin.baseColor;GLES20.glUniform3f(uBase,((bc>>16)&255)/255f,((bc>>8)&255)/255f,(bc&255)/255f);GLES20.glUniform1f(uMetal,1f);GLES20.glUniform1f(uRough,coin.category.contains("ANCIENT")?.34f:.20f);coinVB.position(0);GLES20.glVertexAttribPointer(aPos,3,GLES20.GL_FLOAT,false,24,coinVB);GLES20.glEnableVertexAttribArray(aPos);coinVB.position(3);GLES20.glVertexAttribPointer(aNormal,3,GLES20.GL_FLOAT,false,24,coinVB);GLES20.glEnableVertexAttribArray(aNormal);GLES20.glDrawArrays(GLES20.GL_TRIANGLES,0,coinVertexCount);}
        private FloatBuffer loadObjOrFallback(String asset){try{ArrayList<float[]>vs=new ArrayList<>(),ns=new ArrayList<>();ArrayList<Float>out=new ArrayList<>();BufferedReader br=new BufferedReader(new InputStreamReader(context.getAssets().open(asset)));String line;while((line=br.readLine())!=null){line=line.trim();if(line.startsWith("v ")){String[]s=line.split("\\s+");vs.add(new float[]{Float.parseFloat(s[1]),Float.parseFloat(s[2]),Float.parseFloat(s[3])});}else if(line.startsWith("vn ")){String[]s=line.split("\\s+");ns.add(new float[]{Float.parseFloat(s[1]),Float.parseFloat(s[2]),Float.parseFloat(s[3])});}else if(line.startsWith("f ")){String[]s=line.substring(2).trim().split("\\s+");for(int i=1;i<s.length-1;i++){emit(s[0],vs,ns,out);emit(s[i],vs,ns,out);emit(s[i+1],vs,ns,out);}}}br.close();coinVertexCount=out.size()/6;ByteBuffer bb=ByteBuffer.allocateDirect(out.size()*4).order(ByteOrder.nativeOrder());FloatBuffer fb=bb.asFloatBuffer();for(Float f:out)fb.put(f);fb.position(0);return fb;}catch(Exception e){return makeFallbackCoin();}}
        private static void emit(String token,ArrayList<float[]>vs,ArrayList<float[]>ns,ArrayList<Float>out){String[]p=token.split("/");int vi=Integer.parseInt(p[0])-1,ni=p.length>2&&!p[2].isEmpty()?Integer.parseInt(p[2])-1:-1;float[]v=vs.get(vi),n=ni>=0&&ni<ns.size()?ns.get(ni):new float[]{0,0,1};for(float f:v)out.add(f);for(float f:n)out.add(f);}
        private FloatBuffer makeFallbackCoin(){ArrayList<Float>out=new ArrayList<>();int seg=192;float h=halfT;for(int i=0;i<seg;i++){float a0=(float)(2*Math.PI*i/seg),a1=(float)(2*Math.PI*(i+1)/seg);float x0=(float)Math.cos(a0),y0=(float)Math.sin(a0),x1=(float)Math.cos(a1),y1=(float)Math.sin(a1);tri(out,0,0,h,0,0,1,x0,y0,h,0,0,1,x1,y1,h,0,0,1);tri(out,0,0,-h,0,0,-1,x1,y1,-h,0,0,-1,x0,y0,-h,0,0,-1);tri(out,x0,y0,h,x0,y0,0,x0,y0,-h,x0,y0,0,x1,y1,h,x1,y1,0);tri(out,x1,y1,h,x1,y1,0,x0,y0,-h,x0,y0,0,x1,y1,-h,x1,y1,0);}coinVertexCount=out.size()/6;ByteBuffer bb=ByteBuffer.allocateDirect(out.size()*4).order(ByteOrder.nativeOrder());FloatBuffer fb=bb.asFloatBuffer();for(Float f:out)fb.put(f);fb.position(0);return fb;}
        private static void tri(ArrayList<Float>o,float...v){for(float f:v)o.add(f);}private static FloatBuffer makeFloor(){float[]a={-6,0,-6,0,1,0,6,0,-6,0,1,0,6,0,6,0,1,0,-6,0,-6,0,1,0,6,0,6,0,1,0,-6,0,6,0,1,0};ByteBuffer b=ByteBuffer.allocateDirect(a.length*4).order(ByteOrder.nativeOrder());FloatBuffer f=b.asFloatBuffer();f.put(a);f.position(0);return f;}
        private static int shader(int type,String src){int s=GLES20.glCreateShader(type);GLES20.glShaderSource(s,src);GLES20.glCompileShader(s);return s;}private static int link(String vs,String fs){int p=GLES20.glCreateProgram();GLES20.glAttachShader(p,shader(GLES20.GL_VERTEX_SHADER,vs));GLES20.glAttachShader(p,shader(GLES20.GL_FRAGMENT_SHADER,fs));GLES20.glLinkProgram(p);return p;}
        private static final String VS="attribute vec3 aPos;attribute vec3 aNormal;uniform mat4 uMvp;uniform mat4 uModel;varying vec3 vN;varying vec3 vP;void main(){vec4 wp=uModel*vec4(aPos,1.0);vP=wp.xyz;vN=normalize(mat3(uModel)*aNormal);gl_Position=uMvp*vec4(aPos,1.0);}";
        private static final String FS="precision mediump float;varying vec3 vN;varying vec3 vP;uniform vec3 uBase;uniform float uMetal;uniform float uRough;void main(){vec3 N=normalize(vN);vec3 L1=normalize(vec3(-0.45,0.82,0.35));vec3 L2=normalize(vec3(0.65,0.35,-0.45));vec3 V=normalize(vec3(0.0,3.2,8.6)-vP);float d1=max(dot(N,L1),0.0);float d2=max(dot(N,L2),0.0);vec3 H=normalize(L1+V);float spec=pow(max(dot(N,H),0.0),mix(120.0,28.0,uRough));float rim=pow(1.0-max(dot(N,V),0.0),3.0);vec3 env=vec3(0.20,0.24,0.32)*(0.18+rim*.55);vec3 col=uBase*(0.16+d1*.72+d2*.22)+env*uMetal+vec3(1.0,.92,.72)*spec*(.45+.85*uMetal);gl_FragColor=vec4(col,1.0);}";
    }
    private static final class Vec3{float x,y,z;Vec3(){this(0,0,0);}Vec3(float X,float Y,float Z){x=X;y=Y;z=Z;}Vec3 set(float X,float Y,float Z){x=X;y=Y;z=Z;return this;}float length(){return(float)Math.sqrt(x*x+y*y+z*z);}Vec3 scale(float s){x*=s;y*=s;z*=s;return this;}Vec3 add(Vec3 v){x+=v.x;y+=v.y;z+=v.z;return this;}Vec3 cross(Vec3 b){return new Vec3(y*b.z-z*b.y,z*b.x-x*b.z,x*b.y-y*b.x);}}
    private static final class Quat{float w=1,x=0,y=0,z=0;void setFromAxisAngle(float ax,float ay,float az,float a){float h=a*.5f,s=(float)Math.sin(h),l=(float)Math.sqrt(ax*ax+ay*ay+az*az);w=(float)Math.cos(h);x=ax/l*s;y=ay/l*s;z=az/l*s;}void integrate(Vec3 o,float dt){float len=o.length(),ang=len*dt;if(ang<1e-7)return;float inv=1f/len,h=ang*.5f,s=(float)Math.sin(h),dw=(float)Math.cos(h),dx=o.x*inv*s,dy=o.y*inv*s,dz=o.z*inv*s;float nw=dw*w-dx*x-dy*y-dz*z,nx=dw*x+dx*w+dy*z-dz*y,ny=dw*y-dx*z+dy*w+dz*x,nz=dw*z+dx*y-dy*x+dz*w;w=nw;x=nx;y=ny;z=nz;normalize();}void normalize(){float l=(float)Math.sqrt(w*w+x*x+y*y+z*z);w/=l;x/=l;y/=l;z/=l;}Vec3 rotate(Vec3 v){float ix=w*v.x+y*v.z-z*v.y,iy=w*v.y+z*v.x-x*v.z,iz=w*v.z+x*v.y-y*v.x,iw=-x*v.x-y*v.y-z*v.z;return new Vec3(ix*w+iw*-x+iy*-z-iz*-y,iy*w+iw*-y+iz*-x-ix*-z,iz*w+iw*-z+ix*-y-iy*-x);}void toMatrix(float[]m){Matrix.setIdentityM(m,0);float xx=x*x,yy=y*y,zz=z*z,xy=x*y,xz=x*z,yz=y*z,wx=w*x,wy=w*y,wz=w*z;m[0]=1-2*(yy+zz);m[1]=2*(xy+wz);m[2]=2*(xz-wy);m[4]=2*(xy-wz);m[5]=1-2*(xx+zz);m[6]=2*(yz+wx);m[8]=2*(xz+wy);m[9]=2*(yz-wx);m[10]=1-2*(xx+yy);}}
}
