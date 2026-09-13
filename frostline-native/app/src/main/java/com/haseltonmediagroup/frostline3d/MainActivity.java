package com.haseltonmediagroup.frostline3d;

import android.app.Activity;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;

public class MainActivity extends Activity implements SensorEventListener {
    private SensorManager sensors;
    private Sensor rotation, accel;
    private FrostSurface game;
    private final float[] rotM=new float[9], ori=new float[3];
    private float neutralRoll=Float.NaN, neutralPitch=Float.NaN;
    private float filteredAccel=9.81f;
    private long lastFlick=0;

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideUi();
        sensors=(SensorManager)getSystemService(SENSOR_SERVICE);
        rotation=sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        if(rotation==null) rotation=sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        accel=sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);

        FrameLayout root=new FrameLayout(this);
        game=new FrostSurface(this);
        root.addView(game,new FrameLayout.LayoutParams(-1,-1));
        root.addView(button("CAM RESET",24,24,190,78,v->{ neutralRoll=Float.NaN; neutralPitch=Float.NaN; game.renderer.cameraResetPulse(); vibrate(); }));
        root.addView(button("GRIND",0,0,160,78,v->{ game.renderer.grind(); vibrate(); }), bottomRight(350,24,160,78));
        root.addView(button("JUMP",0,0,160,78,v->{ game.renderer.jump(); vibrate(); }), bottomRight(170,24,160,78));
        setContentView(root);
    }

    private Button button(String text,int left,int top,int w,int h,View.OnClickListener l){
        Button b=new Button(this); b.setText(text); b.setTextColor(Color.WHITE); b.setTextSize(16); b.setBackgroundColor(Color.argb(185,28,32,42)); b.setOnClickListener(l);
        FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(w,h); lp.leftMargin=left; lp.topMargin=top; b.setLayoutParams(lp); return b;
    }
    private FrameLayout.LayoutParams bottomRight(int right,int bottom,int w,int h){
        FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(w,h); lp.gravity=Gravity.RIGHT|Gravity.BOTTOM; lp.rightMargin=right; lp.bottomMargin=bottom; return lp;
    }
    private void hideUi(){
        if(android.os.Build.VERSION.SDK_INT>=30){ WindowInsetsController c=getWindow().getInsetsController(); if(c!=null){c.hide(WindowInsets.Type.statusBars()|WindowInsets.Type.navigationBars());c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);} }
        else getWindow().getDecorView().setSystemUiVisibility(5894|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }
    private void vibrate(){ try{ Vibrator v=(Vibrator)getSystemService(VIBRATOR_SERVICE); if(v!=null)v.vibrate(VibrationEffect.createOneShot(28,80)); }catch(Exception ignored){} }
    private static float shape(float deg,float dead,float full){ float a=Math.abs(deg); if(a<=dead)return 0; float t=Math.min(1,(a-dead)/(full-dead)); t=t*t*(3-2*t); return Math.signum(deg)*t; }
    private static float wrap(float a){ while(a>Math.PI)a-=Math.PI*2; while(a<-Math.PI)a+=Math.PI*2; return a; }

    @Override public void onSensorChanged(SensorEvent e){
        if(e.sensor.getType()==Sensor.TYPE_GAME_ROTATION_VECTOR||e.sensor.getType()==Sensor.TYPE_ROTATION_VECTOR){
            SensorManager.getRotationMatrixFromVector(rotM,e.values); SensorManager.getOrientation(rotM,ori);
            float roll=ori[1], pitch=ori[2];
            if(Float.isNaN(neutralRoll)){ neutralRoll=roll; neutralPitch=pitch; vibrate(); }
            float steer=shape((float)Math.toDegrees(wrap(roll-neutralRoll)),3,24);
            float stance=shape(-(float)Math.toDegrees(wrap(pitch-neutralPitch)),3,18);
            game.renderer.setControls(steer,stance);
        } else if(e.sensor.getType()==Sensor.TYPE_ACCELEROMETER){
            float m=(float)Math.sqrt(e.values[0]*e.values[0]+e.values[1]*e.values[1]+e.values[2]*e.values[2]);
            float impulse=Math.abs(m-filteredAccel); filteredAccel=filteredAccel*.86f+m*.14f; long now=System.currentTimeMillis();
            if(impulse>5.6f&&now-lastFlick>550){lastFlick=now;game.renderer.jump();vibrate();}
        }
    }
    @Override public void onAccuracyChanged(Sensor s,int a){}
    @Override protected void onResume(){super.onResume();hideUi();game.onResume();if(rotation!=null)sensors.registerListener(this,rotation,SensorManager.SENSOR_DELAY_GAME);if(accel!=null)sensors.registerListener(this,accel,SensorManager.SENSOR_DELAY_GAME);}
    @Override protected void onPause(){sensors.unregisterListener(this);game.onPause();super.onPause();}
}
