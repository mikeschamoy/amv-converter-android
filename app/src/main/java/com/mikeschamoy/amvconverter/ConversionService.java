package com.mikeschamoy.amvconverter;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.*;
import com.arthenica.ffmpegkit.*;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class ConversionService extends Service {
    public static final String EXTRA_INPUT="input", EXTRA_OUTPUT="output", EXTRA_FILTER="filter";
    public static final String EXTRA_FPS="fps", EXTRA_BLOCK="block", EXTRA_Q1="q1";
    public static final String EXTRA_DURATION_MS="durationMs", EXTRA_START="trimStart", EXTRA_END="trimEnd";
    public static final String EXTRA_TEST="testClip", EXTRA_NAME="name";

    public static final String ACTION_CANCEL="com.mikeschamoy.amvconverter.CANCEL";
    public static final String ACTION_PROGRESS="com.mikeschamoy.amvconverter.PROGRESS";

    private static final String CHANNEL="conversion";
    private static final int NOTIFY_ID=100;
    private long currentSessionId=-1;
    private PowerManager.WakeLock wakeLock;
    private long effectiveDurationMs=0;
    private String outputName="output.amv";

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(intent==null) return START_NOT_STICKY;

        if(ACTION_CANCEL.equals(intent.getAction())) {
            if(currentSessionId>0) FFmpegKit.cancel(currentSessionId);
            finishWakeLock();
            sendState(0,0,0,"Cancelled",true);
            stopForeground(true); stopSelf();
            return START_NOT_STICKY;
        }

        acquireWakeLock();
        startInForeground(0,"Preparing conversion…");

        Uri input=Uri.parse(intent.getStringExtra(EXTRA_INPUT));
        Uri output=Uri.parse(intent.getStringExtra(EXTRA_OUTPUT));
        outputName=intent.getStringExtra(EXTRA_NAME);
        String filter=intent.getStringExtra(EXTRA_FILTER);
        int fps=intent.getIntExtra(EXTRA_FPS,25);
        int block=intent.getIntExtra(EXTRA_BLOCK,882);
        boolean q1=intent.getBooleanExtra(EXTRA_Q1,false);
        double start=intent.getDoubleExtra(EXTRA_START,0);
        double end=intent.getDoubleExtra(EXTRA_END,0);
        boolean test=intent.getBooleanExtra(EXTRA_TEST,false);
        long sourceDuration=intent.getLongExtra(EXTRA_DURATION_MS,0);

        if(test) effectiveDurationMs=30000;
        else if(end>start && end>0) effectiveDurationMs=(long)((end-start)*1000);
        else effectiveDurationMs=Math.max(0,sourceDuration-(long)(start*1000));

        String inputSaf=FFmpegKitConfig.getSafParameterForRead(this,input);
        String outputSaf=FFmpegKitConfig.getSafParameterForWrite(this,output);

        PresetEngine.Preset p=new PresetEngine.Preset();
        p.filter=filter;p.outputFps=fps;p.blockSize=block;p.cartoonQ1=q1;

        String command=PresetEngine.buildCommand(inputSaf,outputSaf,p,start,end,test);

        var session=FFmpegKit.executeAsync(command,completed->{
            boolean success=ReturnCode.isSuccess(completed.getReturnCode());
            String msg=success?"Finished":"Conversion failed";
            updateNotification(success?100:0,msg);
            addHistory((success?"✓ ":"✕ ")+outputName+" — "+msg);
            sendState(success?100:0,0,0,msg,true);
            finishWakeLock();
            stopForeground(false); stopSelf();
        },log->{},stats->{
            long t=(long)stats.getTime();
            double speed=stats.getSpeed();
            int pct=effectiveDurationMs>0?(int)Math.min(99,(t*100L)/effectiveDurationMs):0;
            long eta=0;
            if(speed>0 && effectiveDurationMs>t) eta=(long)((effectiveDurationMs-t)/(1000.0*speed));
            String text="Converting… "+pct+"% • "+String.format(Locale.US,"%.1fx",speed)
                    +(eta>0?" • "+fmtEta(eta)+" left":"");
            updateNotification(pct,text);
            sendState(pct,speed,eta,text,false);
        });
        currentSessionId=session.getSessionId();
        return START_NOT_STICKY;
    }

    private void sendState(int pct,double speed,long eta,String msg,boolean done) {
        Intent i=new Intent(ACTION_PROGRESS);
        i.setPackage(getPackageName());
        i.putExtra("pct",pct);i.putExtra("speed",speed);i.putExtra("eta",eta);
        i.putExtra("msg",msg);i.putExtra("done",done);
        sendBroadcast(i);
    }

    private String fmtEta(long sec){
        long h=sec/3600,m=(sec%3600)/60,s=sec%60;
        return h>0?String.format(Locale.US,"%d:%02d:%02d",h,m,s)
                :String.format(Locale.US,"%d:%02d",m,s);
    }

    private void addHistory(String entry){
        String stamp=new SimpleDateFormat("MM/dd HH:mm",Locale.US).format(new Date());
        var prefs=getSharedPreferences("history",MODE_PRIVATE);
        String old=prefs.getString("items","");
        String next=stamp+"  "+entry+(old.isEmpty()?"":"\n"+old);
        String[] lines=next.split("\n");
        StringBuilder b=new StringBuilder();
        for(int i=0;i<Math.min(lines.length,8);i++){
            if(i>0)b.append("\n");
            b.append(lines[i]);
        }
        prefs.edit().putString("items",b.toString()).apply();
    }

    private void acquireWakeLock(){
        PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);
        wakeLock=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"AMVConverter:Conversion");
        wakeLock.acquire(6*60*60*1000L);
    }
    private void finishWakeLock(){
        if(wakeLock!=null && wakeLock.isHeld())wakeLock.release();
        wakeLock=null;
    }

    private void startInForeground(int progress,String text){
        Notification n=notification(progress,text);
        if(Build.VERSION.SDK_INT>=35)startForeground(NOTIFY_ID,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING);
        else startForeground(NOTIFY_ID,n);
    }
    private void updateNotification(int progress,String text){
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFY_ID,notification(progress,text));
    }
    private Notification notification(int progress,String text){
        PendingIntent openPi=PendingIntent.getActivity(this,1,new Intent(this,MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Intent cancel=new Intent(this,ConversionService.class);cancel.setAction(ACTION_CANCEL);
        PendingIntent cancelPi=PendingIntent.getService(this,2,cancel,
                PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);
        b.setContentTitle("AMV Converter").setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download).setContentIntent(openPi)
                .setOngoing(progress<100 && !"Conversion failed".equals(text))
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,"Cancel",cancelPi).build());
        if(progress>0 && progress<100)b.setProgress(100,progress,false);
        else if(progress==0 && text.startsWith("Convert"))b.setProgress(0,0,true);
        return b.build();
    }
    private void createChannel(){
        if(Build.VERSION.SDK_INT>=26){
            NotificationChannel c=new NotificationChannel(CHANNEL,"AMV conversions",NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(c);
        }
    }
    @Override public void onTimeout(int startId,int fgsType){
        if(currentSessionId>0)FFmpegKit.cancel(currentSessionId);
        finishWakeLock();stopForeground(true);stopSelf();
    }
    @Override public IBinder onBind(Intent i){return null;}
    @Override public void onDestroy(){finishWakeLock();super.onDestroy();}
}
