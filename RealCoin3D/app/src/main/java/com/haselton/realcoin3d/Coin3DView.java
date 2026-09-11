package com.haselton.realcoin3d;

import android.content.Context;
import android.graphics.*;
import android.opengl.*;
import android.view.MotionEvent;
import java.nio.*;
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
        renderer = new CoinRenderer();
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
    public void flipButton(){ queueEvent(() -> renderer.flip(1.0f,(float)((Math.random()-.5)*.28))); }
    public void flip(float power,float lateral){ queueEvent(() -> renderer.flip(power,lateral)); }

    @Override public boolean onTouchEvent(MotionEvent e){
        switch(e.getActionMasked()){
            case MotionEvent.ACTION_DOWN: downX=e.getX(); downY=e.getY(); downMs=System.currentTimeMillis(); return true;
            case MotionEvent.ACTION_UP:
                long ms=Math.max(16,System.currentTimeMillis()-downMs);
                float dy=downY-e.getY(), dx=e.getX()-downX;
                if(dy>30*getResources().getDisplayMetrics().density){
                    float speed=dy/ms;
                    float strength=Math.max(.55f,Math.min(1.6f,.45f+speed*1.7f));
                    float lateral=Math.max(-.6f,Math.min(.6f,dx/ms*.85f));
                    flip(strength,lateral); performClick(); return true;
                }
                return true;
        }
        return true;
    }
    @Override public boolean performClick(){ super.performClick(); return true; }

    private static final class CoinRenderer implements Renderer {
        interface Callback { void impact(float strength); void settled(boolean heads); }
        Callback callback;
        volatile CoinDefinition coin;
        volatile boolean airborne=false;
        private int program, texture=0;
        private FloatBuffer vb; private ShortBuffer ib; private int indexCount;
        private int aPos,aNormal,aUv,uMvp,uModel,uTex,uBase,uHighlight,uAncient;
        private final float[] proj=new float[16], view=new float[16], model=new float[16], mv=new float[16], mvp=new float[16];
        private long lastNs;
        private final Random rng=new Random();
        private float px=0, py=.12f, pz=0, vx=0, vy=0, vz=0;
        private final Quat q=new Quat();
        private final Vec3 omega=new Vec3();
        private float halfT=.10f, radius=1f;
        private int lowMotionFrames=0;
        private boolean impactArmed=true;

        @Override public void onSurfaceCreated(javax.microedition.khronos.opengles.GL10 gl, javax.microedition.khronos.egl.EGLConfig config){
            GLES20.glClearColor(.025f,.032f,.043f,1f); GLES20.glEnable(GLES20.GL_DEPTH_TEST); GLES20.glEnable(GLES20.GL_CULL_FACE);
            program=link(VS,FS); aPos=GLES20.glGetAttribLocation(program,"aPos"); aNormal=GLES20.glGetAttribLocation(program,"aNormal"); aUv=GLES20.glGetAttribLocation(program,"aUv");
            uMvp=GLES20.glGetUniformLocation(program,"uMvp"); uModel=GLES20.glGetUniformLocation(program,"uModel"); uTex=GLES20.glGetUniformLocation(program,"uTex");
            uBase=GLES20.glGetUniformLocation(program,"uBase"); uHighlight=GLES20.glGetUniformLocation(program,"uHighlight"); uAncient=GLES20.glGetUniformLocation(program,"uAncient");
        }
        @Override public void onSurfaceChanged(javax.microedition.khronos.opengles.GL10 gl,int w,int h){ GLES20.glViewport(0,0,w,h); Matrix.perspectiveM(proj,0,32f,w/(float)Math.max(1,h),.1f,50f); Matrix.setLookAtM(view,0,0f,3.35f,8.8f,0f,1.1f,0f,0f,1f,0f); }
        @Override public void onDrawFrame(javax.microedition.khronos.opengles.GL10 gl){ GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT|GLES20.GL_DEPTH_BUFFER_BIT); step(); drawCoin(); }
        void setCoin(CoinDefinition c){ coin=c; halfT=(float)Math.max(.065,Math.min(.17,(c.thicknessMm/c.diameterMm)*2.25)); buildMesh(c); if(texture!=0)GLES20.glDeleteTextures(1,new int[]{texture},0); texture=createCoinTexture(c); py=supportHeight(); q.identity(); omega.set(0,0,0); airborne=false; }
        void flip(float strength,float lateral){ if(airborne||coin==null)return; airborne=true; lowMotionFrames=0; impactArmed=true; vx=lateral*1.7f; vz=(rng.nextFloat()-.5f)*.55f; vy=5.7f+Math.max(.5f,Math.min(1.6f,strength))*2.65f; omega.set(8.5f+rng.nextFloat()*5.5f,(rng.nextFloat()-.5f)*6f,(lateral*5f)+(rng.nextFloat()-.5f)*3.5f); if(rng.nextBoolean())omega.x*=-1f; lastNs=System.nanoTime(); }
        private void step(){ if(!airborne){lastNs=System.nanoTime();return;} long now=System.nanoTime(); float dt=Math.min(.025f,Math.max(.001f,(now-lastNs)/1_000_000_000f)); lastNs=now; int sub=2; float sdt=dt/sub; for(int k=0;k<sub;k++){ vy-=9.81f*sdt; px+=vx*sdt; py+=vy*sdt; pz+=vz*sdt; q.integrate(omega,sdt); float support=supportHeight(); if(py<support){ float inSpeed=-vy; py=support; if(inSpeed>.14f){ float strength=Math.min(1f,inSpeed/6.5f); if(impactArmed&&callback!=null)callback.impact(strength); impactArmed=false; vy=Math.max(0.03f,inSpeed*(.20f+.09f*strength)); vx*=.78f; vz*=.78f; omega.scale(.78f); omega.x+=(rng.nextFloat()-.5f)*1.3f*strength; omega.z+=(rng.nextFloat()-.5f)*1.3f*strength; } else { vy=0; vx*=.86f; vz*=.86f; Vec3 n=q.rotate(new Vec3(0,1,0)); float sign=n.y>=0?1f:-1f; Vec3 target=new Vec3(0,sign,0); Vec3 torque=n.cross(target).scale(7.6f*sdt); omega.add(torque); omega.scale(.935f); } } else if(py>support+.08f)impactArmed=true; float edge=2.25f; if(px<-edge){px=-edge;vx=Math.abs(vx)*.45f;} if(px>edge){px=edge;vx=-Math.abs(vx)*.45f;} } float speed=(float)Math.sqrt(vx*vx+vy*vy+vz*vz), spin=omega.length(); if(py<=supportHeight()+.015f&&speed<.10f&&spin<.16f)lowMotionFrames++;else lowMotionFrames=0; if(lowMotionFrames>16){ Vec3 n=q.rotate(new Vec3(0,1,0)); boolean heads=n.y>=0; q.setFromAxisAngle(1,0,0,heads?0:(float)Math.PI); py=halfT; vx=vy=vz=0; omega.set(0,0,0); airborne=false; if(callback!=null)callback.settled(heads); } }
        private float supportHeight(){ Vec3 n=q.rotate(new Vec3(0,1,0)); float ay=Math.min(1f,Math.abs(n.y)); return ay*halfT+(float)Math.sqrt(Math.max(0,1-ay*ay))*radius; }
        private void drawCoin(){ if(coin==null||vb==null)return; GLES20.glUseProgram(program); Matrix.setIdentityM(model,0); Matrix.translateM(model,0,px,py,pz); q.toMatrix(model); Matrix.multiplyMM(mv,0,view,0,model,0); Matrix.multiplyMM(mvp,0,proj,0,mv,0); GLES20.glUniformMatrix4fv(uMvp,1,false,mvp,0); GLES20.glUniformMatrix4fv(uModel,1,false,model,0); int bc=coin.baseColor,hc=coin.highlightColor; GLES20.glUniform3f(uBase,Color.red(bc)/255f,Color.green(bc)/255f,Color.blue(bc)/255f); GLES20.glUniform3f(uHighlight,Color.red(hc)/255f,Color.green(hc)/255f,Color.blue(hc)/255f); GLES20.glUniform1f(uAncient,(coin.category.contains("ANCIENT")||coin.id.equals("eightreales"))?1f:0f); GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,texture); GLES20.glUniform1i(uTex,0); vb.position(0); GLES20.glVertexAttribPointer(aPos,3,GLES20.GL_FLOAT,false,8*4,vb); GLES20.glEnableVertexAttribArray(aPos); vb.position(3); GLES20.glVertexAttribPointer(aNormal,3,GLES20.GL_FLOAT,false,8*4,vb); GLES20.glEnableVertexAttribArray(aNormal); vb.position(6); GLES20.glVertexAttribPointer(aUv,2,GLES20.GL_FLOAT,false,8*4,vb); GLES20.glEnableVertexAttribArray(aUv); ib.position(0); GLES20.glDrawElements(GLES20.GL_TRIANGLES,indexCount,GLES20.GL_UNSIGNED_SHORT,ib); }
        private void buildMesh(CoinDefinition c){ final int seg=128,rings=4; boolean irregular=c.category.contains("ANCIENT")||c.id.equals("eightreales"); java.util.ArrayList<Float> verts=new java.util.ArrayList<>(); java.util.ArrayList<Short> inds=new java.util.ArrayList<>(); float[] rr={1.00f,.988f,.955f,0f}; float[] yy={0f,.55f,1f,1f}; for(int side=0;side<2;side++){ float sy=side==0?1f:-1f; int base=verts.size()/8; for(int r=0;r<rings;r++)for(int i=0;i<seg;i++){ float a=(float)(2*Math.PI*i/seg); float wob=1f; if(irregular)wob=1f+.018f*(float)Math.sin(a*5.0+.7)+.012f*(float)Math.sin(a*9.0+1.6); float rad=rr[r]*wob;if(r==rings-1)rad=0; float x=(float)Math.cos(a)*rad,z=(float)Math.sin(a)*rad,y=sy*halfT*yy[r]; float nx=(r<2?(float)Math.cos(a)*.22f:0),nz=(r<2?(float)Math.sin(a)*.22f:0),ny=sy; float len=(float)Math.sqrt(nx*nx+ny*ny+nz*nz);nx/=len;ny/=len;nz/=len; float u=(side==0?0f:.5f)+(x*.5f+.5f)*.5f,v=z*.5f+.5f; add(verts,x,y,z,nx,ny,nz,u,v);} for(int r=0;r<rings-1;r++)for(int i=0;i<seg;i++){short a=(short)(base+r*seg+i),b=(short)(base+r*seg+(i+1)%seg),cc=(short)(base+(r+1)*seg+i),d=(short)(base+(r+1)*seg+(i+1)%seg);if(side==0){tri(inds,a,b,cc);tri(inds,b,d,cc);}else{tri(inds,a,cc,b);tri(inds,b,cc,d);}} } int edgeBase=verts.size()/8; for(int i=0;i<seg;i++){float a=(float)(2*Math.PI*i/seg);float wob=irregular?1f+.018f*(float)Math.sin(a*5.0+.7)+.012f*(float)Math.sin(a*9.0+1.6):1f;float x=(float)Math.cos(a)*wob,z=(float)Math.sin(a)*wob,nx=(float)Math.cos(a),nz=(float)Math.sin(a);add(verts,x,halfT*.55f,z,nx,0,nz,(i/(float)seg),0);add(verts,x,-halfT*.55f,z,nx,0,nz,(i/(float)seg),1);} for(int i=0;i<seg;i++){short a=(short)(edgeBase+i*2),b=(short)(edgeBase+((i+1)%seg)*2),c1=(short)(a+1),d=(short)(b+1);tri(inds,a,c1,b);tri(inds,b,c1,d);} ByteBuffer bb=ByteBuffer.allocateDirect(verts.size()*4).order(ByteOrder.nativeOrder());vb=bb.asFloatBuffer();for(Float f:verts)vb.put(f);vb.position(0);ByteBuffer ibb=ByteBuffer.allocateDirect(inds.size()*2).order(ByteOrder.nativeOrder());ib=ibb.asShortBuffer();for(Short s:inds)ib.put(s);ib.position(0);indexCount=inds.size(); }
        private static void add(java.util.ArrayList<Float>v,float...f){for(float x:f)v.add(x);} private static void tri(java.util.ArrayList<Short>i,short a,short b,short c){i.add(a);i.add(b);i.add(c);}
        private int createCoinTexture(CoinDefinition c){ final int W=1024,H=512; Bitmap bmp=Bitmap.createBitmap(W,H,Bitmap.Config.ARGB_8888); Canvas cv=new Canvas(bmp); Paint p=new Paint(Paint.ANTI_ALIAS_FLAG); Paint t=new Paint(Paint.ANTI_ALIAS_FLAG);t.setTextAlign(Paint.Align.CENTER); drawFace(cv,p,t,c,256,256,225,true); drawFace(cv,p,t,c,768,256,225,false); int[] ids=new int[1]; GLES20.glGenTextures(1,ids,0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,ids[0]); GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR_MIPMAP_LINEAR);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);GLUtils.texImage2D(GLES20.GL_TEXTURE_2D,0,bmp,0);GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D);bmp.recycle();return ids[0]; }
        private void drawFace(Canvas cv,Paint p,Paint t,CoinDefinition c,float cx,float cy,float r,boolean heads){ int base=c.baseColor,hi=c.highlightColor,sh=c.shadowColor; p.setShader(new RadialGradient(cx-r*.45f,cy-r*.55f,r*1.8f,new int[]{hi,base,sh},new float[]{0,.55f,1},Shader.TileMode.CLAMP));cv.drawCircle(cx,cy,r,p);p.setShader(null);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(10);p.setColor(Color.argb(210,Color.red(hi),Color.green(hi),Color.blue(hi)));cv.drawCircle(cx,cy,r*.88f,p);p.setStrokeWidth(3);p.setColor(Color.argb(140,20,20,20));cv.drawCircle(cx,cy,r*.78f,p);p.setStrokeWidth(2);p.setColor(Color.argb(75,25,25,25));for(int i=0;i<72;i++){double a=i*Math.PI*2/72;float r1=r*.81f,r2=(i%2==0?r*.88f:r*.85f);cv.drawLine(cx+(float)Math.cos(a)*r1,cy+(float)Math.sin(a)*r1,cx+(float)Math.cos(a)*r2,cy+(float)Math.sin(a)*r2,p);} boolean ancient=c.category.contains("ANCIENT");if(ancient){p.setStyle(Paint.Style.FILL);p.setColor(Color.argb(30,0,0,0));for(int i=0;i<35;i++){float x=cx+(rng.nextFloat()-.5f)*r*1.5f,y=cy+(rng.nextFloat()-.5f)*r*1.5f,rr=2+rng.nextFloat()*11;cv.drawCircle(x,y,rr,p);}}p.setStyle(Paint.Style.FILL);t.setTypeface(Typeface.create(Typeface.SERIF,Typeface.BOLD));t.setTextSize(heads?58:64);t.setColor(Color.argb(205,34,30,24));cv.drawText(heads?c.headsMark:c.tailsMark,cx,cy+20,t);t.setTypeface(Typeface.create(Typeface.SANS_SERIF,Typeface.BOLD));t.setTextSize(22);t.setLetterSpacing(.12f);t.setColor(Color.argb(180,36,32,26));cv.drawText(heads?"OBVERSE":"REVERSE",cx,cy+r*.58f,t);t.setTextSize(16);t.setLetterSpacing(.08f);cv.drawText(c.name.toUpperCase(),cx,cy-r*.58f,t);p.setStyle(Paint.Style.FILL); }
        private static int compile(int type,String src){int s=GLES20.glCreateShader(type);GLES20.glShaderSource(s,src);GLES20.glCompileShader(s);return s;} private static int link(String vs,String fs){int p=GLES20.glCreateProgram();GLES20.glAttachShader(p,compile(GLES20.GL_VERTEX_SHADER,vs));GLES20.glAttachShader(p,compile(GLES20.GL_FRAGMENT_SHADER,fs));GLES20.glLinkProgram(p);return p;}
        private static final String VS="attribute vec3 aPos;attribute vec3 aNormal;attribute vec2 aUv;uniform mat4 uMvp;uniform mat4 uModel;varying vec3 vN;varying vec3 vW;varying vec2 vUv;void main(){vec4 w=uModel*vec4(aPos,1.0);vW=w.xyz;vN=normalize(mat3(uModel)*aNormal);vUv=aUv;gl_Position=uMvp*vec4(aPos,1.0);}";
        private static final String FS="precision mediump float;varying vec3 vN;varying vec3 vW;varying vec2 vUv;uniform sampler2D uTex;uniform vec3 uBase;uniform vec3 uHighlight;uniform float uAncient;void main(){vec3 N=normalize(vN);vec3 L=normalize(vec3(-.42,.78,.55));vec3 V=normalize(vec3(0.,3.35,8.8)-vW);vec3 H=normalize(L+V);float ndl=max(dot(N,L),0.0);float spec=pow(max(dot(N,H),0.0),72.0);float rim=pow(1.0-max(dot(N,V),0.0),3.0);vec4 tex=texture2D(uTex,vUv);float reed=0.0;if(abs(N.y)<.35)reed=.12*sin(vUv.x*900.0);vec3 metal=mix(uBase,tex.rgb,.63);metal*=.34+.72*ndl;metal+=uHighlight*(spec*.75+rim*.16+reed);if(uAncient>.5)metal*=.92+.08*sin(vW.x*31.0+vW.z*19.0);gl_FragColor=vec4(metal,1.0);}";
    }
    private static final class Vec3 { float x,y,z; Vec3(){this(0,0,0);} Vec3(float x,float y,float z){this.x=x;this.y=y;this.z=z;} Vec3 set(float x,float y,float z){this.x=x;this.y=y;this.z=z;return this;} Vec3 add(Vec3 o){x+=o.x;y+=o.y;z+=o.z;return this;} Vec3 scale(float s){x*=s;y*=s;z*=s;return this;} float length(){return (float)Math.sqrt(x*x+y*y+z*z);} Vec3 cross(Vec3 o){return new Vec3(y*o.z-z*o.y,z*o.x-x*o.z,x*o.y-y*o.x);} }
    private static final class Quat { float x=0,y=0,z=0,w=1; void identity(){x=y=z=0;w=1;} void setFromAxisAngle(float ax,float ay,float az,float a){float h=a*.5f,s=(float)Math.sin(h);x=ax*s;y=ay*s;z=az*s;w=(float)Math.cos(h);normalize();} void integrate(Vec3 o,float dt){float a=o.length()*dt;if(a<1e-6)return;float inv=1f/o.length();Quat d=new Quat();d.setFromAxisAngle(o.x*inv,o.y*inv,o.z*inv,a);mul(d);normalize();} void mul(Quat b){float nw=w*b.w-x*b.x-y*b.y-z*b.z,nx=w*b.x+x*b.w+y*b.z-z*b.y,ny=w*b.y-x*b.z+y*b.w+z*b.x,nz=w*b.z+x*b.y-y*b.x+z*b.w;w=nw;x=nx;y=ny;z=nz;} void normalize(){float l=(float)Math.sqrt(x*x+y*y+z*z+w*w);x/=l;y/=l;z/=l;w/=l;} Vec3 rotate(Vec3 v){float qx=x,qy=y,qz=z,qw=w;float ix=qw*v.x+qy*v.z-qz*v.y,iy=qw*v.y+qz*v.x-qx*v.z,iz=qw*v.z+qx*v.y-qy*v.x,iw=-qx*v.x-qy*v.y-qz*v.z;return new Vec3(ix*qw+iw*-qx+iy*-qz-iz*-qy,iy*qw+iw*-qy+iz*-qx-ix*-qz,iz*qw+iw*-qz+ix*-qy-iy*-qx);} void toMatrix(float[] m){float tx=2*x,ty=2*y,tz=2*z,twx=tx*w,twy=ty*w,twz=tz*w,txx=tx*x,txy=ty*x,txz=tz*x,tyy=ty*y,tyz=tz*y,tzz=tz*z;m[0]=1-(tyy+tzz);m[1]=txy+twz;m[2]=txz-twy;m[4]=txy-twz;m[5]=1-(txx+tzz);m[6]=tyz+twx;m[8]=txz+twy;m[9]=tyz-twx;m[10]=1-(txx+tyy);} }
}
