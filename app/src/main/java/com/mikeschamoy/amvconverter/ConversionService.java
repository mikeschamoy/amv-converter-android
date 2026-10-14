package com.mikeschamoy.amvconverter;

import android.app.*;
import android.content.*;
import android.content.ContentValues;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.provider.MediaStore;

import com.arthenica.ffmpegkit.*;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.regex.*;

public class ConversionService extends Service {
    public static final String ACTION_CANCEL="com.mikeschamoy.amvconverter.CANCEL";
    public static final String ACTION_PROGRESS="com.mikeschamoy.amvconverter.PROGRESS";
    public static final String EXTRA_INPUT="input",EXTRA_INPUTS="inputs",EXTRA_OUTPUT="output",EXTRA_TREE="tree";
    public static final String EXTRA_TYPE="type",EXTRA_MODE="mode",EXTRA_HEIGHT="height",EXTRA_FPS="fps";
    public static final String EXTRA_43="f43",EXTRA_PORTRAIT="portrait",EXTRA_AUTOBARS="autobars";
    public static final String EXTRA_START="start",EXTRA_END="end",EXTRA_TEST="test",EXTRA_DOWNLOADS="downloads",EXTRA_QUEUE_AUTO="queueAuto",EXTRA_PERFORMANCE="performance",EXTRA_FRAME_MODE="frameMode",EXTRA_PICTURE_MODE="pictureMode";

    private static final String CHANNEL="conversion";
    private static final int NOTIFY_ID=100;
    private volatile boolean cancelRequested=false;
    private volatile long currentSessionId=-1;
    private volatile int performanceMode=0;
    private volatile long lastProgressUpdateMs=0;
    private PowerManager.WakeLock wakeLock;

    @Override public void onCreate(){super.onCreate();createChannel();}

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null)return START_NOT_STICKY;
        if(ACTION_CANCEL.equals(intent.getAction())){
            cancelRequested=true;
            if(currentSessionId>0)FFmpegKit.cancel(currentSessionId);
            return START_NOT_STICKY;
        }
        cancelRequested=false;
        performanceMode=Math.max(0,Math.min(2,intent.getIntExtra(EXTRA_PERFORMANCE,0)));
        lastProgressUpdateMs=0;
        acquireWake();startFg("Preparing…",0);
        getSharedPreferences("state",MODE_PRIVATE).edit().putBoolean("jobActive",true).apply();
        new Thread(()->runJobs(intent)).start();
        return START_NOT_STICKY;
    }

    private void runJobs(Intent intent){
        ArrayList<String> inputs=intent.getStringArrayListExtra(EXTRA_INPUTS);
        if(inputs==null){
            inputs=new ArrayList<>();
            String s=intent.getStringExtra(EXTRA_INPUT);
            if(s!=null)inputs.add(s);
        }
        String singleOut=intent.getStringExtra(EXTRA_OUTPUT);
        String tree=intent.getStringExtra(EXTRA_TREE);

        PresetEngine.Type type=PresetEngine.Type.values()[intent.getIntExtra(EXTRA_TYPE,0)];
        PresetEngine.Mode mode=PresetEngine.Mode.values()[intent.getIntExtra(EXTRA_MODE,1)];
        int height=intent.getIntExtra(EXTRA_HEIGHT,0);
        int fps=intent.getIntExtra(EXTRA_FPS,0);
        PresetEngine.PictureMode picture=PresetEngine.PictureMode.values()[Math.max(0,Math.min(2,intent.getIntExtra(EXTRA_PICTURE_MODE,0)))];
        PresetEngine.FrameMode frame=PresetEngine.FrameMode.values()[Math.max(0,Math.min(1,intent.getIntExtra(EXTRA_FRAME_MODE,0)))];
        boolean preserve43=intent.getBooleanExtra(EXTRA_43,false);
        PresetEngine.PortraitMode portrait=PresetEngine.PortraitMode.values()[intent.getIntExtra(EXTRA_PORTRAIT,0)];
        boolean autoBars=intent.getBooleanExtra(EXTRA_AUTOBARS,true);
        double start=intent.getDoubleExtra(EXTRA_START,0),end=intent.getDoubleExtra(EXTRA_END,0);
        boolean test=intent.getBooleanExtra(EXTRA_TEST,false);
        boolean downloads=intent.getBooleanExtra(EXTRA_DOWNLOADS,false);
        boolean queueAuto=intent.getBooleanExtra(EXTRA_QUEUE_AUTO,false);

        int total=inputs.size(),fail=0,completed=0;
        String lastName=""; long lastBytes=0,totalBytes=0;
        for(int i=0;i<total&&!cancelRequested;i++){
            Uri in=Uri.parse(inputs.get(i));
            String original=displayName(in);
            update(i,total,0,0,0,"Inspecting "+original,false);

            PresetEngine.SourceInfo info=inspect(in);
            if(info==null){fail++;addHistory("✕ "+original+" — inspect failed");continue;}

            PresetEngine.Crop crop=autoBars&&!info.image?detectBars(in,info):null;
            if(total==1&&intent.hasExtra("manualCropW")){crop=new PresetEngine.Crop(
                    intent.getIntExtra("manualCropW",0),intent.getIntExtra("manualCropH",0),
                    intent.getIntExtra("manualCropX",0),intent.getIntExtra("manualCropY",0));}
            PresetEngine.Type itemType=queueAuto?(info.isPortrait()?PresetEngine.Type.PORTRAIT:(type==PresetEngine.Type.PORTRAIT?PresetEngine.Type.YOUTUBE:type)):type;
            int itemHeight=queueAuto?0:height;
            int itemFps=queueAuto?0:fps;
            PresetEngine.PictureMode processing=picture;
            if(picture==PresetEngine.PictureMode.AUTO){
                PictureAnalysis.Result result=PictureAnalysis.analyze(this,in,info);
                processing=result.mode;
            }
            PresetEngine.Preset preset=PresetEngine.choose(info,itemType,mode,itemHeight,itemFps,preserve43,portrait,crop,frame,processing);

            String outName=FileNameCleaner.clean(original,itemType,info.image);
            Uri out;
            if(total==1&&singleOut!=null)out=Uri.parse(singleOut);
            else if(downloads)out=createInDownloads(outName,info.image);
            else out=createInTree(Uri.parse(tree),outName,info.image);

            if(out==null){fail++;addHistory("✕ "+outName+" — output create failed");continue;}

            double runtime=test?30:(end>start&&end>0?end-start:Math.max(0,info.durationSeconds-start));
            boolean ok=convertOne(in,out,info,preset,start,end,test,i,total,outName,runtime);
            if(!ok){fail++;continue;}

            boolean verified=verify(out,info.image,runtime);
            boolean seekOk=info.image || verifySeek(out,runtime);
            long bytes=querySize(out);
            completed++; lastName=displayName(out); lastBytes=bytes; totalBytes+=Math.max(0,bytes);
            String mark=verified&&seekOk?"✓ ":"⚠ ";
            String note=!verified?" verify warning":(!seekOk?" seek warning":" • seek OK");
            addHistory(mark+outName+" — "+human(bytes)+note);
        }

        String title,msg;
        if(cancelRequested){
            title="ClipAMV • Cancelled";
            msg="Finished "+completed+"/"+total+" files before cancellation";
        }else if(total==1 && completed==1){
            title="✓ "+lastName+" completed";
            msg=human(lastBytes)+" • Ready";
        }else if(total==1){
            title="ClipAMV • Conversion failed";
            msg="No output was completed";
        }else{
            title=fail==0?"✓ Queue complete • "+completed+"/"+total:"Queue finished • "+completed+"/"+total;
            msg="Last: "+(lastName.isEmpty()?"None":lastName)+" • "+human(totalBytes)+(fail>0?" • "+fail+" failed":"");
        }
        update(total,total,100,0,0,title+" • "+msg,true);
        getSharedPreferences("state",MODE_PRIVATE).edit().putBoolean("jobActive",false).apply();
        releaseWake();stopForeground(true);
        notifyDone(title,msg);stopSelf();
    }

    private boolean convertOne(Uri in,Uri out,PresetEngine.SourceInfo info,PresetEngine.Preset preset,
                               double start,double end,boolean test,int index,int total,String outName,double runtime){
        String inSaf=FFmpegKitConfig.getSafParameterForRead(this,in);
        String outSaf=FFmpegKitConfig.getSafParameterForWrite(this,out);
        String cmd=info.image?PresetEngine.buildImageCommand(inSaf,outSaf,preset):
                PresetEngine.buildVideoCommand(inSaf,outSaf,preset,start,end,test,performanceMode);

        CountDownLatch latch=new CountDownLatch(1);
        final boolean[] ok={false};
        FFmpegSession session=FFmpegKit.executeAsync(cmd,done->{
            ok[0]=ReturnCode.isSuccess(done.getReturnCode());latch.countDown();
        },log->{},stats->{
            if(cancelRequested)return;
            long now=SystemClock.elapsedRealtime();
            long interval=performanceMode==2?1600:performanceMode==1?900:400;
            if(now-lastProgressUpdateMs<interval)return;
            lastProgressUpdateMs=now;
            long t=(long)stats.getTime();
            int pct=runtime>0?(int)Math.min(99,(t/1000.0)*100.0/runtime):0;
            double speed=stats.getSpeed();
            long eta=speed>0?Math.max(0,(long)((runtime-t/1000.0)/speed)):0;
            update(index,total,pct,speed,eta,(index+1)+"/"+total+" • "+outName,false);
        });
        currentSessionId=session.getSessionId();
        try{latch.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}
        currentSessionId=-1;
        if(!ok[0]){
            addHistory("✕ "+outName+" — conversion failed");
            try{DocumentsContract.deleteDocument(getContentResolver(),out);}catch(Exception ignored){}
        }
        return ok[0];
    }

    private PresetEngine.SourceInfo inspect(Uri uri){
        String saf=FFmpegKitConfig.getSafParameterForRead(this,uri);
        String cmd="-v error -show_entries stream=index,codec_type,codec_name,width,height,avg_frame_rate,sample_aspect_ratio,display_aspect_ratio:stream_side_data=rotation:stream_tags=rotate "+
                "-show_entries format=duration -of default=noprint_wrappers=1 "+q(saf);
        FFprobeSession ss=FFprobeKit.execute(cmd);
        String out=ss.getOutput();
        try{
            PresetEngine.SourceInfo s=new PresetEngine.SourceInfo();
            s.audioPresent=false;
            String currentType="";
            for(String raw:out.split("\\r?\\n")){
                String line=raw.trim();
                if(line.startsWith("codec_type="))currentType=line.substring(11);
                else if(line.startsWith("codec_name=")){
                    if("video".equals(currentType))s.videoCodec=line.substring(11);
                    else if("audio".equals(currentType)){s.audioCodec=line.substring(11);s.audioPresent=true;}
                }else if(line.startsWith("width=")&&s.width==0)s.width=Integer.parseInt(line.substring(6));
                else if(line.startsWith("height=")&&s.height==0)s.height=Integer.parseInt(line.substring(7));
                else if(line.startsWith("sample_aspect_ratio=")){double r=line.endsWith("N/A")?0:rational(line.substring(20).replace(':','/'));if(r>0)s.sampleAspect=r;}
                else if(line.startsWith("rotation="))s.rotation=(int)Math.round(Double.parseDouble(line.substring(9)));
                else if(line.startsWith("TAG:rotate="))s.rotation=(int)Math.round(Double.parseDouble(line.substring(11)));
                else if(line.startsWith("avg_frame_rate=")&&s.fps==0)s.fps=rational(line.substring(15));
                else if(line.startsWith("duration=")&&!line.endsWith("N/A"))s.durationSeconds=Double.parseDouble(line.substring(9));
            }
            String mime=getContentResolver().getType(uri);
            s.image=mime!=null&&mime.startsWith("image/");
            if(s.image){s.durationSeconds=0;s.fps=1;s.audioPresent=false;}
            if(s.width<=0||s.height<=0)return null;
            if(!s.image&&s.fps<=0)s.fps=25;
            s.inputBytes=querySize(uri);
            return s;
        }catch(Exception e){return null;}
    }

    private PresetEngine.Crop detectBars(Uri uri,PresetEngine.SourceInfo info){
        double ss=Math.max(0,info.durationSeconds/2.0-4);
        String saf=FFmpegKitConfig.getSafParameterForRead(this,uri);
        FFmpegSession session=FFmpegKit.execute("-ss "+fmt(ss)+" -i "+q(saf)+" -t 8 -vf cropdetect=24:16:0 -an -f null -");
        Matcher m=Pattern.compile("crop=(\\d+):(\\d+):(\\d+):(\\d+)").matcher(session.getOutput());
        PresetEngine.Crop last=null;
        while(m.find())last=new PresetEngine.Crop(Integer.parseInt(m.group(1)),Integer.parseInt(m.group(2)),Integer.parseInt(m.group(3)),Integer.parseInt(m.group(4)));
        return PresetEngine.isMeaningfulCrop(info,last)?last:null;
    }

    private boolean verify(Uri out,boolean image,double expected){
        String saf=FFmpegKitConfig.getSafParameterForRead(this,out);
        if(image){
            FFprobeSession s=FFprobeKit.execute("-v error -select_streams v:0 -show_entries stream=width,height -of csv=p=0 "+q(saf));
            return s.getOutput()!=null&&!s.getOutput().trim().isEmpty();
        }
        FFprobeSession s=FFprobeKit.execute("-v error -select_streams v:0 -show_entries stream=codec_name,width,height -show_entries format=duration -of default=nw=1 "+q(saf));
        String o=s.getOutput();
        if(o==null||!o.contains("codec_name=amv")||!o.contains("width=320")||!o.contains("height=240"))return false;
        Matcher m=Pattern.compile("duration=([0-9.]+)").matcher(o);
        if(m.find()&&expected>3){
            double d=Double.parseDouble(m.group(1));
            return Math.abs(d-expected)<Math.max(5,expected*0.03);
        }
        return true;
    }

    private boolean verifySeek(Uri out,double duration){
        if(duration<20)return true;
        String saf=FFmpegKitConfig.getSafParameterForRead(this,out);
        double[] points={0.25,0.50,0.75};
        for(double f:points){
            double ss=Math.max(0,duration*f);
            FFmpegSession s=FFmpegKit.execute("-v error -ss "+fmt(ss)+" -i "+q(saf)+" -frames:v 1 -an -f null -");
            if(!ReturnCode.isSuccess(s.getReturnCode()))return false;
        }
        return true;
    }

    private Uri createInDownloads(String name,boolean image){
        if(Build.VERSION.SDK_INT<29)return null;
        try{
            name=uniqueDownloadName(name);
            ContentValues v=new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME,name);
            v.put(MediaStore.Downloads.MIME_TYPE,image?"image/jpeg":"application/octet-stream");
            if(Build.VERSION.SDK_INT>=29)v.put(MediaStore.Downloads.IS_PENDING,0);
            return getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,v);
        }catch(Exception e){return null;}
    }

    private String uniqueDownloadName(String name){
        if(Build.VERSION.SDK_INT<29)return name;
        String ext=name.toLowerCase(Locale.US).endsWith(".jpg")?".jpg":".amv";
        String base=name.substring(0,Math.max(0,name.length()-ext.length()));
        String candidate=name; int n=2;
        while(downloadNameExists(candidate)&&n<100){
            String suffix="-"+n++;
            int max=Math.max(1,16-suffix.length());
            candidate=base.substring(0,Math.min(max,base.length()))+suffix+ext;
        }
        return candidate;
    }
    private boolean downloadNameExists(String name){
        if(Build.VERSION.SDK_INT<29)return false;
        try(Cursor c=getContentResolver().query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,new String[]{MediaStore.Downloads._ID},MediaStore.Downloads.DISPLAY_NAME+"=?",new String[]{name},null)){
            return c!=null&&c.moveToFirst();
        }catch(Exception e){return false;}
    }

    private Uri createInTree(Uri tree,String name,boolean image){
        try{
            String docId=DocumentsContract.getTreeDocumentId(tree);
            Uri parent=DocumentsContract.buildDocumentUriUsingTree(tree,docId);
            return DocumentsContract.createDocument(getContentResolver(),parent,image?"image/jpeg":"application/octet-stream",name);
        }catch(Exception e){return null;}
    }

    private void update(int index,int total,int pct,double speed,long eta,String label,boolean done){
        int overall=total<=0?pct:(int)Math.min(100,((index+pct/100.0)/total)*100);
        String msg=done?label:label+" • "+pct+"%"+(speed>0?" • "+String.format(Locale.US,"%.1fx",speed):"")+(eta>0?" • "+eta(eta)+" left":"");
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFY_ID,notification(overall,msg));
        String tele="Speed: "+(speed>0?String.format(Locale.US,"%.1fx",speed):"—")+
                " • CPU: "+performanceLabel()+" • Thermal: "+thermalLabel()+" • Battery: "+batteryLabel();
        Intent i=new Intent(ACTION_PROGRESS);i.setPackage(getPackageName());
        i.putExtra("pct",pct);i.putExtra("overall",overall);i.putExtra("msg",msg);i.putExtra("done",done);
        i.putExtra("telemetry",tele);sendBroadcast(i);
    }

    private void startFg(String msg,int pct){
        Notification n=notification(pct,msg);
        if(Build.VERSION.SDK_INT>=35)startForeground(NOTIFY_ID,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING);
        else startForeground(NOTIFY_ID,n);
    }

    private Notification notification(int pct,String text){
        PendingIntent open=PendingIntent.getActivity(this,1,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Intent ci=new Intent(this,ConversionService.class);ci.setAction(ACTION_CANCEL);
        PendingIntent cancel=PendingIntent.getService(this,2,ci,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);
        b.setContentTitle("ClipAMV").setContentText(text).setSmallIcon(R.drawable.ic_stat_clipamv)
                .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(pct<100)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,"Cancel",cancel).build());
        if(pct>0&&pct<100)b.setProgress(100,pct,false);else if(pct==0)b.setProgress(0,0,true);
        return b.build();
    }

    private void notifyDone(String title,String msg){
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);
        PendingIntent open=PendingIntent.getActivity(this,1,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        b.setContentTitle(title).setContentText(msg).setStyle(new Notification.BigTextStyle().bigText(msg))
                .setContentIntent(open).setSmallIcon(R.drawable.ic_stat_clipamv).setAutoCancel(true);
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(101,b.build());
    }
    private String performanceLabel(){return performanceMode==2?"Maximum":performanceMode==1?"Faster":"Standard";}
    private String thermalLabel(){
        if(Build.VERSION.SDK_INT<29)return "N/A";
        PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);
        if(pm==null)return "N/A";
        int t=pm.getCurrentThermalStatus();
        if(t>=PowerManager.THERMAL_STATUS_SEVERE)return "SEVERE";
        if(t>=PowerManager.THERMAL_STATUS_MODERATE)return "MODERATE";
        if(t>=PowerManager.THERMAL_STATUS_LIGHT)return "WARM";
        return "NORMAL";
    }
    private String batteryLabel(){
        Intent i=registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if(i==null)return "N/A";
        int level=i.getIntExtra(BatteryManager.EXTRA_LEVEL,-1),scale=i.getIntExtra(BatteryManager.EXTRA_SCALE,-1);
        return level<0||scale<=0?"N/A":Math.round(100f*level/scale)+"%";
    }

    private void createChannel(){
        if(Build.VERSION.SDK_INT>=26){
            NotificationChannel c=new NotificationChannel(CHANNEL,"ClipAMV conversions",NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(c);
        }
    }

    private void acquireWake(){
        PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);
        wakeLock=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"ClipAMV:Convert");
        wakeLock.acquire(6*60*60*1000L);
    }
    private void releaseWake(){if(wakeLock!=null&&wakeLock.isHeld())wakeLock.release();wakeLock=null;}

    private long querySize(Uri uri){
        try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.SIZE},null,null,null)){
            if(c!=null&&c.moveToFirst()&&!c.isNull(0))return c.getLong(0);
        }catch(Exception ignored){}
        return 0;
    }
    private String displayName(Uri uri){
        try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){
            if(c!=null&&c.moveToFirst())return c.getString(0);
        }catch(Exception ignored){}
        return "media";
    }
    private void addHistory(String e){
        String stamp=new SimpleDateFormat("MM/dd HH:mm",Locale.US).format(new Date());
        SharedPreferences p=getSharedPreferences("history",MODE_PRIVATE);
        String old=p.getString("items","");
        String next=stamp+"  "+e+(old.isEmpty()?"":"\n"+old);
        String[] lines=next.split("\n");StringBuilder b=new StringBuilder();
        for(int i=0;i<Math.min(10,lines.length);i++){if(i>0)b.append("\n");b.append(lines[i]);}
        p.edit().putString("items",b.toString()).apply();
    }
    private static double rational(String x){if(x.contains("/")){String[]p=x.split("/");double a=Double.parseDouble(p[0]),b=Double.parseDouble(p[1]);return b==0?0:a/b;}return Double.parseDouble(x);}
    private static String q(String x){return "\""+x.replace("\"","\\\"")+"\"";}
    private static String fmt(double x){return String.format(Locale.US,"%.3f",x);}
    private static String eta(long s){long h=s/3600,m=(s%3600)/60,x=s%60;return h>0?String.format(Locale.US,"%d:%02d:%02d",h,m,x):String.format(Locale.US,"%d:%02d",m,x);}
    private static String human(long b){if(b<=0)return "?";double m=b/1048576.0;return m>=1024?String.format(Locale.US,"%.2f GB",m/1024):String.format(Locale.US,"%.0f MB",m);}
    @Override public IBinder onBind(Intent i){return null;}
    @Override public void onDestroy(){releaseWake();super.onDestroy();}
}
