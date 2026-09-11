package com.haselton.realcoin3d;

import android.app.*;
import android.os.*;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import com.google.android.gms.ads.*;
import com.google.android.gms.ads.interstitial.*;
import java.util.*;

public final class MainActivity extends Activity {
    private Coin3DView coinView; private TextView result, stats, coinName; private final List<CoinDefinition> coins=CoinCatalog.starterCoins();
    private int flips=0,heads=0,tails=0; private boolean haptics=true,sound=true; private CoinSoundEngine sounds=new CoinSoundEngine(); private InterstitialAd interstitial;
    private android.os.Vibrator vibrator;
    @Override public void onCreate(Bundle b){super.onCreate(b); getWindow().setStatusBarColor(Color.rgb(8,10,13));getWindow().setNavigationBarColor(Color.rgb(8,10,13)); vibrator=(android.os.Vibrator)getSystemService(VIBRATOR_SERVICE); MobileAds.initialize(this, status -> {}); loadInterstitial(); buildUi();}
    private TextView tv(String s,int sp,int color){TextView v=new TextView(this);v.setText(s);v.setTextSize(sp);v.setTextColor(color);v.setGravity(Gravity.CENTER);v.setPadding(12,8,12,8);return v;}
    private GradientDrawable pill(int fill,int stroke){GradientDrawable g=new GradientDrawable();g.setColor(fill);g.setCornerRadius(80);g.setStroke(2,stroke);return g;}
    private void buildUi(){LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.rgb(8,10,13));root.setPadding(18,12,18,0);
        TextView title=tv("REALCOIN 3D",24,Color.WHITE);title.setTypeface(null,1);root.addView(title,new LinearLayout.LayoutParams(-1,-2));
        TextView sub=tv("PHYSICAL COIN TOSS",11,Color.rgb(185,185,185));root.addView(sub,new LinearLayout.LayoutParams(-1,-2));
        coinName=tv("",15,Color.rgb(232,194,84));coinName.setBackground(pill(Color.rgb(21,24,29),Color.rgb(83,75,48)));coinName.setOnClickListener(v->chooseCoin());LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(-1,-2);np.setMargins(18,10,18,8);root.addView(coinName,np);
        coinView=new Coin3DView(this);coinView.setCoin(coins.get(1));coinName.setText("◀  "+coins.get(1).name+"  ▾");coinView.setListener(new Coin3DView.Listener(){public void onImpact(float s){if(sound)sounds.impact(s);if(haptics)vibrateImpact(s);}public void onSettled(boolean h){flips++;if(h)heads++;else tails++;result.setText(h?"HEADS":"TAILS");stats.setText(heads+" Heads   •   "+tails+" Tails   •   "+flips+" Flips");if(haptics)vibrate(22,80); if(flips%10==0 && interstitial!=null){interstitial.show(MainActivity.this);interstitial=null;loadInterstitial();}}});
        root.addView(coinView,new LinearLayout.LayoutParams(-1,0,1f));
        result=tv("FLICK UP TO TOSS",22,Color.WHITE);result.setTypeface(null,1);root.addView(result,new LinearLayout.LayoutParams(-1,-2));
        stats=tv("0 Heads   •   0 Tails   •   0 Flips",13,Color.rgb(170,176,181));root.addView(stats,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout controls=new LinearLayout(this);controls.setGravity(Gravity.CENTER);controls.setPadding(4,6,4,8);
        Button flip=new Button(this);flip.setText("FLIP");flip.setTextColor(Color.rgb(18,18,18));flip.setTextSize(16);flip.setTypeface(null,1);flip.setBackground(pill(Color.rgb(226,184,67),Color.rgb(255,218,111)));flip.setOnClickListener(v->{if(!coinView.isAirborne()){result.setText("IN THE AIR…");if(haptics)vibrate(18,70);coinView.flipButton();}});LinearLayout.LayoutParams fp=new LinearLayout.LayoutParams(0,58,1f);fp.setMargins(6,0,6,0);controls.addView(flip,fp);
        Button opts=new Button(this);opts.setText("⚙");opts.setTextColor(Color.WHITE);opts.setTextSize(20);opts.setBackground(pill(Color.rgb(25,28,34),Color.rgb(65,70,76)));opts.setOnClickListener(v->settings());LinearLayout.LayoutParams op=new LinearLayout.LayoutParams(72,58);op.setMargins(6,0,6,0);controls.addView(opts,op);root.addView(controls,new LinearLayout.LayoutParams(-1,-2));
        AdView ad=new AdView(this);ad.setAdUnitId("ca-app-pub-3940256099942544/9214589741");ad.setAdSize(AdSize.BANNER);ad.loadAd(new AdRequest.Builder().build());FrameLayout adWrap=new FrameLayout(this);adWrap.addView(ad,new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER));root.addView(adWrap,new LinearLayout.LayoutParams(-1,-2));setContentView(root);}
    private void chooseCoin(){String[] names=new String[coins.size()];for(int i=0;i<coins.size();i++)names[i]=coins.get(i).name+"\n"+coins.get(i).category+" • "+coins.get(i).metal;new AlertDialog.Builder(this).setTitle("Choose a coin").setItems(names,(d,w)->{CoinDefinition c=coins.get(w);coinView.setCoin(c);coinName.setText("◀  "+c.name+"  ▾");result.setText("FLICK UP TO TOSS");}).show();}
    private void settings(){String[] items={"Haptics: "+(haptics?"ON":"OFF"),"Sound: "+(sound?"ON":"OFF"),"Reset statistics","About this coin"};new AlertDialog.Builder(this).setTitle("Settings").setItems(items,(d,w)->{if(w==0)haptics=!haptics;else if(w==1)sound=!sound;else if(w==2){flips=heads=tails=0;stats.setText("0 Heads   •   0 Tails   •   0 Flips");result.setText("FLICK UP TO TOSS");}else{CoinDefinition c=coinView.getCoin();new AlertDialog.Builder(this).setTitle(c.name).setMessage(c.category+"\n"+c.era+"\n"+c.metal+"\n"+c.diameterMm+" mm • "+c.weightGrams+" g\n\n"+c.note+"\n\nHistorical designs in this prototype are simulations, not claims of authenticity.").setPositiveButton("OK",null).show();}}).show();}
    private void vibrateImpact(float s){vibrate((int)(18+s*28),(int)(80+s*140));}
    private void vibrate(int ms,int amp){if(vibrator==null||!vibrator.hasVibrator())return;if(Build.VERSION.SDK_INT>=26)vibrator.vibrate(VibrationEffect.createOneShot(ms,Math.max(1,Math.min(255,amp))));else vibrator.vibrate(ms);}
    private void loadInterstitial(){AdRequest req=new AdRequest.Builder().build();InterstitialAd.load(this,"ca-app-pub-3940256099942544/1033173712",req,new InterstitialAdLoadCallback(){@Override public void onAdLoaded(InterstitialAd ad){interstitial=ad;}@Override public void onAdFailedToLoad(LoadAdError e){interstitial=null;}});}
}
