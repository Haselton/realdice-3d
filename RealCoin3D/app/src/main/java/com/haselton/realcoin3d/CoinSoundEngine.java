package com.haselton.realcoin3d;

import android.media.*;
import java.util.Random;

public final class CoinSoundEngine {
    private final Random random = new Random();
    public void impact(float strength) {
        final float s = Math.max(.08f, Math.min(1f, strength));
        new Thread(() -> {
            int sr = 22050, ms = 95, n = sr*ms/1000;
            short[] pcm = new short[n];
            double f1 = 1750 + random.nextInt(500), f2 = 3100 + random.nextInt(800);
            for (int i=0;i<n;i++) {
                double t=i/(double)sr;
                double env=Math.exp(-t*38.0);
                double metal=Math.sin(2*Math.PI*f1*t)+0.55*Math.sin(2*Math.PI*f2*t);
                double noise=(random.nextDouble()*2-1)*0.22;
                pcm[i]=(short)(Math.max(-1,Math.min(1,(metal+noise)*env*s*0.42))*32767);
            }
            try {
                AudioTrack track=new AudioTrack(AudioManager.STREAM_MUSIC,sr,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT,pcm.length*2,AudioTrack.MODE_STATIC);
                track.write(pcm,0,pcm.length); track.play();
                Thread.sleep(ms+40); track.release();
            } catch(Exception ignored) {}
        }, "coin-sound").start();
    }
}
