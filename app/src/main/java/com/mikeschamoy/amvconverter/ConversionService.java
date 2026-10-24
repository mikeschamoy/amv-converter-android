package com.mikeschamoy.amvconverter;

import android.app.*;
import android.content.*;
import android.content.ContentValues;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import java.io.*;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.provider.MediaStore;

import com.arthenica.ffmpegkit.*;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.*;
import org.json.JSONArray;
import org.json.JSONObject;

public class ConversionService extends Service {
    public static final String ACTION_CANCEL="com.mikeschamoy.amvconverter.CANCEL";
    public static final String ACTION_PAUSE_AFTER_CURRENT="com.mikeschamoy.amvconverter.PAUSE_AFTER_CURRENT";
    public static final String ACTION_RESUME="com.mikeschamoy.amvconverter.RESUME";
    public static final String ACTION_PROGRESS="com.mikeschamoy.amvconverter.PROGRESS";
    public static final String EXTRA_INPUT="input",EXTRA_INPUTS="inputs",EXTRA_OUTPUT="output",EXTRA_TREE="tree";
    public static final String EXTRA_TYPE="type",EXTRA_MODE="mode",EXTRA_HEIGHT="height",EXTRA_FPS="fps";
    public static final String EXTRA_43="f43",EXTRA_PORTRAIT="portrait",EXTRA_AUTOBARS="autobars";
    public static final String EXTRA_START="start",EXTRA_END="end",EXTRA_TEST="test",EXTRA_DOWNLOADS="downloads",EXTRA_QUEUE_AUTO="queueAuto",EXTRA_AUTO_TYPE="autoType",EXTRA_PERFORMANCE="performance",EXTRA_FRAME_MODE="frameMode",EXTRA_PICTURE_MODE="pictureMode",EXTRA_SUBTITLE_MODE="subtitleMode",EXTRA_SUBTITLE_SIZE="subtitleSize";

    private static final String CHANNEL="conversion";
    private static final int NOTIFY_ID=100;
    // Process-local guard: persisted jobActive can survive a killed service/process.
    private static volatile boolean working=false;
    public static boolean isWorking(){return working;}
    private volatile boolean cancelRequested=false;
    private volatile boolean pauseRequested=false;
    private volatile boolean activeQueue=false;
    private volatile long currentSessionId=-1;
    private volatile int lastAuxiliaryStartId=0;
    private volatile int performanceMode=0;
    private volatile long lastProgressUpdateMs=0;
    private String lastFailureReason="";
    private PowerManager.WakeLock wakeLock;

    @Override public void onCreate(){super.onCreate();createChannel();}

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null){if(!working)stopSelfResult(startId);return START_NOT_STICKY;}
        if(ACTION_PAUSE_AFTER_CURRENT.equals(intent.getAction())){
            if(working&&activeQueue){
                lastAuxiliaryStartId=startId;
                pauseRequested=true;
                ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFY_ID,
                    notification(0,"Pause requested • finishing current file safely"));
            }else if(!working)stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        if(ACTION_CANCEL.equals(intent.getAction())){
            // Cancel only the currently running job; a separate paused queue stays saved.
            if(working)lastAuxiliaryStartId=startId;
            cancelRequested=true;
            if(currentSessionId>0)FFmpegKit.cancel(currentSessionId);
            if(!working)stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        if(working){lastAuxiliaryStartId=startId;return START_NOT_STICKY;}
        boolean resuming=ACTION_RESUME.equals(intent.getAction());
        if(resuming){
            startFg("Restoring paused queue…",0);
            Intent pending=readPausedJob();
            if(pending==null){
                stopForeground(true);
                notifyDone("ClipAMV • Nothing to resume","Select a new queue to start converting.");
                update(0,1,0,0,0,"No saved queue could be restored",true);
                stopSelfResult(startId);
                return START_NOT_STICKY;
            }
            intent=pending;
        }
        // Running an unrelated conversion must not discard a saved paused queue.
        if(resuming)getSharedPreferences("state",MODE_PRIVATE).edit()
            .remove("pausedJob").remove("pausedRemaining").apply();
        startFg("Preparing…",0);
        working=true;
        lastAuxiliaryStartId=0;
        cancelRequested=false;
        pauseRequested=false;
        activeQueue=intent.getBooleanExtra("wasQueue",false)||
            (intent.getStringArrayListExtra(EXTRA_INPUTS)!=null&&intent.getStringArrayListExtra(EXTRA_INPUTS).size()>1);
        performanceMode=Math.max(0,Math.min(2,intent.getIntExtra(EXTRA_PERFORMANCE,0)));
        lastProgressUpdateMs=0;

        getSharedPreferences("state",MODE_PRIVATE).edit().putBoolean("jobActive",true).putBoolean("activeQueue",activeQueue).apply();
        final Intent jobIntent=intent;
        new Thread(()->{
            try{acquireWake();runJobs(jobIntent,startId);}
            catch(Exception e){
                String reason="Unexpected error: "+e.getClass().getSimpleName()+
                    (e.getMessage()==null?"":": "+e.getMessage());
                if(reason.length()>160)reason=reason.substring(0,160);
                recordFailure(reason,android.util.Log.getStackTraceString(e));
                addHistory("✕ "+reason);
                finishJob(startId,"ClipAMV • Conversion failed",reason,0);
            }
        }).start();
        return START_NOT_STICKY;
    }

    private void finishJob(int startId,String title,String msg,int finalPercent){
        getSharedPreferences("state",MODE_PRIVATE).edit().putBoolean("jobActive",false).apply();
        working=false;
        releaseWake();
        stopForeground(true);
        try{notifyDone(title,msg);}catch(RuntimeException e){android.util.Log.w("ClipAMV","Notify failed",e);}
        try{update(0,1,finalPercent,0,0,title+" • "+msg,true);}
        catch(RuntimeException e){android.util.Log.e("ClipAMV","Broadcast failed",e);}
        stopSelfResult(Math.max(startId,lastAuxiliaryStartId));
    }

    private void runJobs(Intent intent,int startId){
        lastFailureReason="";
        final long jobStartMs=SystemClock.elapsedRealtime();
        recordFailure("No FFmpeg error recorded for current conversion", "");
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
        PresetEngine.FrameMode frame=PresetEngine.FrameMode.values()[Math.max(0,Math.min(PresetEngine.FrameMode.values().length-1,intent.getIntExtra(EXTRA_FRAME_MODE,2)))];
        boolean preserve43=intent.getBooleanExtra(EXTRA_43,false);
        PresetEngine.PortraitMode portrait=PresetEngine.PortraitMode.values()[intent.getIntExtra(EXTRA_PORTRAIT,0)];
        boolean autoBars=intent.getBooleanExtra(EXTRA_AUTOBARS,true);
        double start=intent.getDoubleExtra(EXTRA_START,0),end=intent.getDoubleExtra(EXTRA_END,0);
        boolean test=intent.getBooleanExtra(EXTRA_TEST,false);
        boolean downloads=intent.getBooleanExtra(EXTRA_DOWNLOADS,false);
        boolean queueAuto=intent.getBooleanExtra(EXTRA_QUEUE_AUTO,false);
        boolean autoType=intent.getBooleanExtra(EXTRA_AUTO_TYPE,false);
        int subtitleMode=Math.max(0,Math.min(2,intent.getIntExtra(EXTRA_SUBTITLE_MODE,SubtitleSupport.OFF)));
        int subtitleSize=Math.max(0,Math.min(1,intent.getIntExtra(EXTRA_SUBTITLE_SIZE,SubtitleSupport.LARGE)));

        int total=inputs.size(),fail=0,completed=0,processed=0,imagesCompleted=0;
        String lastName=""; long lastBytes=0,totalBytes=0;
        for(int i=0;i<total&&QueuePausePolicy.shouldStart(i,cancelRequested,pauseRequested);i++){
            processed=i+1;
            lastFailureReason="";
            Uri in=Uri.parse(inputs.get(i));
            String original=displayName(in);
            update(i,total,0,0,0,"Inspecting "+original,false);

            PresetEngine.SourceInfo info=inspect(in);
            if(info==null){fail++;lastFailureReason="Could not inspect "+original;
                recordFailure(lastFailureReason);addHistory("✕ "+original+" — inspect failed");continue;}

            PresetEngine.Crop crop=autoBars&&!info.image?detectBars(in,info):null;
            if(total==1&&intent.hasExtra("manualCropW")){crop=new PresetEngine.Crop(
                    intent.getIntExtra("manualCropW",0),intent.getIntExtra("manualCropH",0),
                    intent.getIntExtra("manualCropX",0),intent.getIntExtra("manualCropY",0));}
            PresetEngine.Type itemType=(queueAuto||autoType)?AutoSelection.choose(info,original):type;
            int itemHeight=height; // Explicit height is respected even in Auto Each File queues.
            int itemFps=fps; // Explicit FPS is respected even in Auto Each File queues.
            PresetEngine.PictureMode processing=picture;
            if(picture==PresetEngine.PictureMode.AUTO){
                PictureAnalysis.Result result=PictureAnalysis.analyze(this,in,info);
                processing=result.mode;
            }
            PresetEngine.Preset preset=PresetEngine.choose(info,itemType,mode,itemHeight,itemFps,preserve43,portrait,crop,frame,processing);

            String outName=FileNameCleaner.clean(original,itemType,info.image);
            // Never expose an empty AMV in Downloads while it is still encoding.
            // ACTION_CREATE_DOCUMENT has already created a destination; don't delete it on failure.
            final boolean chosenDocument=total==1&&singleOut!=null;
            Uri out=chosenDocument?Uri.parse(singleOut):null;
            if(!chosenDocument&&!downloads&&tree==null){
                fail++;lastFailureReason="Choose a writable output folder";
                recordFailure(lastFailureReason);addHistory("✕ "+outName+" — no destination");continue;
            }

            double available=info.durationSeconds>0?Math.max(0,info.durationSeconds-start):0;
            double requested=end>start&&end>0?end-start:available;
            double runtime=test?(available>0?Math.min(30,available):30):(available>0?Math.min(requested,available):requested);
            File stage=null;
            try{
                File root=getExternalFilesDir(Environment.DIRECTORY_MOVIES);
                if(root==null)root=getCacheDir();
                if(!root.exists()&&!root.mkdirs())throw new IOException("Temporary folder unavailable");
                long estimate=PresetEngine.estimateBytes(info,preset,runtime);
                StatFs disk=new StatFs(root.getAbsolutePath());
                long reserve=Math.max(8L*1024*1024,(long)(estimate*1.30));
                if(estimate>0&&disk.getAvailableBytes()<reserve)
                    throw new IOException("Not enough temporary storage. Need about "+human(reserve));
                stage=File.createTempFile("clipamv_stage_",info.image?".jpg":".amv",root);
                if(!convertOne(in,stage,info,preset,start,end,test,i,total,outName,runtime,subtitleMode,subtitleSize)){
                    fail++;continue;
                }
                if(!verify(stage,info.image,runtime)){
                    fail++;lastFailureReason="Generated file failed format/duration check: "+outName;
                    recordFailure(lastFailureReason);
                    addHistory("✕ "+outName+" — generated file invalid");
                    continue;
                }
                // AMV has no index: scan every video/audio pair and the final trailer
                // before publishing. A partial encode must never be marked complete.
                double checkedDuration=runtime;
                if(!info.image){
                    update(i,total,98,0,0,"Auditing complete AMV frame structure",false);
                    AMVIntegrity.Result audit=AMVIntegrity.inspect(stage,runtime);
                    checkedDuration=audit.seconds; // Actual packet duration, not unreliable source metadata.
                    android.util.Log.i("ClipAMV",audit.toString());
                }
                String seekProblem=info.image?null:verifySeek(stage,checkedDuration);
                if(seekProblem!=null){
                    fail++;
                    lastFailureReason="End-of-video seeking verification failed: "+seekProblem+
                        "; output was NOT published";
                    recordFailure(lastFailureReason);
                    addHistory("✕ "+outName+" — "+lastFailureReason);
                    continue;
                }
                if(cancelRequested)throw new IOException("Cancelled before publishing verified output");
                // Only create the visible destination after the local AMV passes validation.
                if(!chosenDocument){
                    out=downloads?createInDownloads(outName,info.image):
                        createInTree(Uri.parse(tree),outName,info.image);
                    if(out==null)throw new IOException("Could not create destination "+outName);
                }
                update(i,total,99,0,0,"Copying verified "+outName+" to destination",false);
                long bytes=copyToOutput(stage,out);
                if(downloads&&!chosenDocument)publishDownload(out);
                completed++;if(info.image)imagesCompleted++;lastName=displayName(out);lastBytes=bytes;totalBytes+=bytes;
                addHistory("✓ "+outName+" — "+human(bytes)+
                    " • full structure/seek checks passed");
            }catch(Exception e){
                fail++;
                lastFailureReason="Output: "+e.getClass().getSimpleName()+
                    (e.getMessage()==null?"":": "+e.getMessage());
                recordFailure(lastFailureReason);
                addHistory("✕ "+outName+" — "+lastFailureReason);
                if(!chosenDocument&&out!=null)deleteOutput(out);
            }finally{
                if(stage!=null&&stage.exists())stage.delete();
            }

        }

        boolean paused=QueuePausePolicy.hasRemaining(total,processed,cancelRequested,pauseRequested);
        if(paused){
            ArrayList<String> remaining=QueuePausePolicy.remaining(inputs,processed);
            try{savePausedJob(intent,remaining);}catch(Exception ex){
                paused=false;fail++;
                lastFailureReason="Could not save paused queue: "+ex.getMessage();
                recordFailure(lastFailureReason);
            }
        }
        String title,msg;
        if(paused){
            title="ClipAMV • Queue paused";
            msg="Saved "+completed+" • "+(total-processed)+" remaining • Tap Resume in app";
        }else if(cancelRequested){
            title="ClipAMV • Cancelled";
            msg="Finished "+completed+"/"+total+" files before cancellation";
        }else if(total==1 && completed==1){
            title="✓ "+lastName+" completed";
            msg=human(lastBytes)+" • Ready";
        }else if(total==1){
            title="ClipAMV • Conversion failed";
            msg=lastFailureReason.isEmpty()?"No output was completed":lastFailureReason;
        }else{
            title=fail==0?"✓ Queue complete • "+completed+"/"+total:"Queue finished • "+completed+"/"+total;
            msg="Last: "+(lastName.isEmpty()?"None":lastName)+" • "+human(totalBytes)+(fail>0?" • "+fail+" failed":"");
        }
        String display=lastName.replaceFirst("(?i)\\.(amv|jpg)$","");
        String destination=downloads?"Downloads":(tree!=null?"Selected folder":"Chosen document");
        String result=(paused?"QUEUE PAUSED":cancelRequested?"CANCELLED":fail>0||completed==0?"FINISHED WITH ERRORS":"CONVERSION COMPLETE")+
            "\nFiles: "+completed+" of "+total+" saved"+(fail>0?" • "+fail+" failed":"")+
            (paused?"\nRemaining: "+(total-processed)+" • Resume from the app":"")+
            "\nElapsed: "+eta(Math.max(0,(SystemClock.elapsedRealtime()-jobStartMs)/1000))+
            (completed>0?"\nLast: "+display+" • "+human(lastBytes)+"\nTotal: "+human(totalBytes):"")+
            "\nSaved to: "+destination+
            (completed>0?"\nChecks: "+(imagesCompleted==completed?"Image format validated":imagesCompleted>0?"AMV structure/seek and image format validated":"AMV structure and seek verified before publishing"):"")+
            (fail>0?"\nIssue: "+lastFailureReason:"");
        getSharedPreferences("state",MODE_PRIVATE).edit().putString("lastResult",result).apply();
        int finalPercent=total>0?(int)Math.round(100.0*completed/total):0;
        finishJob(startId,title,msg,finalPercent);
    }

    private boolean convertOne(Uri in,File stage,PresetEngine.SourceInfo info,PresetEngine.Preset preset,
                               double start,double end,boolean test,int index,int total,String outName,double runtime,int subtitleMode,int subtitleSize){
        String inSaf;
        try{inSaf=FFmpegKitConfig.getSafParameterForRead(this,in);}
        catch(Exception e){
            lastFailureReason="Unable to open source: "+e.getClass().getSimpleName();
            recordFailure(lastFailureReason);
            addHistory("✕ "+outName+" — "+lastFailureReason);
            return false;
        }
        File subtitle=null;
        try {
            if(!info.image && subtitleMode!=SubtitleSupport.OFF){
                subtitle=SubtitleSupport.extract(this,in,subtitleMode,start,runtime);
                if(subtitle==null){
                    android.util.Log.i("ClipAMV","No compatible text subtitle track selected for "+outName);
                }
            }
        } catch(IOException e){
            lastFailureReason="Subtitle: "+e.getMessage();
            recordFailure(lastFailureReason);
            addHistory("✕ "+outName+" — "+lastFailureReason);
            return false;
        }
        PresetEngine.Preset encodePreset=preset;
        if(subtitle!=null){
            encodePreset=new PresetEngine.Preset();
            encodePreset.filter=SubtitleSupport.withSubtitles(preset.filter,subtitle,subtitleSize);
            encodePreset.outputFps=preset.outputFps;
            encodePreset.blockSize=preset.blockSize;
            encodePreset.q=preset.q;encodePreset.qmin=preset.qmin;
            encodePreset.audioPresent=preset.audioPresent;
        }
        String cmd=info.image?PresetEngine.buildImageCommand(inSaf,stage.getAbsolutePath(),preset):
            PresetEngine.buildVideoCommand(inSaf,stage.getAbsolutePath(),encodePreset,start,end,test,performanceMode);
        CountDownLatch latch=new CountDownLatch(1);
        final long encoderStartMs=SystemClock.elapsedRealtime();
        final AtomicLong encodedPositionMs=new AtomicLong(0);
        final boolean[] ok={false};
        final StringBuilder ffmpegErrors=new StringBuilder();
        FFmpegSession session=FFmpegKit.executeAsync(cmd,done->{
            ok[0]=ReturnCode.isSuccess(done.getReturnCode());latch.countDown();
        },log->{
            synchronized(ffmpegErrors){
                ffmpegErrors.append(log.getMessage()).append('\n');
                if(ffmpegErrors.length()>16000)ffmpegErrors.delete(0,ffmpegErrors.length()-16000);
            }
        },stats->{
            if(cancelRequested)return;
            encodedPositionMs.updateAndGet(prev->Math.max(prev,(long)stats.getTime()));
            long now=SystemClock.elapsedRealtime();
            long interval=performanceMode==2?1600:performanceMode==1?900:400;
            if(now-lastProgressUpdateMs<interval)return;
            lastProgressUpdateMs=now;
            long elapsed=(long)stats.getTime();
            int pct=runtime>0?(int)Math.min(98,(elapsed/1000.0)*100.0/runtime):0;
            double speed=stats.getSpeed();
            long eta=speed>0?Math.max(0,(long)((runtime-elapsed/1000.0)/speed)):0;
            update(index,total,pct,speed,eta,(index+1)+"/"+total+" • "+outName,false);
        });
        currentSessionId=session.getSessionId();
        try{latch.await();}catch(InterruptedException e){Thread.currentThread().interrupt();FFmpegKit.cancel(session.getSessionId());}
        finally{if(subtitle!=null)subtitle.delete();}
        currentSessionId=-1;
        long encoderElapsedMs=Math.max(1,SystemClock.elapsedRealtime()-encoderStartMs);
        double encodedSeconds=encodedPositionMs.get()>0?encodedPositionMs.get()/1000.0:runtime;
        if(runtime>0)encodedSeconds=Math.min(encodedSeconds,runtime);
        float measuredSpeed=(float)(Math.max(0,encodedSeconds)*1000.0/encoderElapsedMs);
        getSharedPreferences("state",MODE_PRIVATE).edit()
            .putFloat("lastEncodeSpeed",measuredSpeed)
            .putLong("lastEncodeWallMs",encoderElapsedMs)
            .putString("lastEncodeFilter",encodePreset.filter)
            .putInt("lastEncodePerformance",performanceMode)
            .apply();
        if(!ok[0]||!stage.isFile()||stage.length()==0){
            String details;
            synchronized(ffmpegErrors){details=ffmpegErrors.toString();}
            String code=session.getReturnCode()==null?"?":session.getReturnCode().toString();
            lastFailureReason=describeFfmpegFailure(details,code);
            recordFailure(lastFailureReason,details);
            addHistory("✕ "+outName+" — "+lastFailureReason);
            return false;
        }
        return true;
    }

    // Pause is a checkpoint between complete, verified files. Never preserve partial encodes.
    private static final String[] QUEUE_INTS={EXTRA_TYPE,EXTRA_MODE,EXTRA_HEIGHT,EXTRA_FPS,EXTRA_PORTRAIT,
        EXTRA_PERFORMANCE,EXTRA_FRAME_MODE,EXTRA_PICTURE_MODE,EXTRA_SUBTITLE_MODE,EXTRA_SUBTITLE_SIZE};
    private static final String[] QUEUE_BOOLS={EXTRA_43,EXTRA_AUTOBARS,EXTRA_DOWNLOADS,
        EXTRA_QUEUE_AUTO,EXTRA_AUTO_TYPE,"wasQueue"};
    private static final String[] QUEUE_DOUBLES={EXTRA_START,EXTRA_END};
    private static final String[] QUEUE_STRINGS={EXTRA_TREE};
    private void savePausedJob(Intent original,ArrayList<String> remaining) throws Exception{
        JSONObject obj=new JSONObject();JSONArray names=new JSONArray();
        for(String name:remaining)names.put(name);
        obj.put("inputs",names);
        for(String k:QUEUE_INTS)obj.put(k,original.getIntExtra(k,0));
        for(String k:QUEUE_BOOLS)obj.put(k,original.getBooleanExtra(k,false));
        for(String k:QUEUE_DOUBLES)obj.put(k,original.getDoubleExtra(k,0));
        for(String k:QUEUE_STRINGS)if(original.hasExtra(k))obj.put(k,original.getStringExtra(k));
        // commit makes the resume control durable before the paused notification is sent.
        boolean saved=getSharedPreferences("state",MODE_PRIVATE).edit()
            .putString("pausedJob",obj.toString()).putInt("pausedRemaining",remaining.size()).commit();
        if(!saved)throw new IOException("Checkpoint storage unavailable");
    }
    private Intent readPausedJob(){
        String raw=getSharedPreferences("state",MODE_PRIVATE).getString("pausedJob","");
        if(raw.isEmpty())return null;
        try{
            JSONObject obj=new JSONObject(raw);JSONArray names=obj.getJSONArray("inputs");
            if(names.length()==0)return null;
            Intent restored=new Intent(this,ConversionService.class);
            ArrayList<String> inputs=new ArrayList<>();
            for(int i=0;i<names.length();i++)inputs.add(names.getString(i));
            restored.putStringArrayListExtra(EXTRA_INPUTS,inputs);
            for(String k:QUEUE_INTS)restored.putExtra(k,obj.getInt(k));
            for(String k:QUEUE_BOOLS)restored.putExtra(k,obj.getBoolean(k));
            for(String k:QUEUE_DOUBLES)restored.putExtra(k,obj.getDouble(k));
            for(String k:QUEUE_STRINGS)if(obj.has(k))restored.putExtra(k,obj.getString(k));
            return restored;
        }catch(Exception e){android.util.Log.e("ClipAMV","Unreadable paused queue",e);return null;}
    }

    private long copyToOutput(File source,Uri output) throws IOException {
        // Request truncation when possible; some SAF providers accept only
        // basic write mode, so fall back gracefully.
        OutputStream raw;
        try{raw=getContentResolver().openOutputStream(output,"rwt");}
        catch(Exception unsupported){raw=getContentResolver().openOutputStream(output,"w");}
        if(raw==null)throw new IOException("Destination refused an output stream");
        long copied=0;
        try(InputStream input=new BufferedInputStream(new FileInputStream(source),128*1024);
            OutputStream result=new BufferedOutputStream(raw,128*1024)){
            byte[] buffer=new byte[128*1024];int count;
            while((count=input.read(buffer))!=-1){
                if(cancelRequested)throw new IOException("Transfer cancelled");
                if(count>0){result.write(buffer,0,count);copied+=count;}
            }
            result.flush();
        }
        if(copied!=source.length())throw new IOException("Copy incomplete: "+copied+"/"+source.length());
        long written=querySize(output);
        if(written>0&&written!=copied)throw new IOException("Destination size mismatch: "+written+"/"+copied);
        return copied;
    }
    private void deleteOutput(Uri output){
        if(output==null)return;
        try{DocumentsContract.deleteDocument(getContentResolver(),output);}
        catch(Exception ignored){
            try{getContentResolver().delete(output,null,null);}catch(Exception ignoredAgain){}
        }
    }
    private void recordFailure(String reason){recordFailure(reason,reason);}
    private void recordFailure(String reason,String log){
        getSharedPreferences("state",MODE_PRIVATE).edit()
            .putString("lastFfmpegReason",reason).putString("lastFfmpegLog",log).apply();
    }
    private static String describeFfmpegFailure(String log,String code){
        String reason="";int best=-1;
        for(String raw:log.split("\\r?\\n")){
            String line=raw.trim();
            if(line.isEmpty())continue;
            String low=line.toLowerCase(Locale.US);
            int score=low.contains("unknown encoder")||low.contains("unable to choose an output format")||low.contains("error parsing options")?10:
                low.contains("invalid argument")||low.contains("no such filter")||low.contains("unsupported")?8:
                low.contains("could not")||low.contains("failed to")||low.contains("error while")?7:
                low.contains("error")||low.contains("failed")?5:0;
            if(score>best&&score>0){reason=line;best=score;}
        }
        if(reason.isEmpty())reason="FFmpeg failed (code "+code+")";
        reason=reason.replaceAll("saf:[^ ]+","[media]")
                     .replaceAll("/storage/[^ ]+","[file]");
        return reason.length()>160?reason.substring(0,157)+"…":reason;
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
            String currentType="",pendingCodec="";
            for(String raw:out.split("\\r?\\n")){
                String line=raw.trim();
                if(line.startsWith("index=")){currentType="";pendingCodec="";}
                else if(line.startsWith("codec_type=")){
                    currentType=line.substring(11);
                    if(!pendingCodec.isEmpty()){
                        if("video".equals(currentType))s.videoCodec=pendingCodec;
                        else if("audio".equals(currentType)){s.audioCodec=pendingCodec;s.audioPresent=true;}
                    }
                }else if(line.startsWith("codec_name=")){
                    pendingCodec=line.substring(11);
                    if("video".equals(currentType))s.videoCodec=pendingCodec;
                    else if("audio".equals(currentType)){s.audioCodec=pendingCodec;s.audioPresent=true;}
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
        // Take two independent, short samples. Do NOT adopt the last crop from a
        // dark transient scene: both samples must agree before cropping.
        PresetEngine.Crop first=detectBarsSample(uri,info,Math.max(0,info.durationSeconds*0.25));
        if(first==null)return null;
        if(info.durationSeconds<=5)return first;
        PresetEngine.Crop second=detectBarsSample(uri,info,Math.max(0,info.durationSeconds*0.70));
        if(second==null)return null;
        if(Math.abs(first.w-second.w)>16||Math.abs(first.h-second.h)>16||
           Math.abs(first.x-second.x)>16||Math.abs(first.y-second.y)>16)return null;
        return first;
    }

    private PresetEngine.Crop detectBarsSample(Uri uri,PresetEngine.SourceInfo info,double at){
        try{
            String saf=FFmpegKitConfig.getSafParameterForRead(this,uri);
            FFmpegSession session=FFmpegKit.execute("-hide_banner -ss "+fmt(at)+" -i "+q(saf)+
                " -frames:v 25 -vf cropdetect=limit=24:round=16:reset=0 -an -f null -");
            if(!ReturnCode.isSuccess(session.getReturnCode()))return null;
            String all=session.getAllLogsAsString();
            if(all==null)return null;
            Matcher m=Pattern.compile("crop=(\\d+):(\\d+):(\\d+):(\\d+)").matcher(all);
            LinkedHashMap<String,Integer> votes=new LinkedHashMap<>();
            int total=0;
            while(m.find()){
                String key=m.group(1)+":"+m.group(2)+":"+m.group(3)+":"+m.group(4);
                votes.put(key,votes.getOrDefault(key,0)+1);total++;
            }
            String best=null;int most=0;
            for(Map.Entry<String,Integer> x:votes.entrySet())if(x.getValue()>most){best=x.getKey();most=x.getValue();}
            if(best==null||most<Math.max(5,(int)Math.ceil(total*.65)))return null;
            String[] p=best.split(":");
            PresetEngine.Crop result=new PresetEngine.Crop(Integer.parseInt(p[0]),Integer.parseInt(p[1]),Integer.parseInt(p[2]),Integer.parseInt(p[3]));
            return PresetEngine.isMeaningfulCrop(info,result)?result:null;
        }catch(Exception ignored){return null;}
    }

    private boolean verify(File stage,boolean image,double expected){
        if(!stage.isFile()||stage.length()==0)return false;
        String path=q(stage.getAbsolutePath());
        if(image){
            FFprobeSession s=FFprobeKit.execute("-v error -select_streams v:0 -show_entries stream=width,height -of csv=p=0 "+path);
            return s.getOutput()!=null&&s.getOutput().contains("320");
        }
        FFprobeSession s=FFprobeKit.execute("-v error -show_entries stream=codec_name,codec_type,width,height,sample_rate,channels "+
            "-show_entries format=duration -of default=nw=1 "+path);
        String result=s.getOutput();
        if(result==null||!result.contains("codec_name=amv")||!result.contains("width=320")||
           !result.contains("height=240")||!result.contains("codec_name=adpcm_ima_amv")||
           !result.contains("sample_rate=22050")||!result.contains("channels=1"))return false;
        Matcher matcher=Pattern.compile("duration=([0-9.]+)").matcher(result);
        if(matcher.find()&&expected>3){
            double duration=Double.parseDouble(matcher.group(1));
            return Math.abs(duration-expected)<Math.max(5,expected*0.03);
        }
        return true;
    }

    /**
     * Every probe must decode an actual frame.  FFmpeg sometimes returns exit
     * code 0 while producing ZERO frames for a seek near/beyond EOF.  Saving
     * framecrc to a temporary file avoids relying on FFmpegKit stdout capture.
     *
     * This verifies our file; it cannot emulate proprietary player firmware.
     */
    private String verifySeek(File stage,double duration){
        if(duration<=1)return "encoded duration too short for seeking test";
        String path=q(stage.getAbsolutePath());
        double[] marks=duration>=20?
            new double[]{0.10,0.50,0.75,0.90,0.95,0.98}:
            new double[]{0.20,0.70,0.90};
        ArrayList<Double> positions=new ArrayList<>();
        for(double fraction:marks){
            double point=Math.max(0,Math.min(duration*fraction,duration-0.75));
            if(positions.isEmpty()||Math.abs(positions.get(positions.size()-1)-point)>.3)
                positions.add(point);
        }
        double finalPoint=Math.max(0,duration-1.5);
        if(positions.isEmpty()||Math.abs(positions.get(positions.size()-1)-finalPoint)>.3)
            positions.add(finalPoint);
        for(double position:positions){
            File crc=null;
            try{
                crc=File.createTempFile("clipamv_seek_",".crc",getCacheDir());
                FFmpegSession session=FFmpegKit.execute("-y -nostdin -v error -ss "+fmt(position)+
                    " -i "+path+" -map 0:v:0 -frames:v 1 -an -f framecrc "+q(crc.getAbsolutePath()));
                if(!ReturnCode.isSuccess(session.getReturnCode()))
                    return "FFmpeg seek failed at "+fmt(position)+"s of "+fmt(duration)+"s";
                boolean actualFrame=false;
                try(BufferedReader input=new BufferedReader(new FileReader(crc))){
                    String line;
                    while((line=input.readLine())!=null){
                        if(line.matches("^0,\\s*[-0-9]+,.*")){
                            actualFrame=true;break;
                        }
                    }
                }
                if(!actualFrame)return "zero video frames at "+fmt(position)+"s of "+fmt(duration)+"s";
            }catch(IOException e){
                return "could not inspect seek result: "+e.getMessage();
            }finally{
                if(crc!=null)crc.delete();
            }
        }
        return null;
    }

    private Uri createInDownloads(String name,boolean image){
        if(Build.VERSION.SDK_INT<29)return null;
        try{
            name=uniqueDownloadName(name);
            ContentValues v=new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME,name);
            v.put(MediaStore.Downloads.MIME_TYPE,image?"image/jpeg":"application/octet-stream");
            if(Build.VERSION.SDK_INT>=29)v.put(MediaStore.Downloads.IS_PENDING,1);
            return getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,v);
        }catch(Exception e){return null;}
    }

    private void publishDownload(Uri uri) throws IOException {
        if(Build.VERSION.SDK_INT<29)return;
        ContentValues values=new ContentValues();
        values.put(MediaStore.Downloads.IS_PENDING,0);
        try{
            if(getContentResolver().update(uri,values,null,null)!=1)
                throw new IOException("Could not publish the completed download");
        }catch(SecurityException e){throw new IOException("Download publish permission denied",e);}
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
            String safeName=uniqueTreeName(tree,docId,name);
            return DocumentsContract.createDocument(getContentResolver(),parent,image?"image/jpeg":"application/octet-stream",safeName);
        }catch(Exception e){return null;}
    }

    private String uniqueTreeName(Uri tree,String docId,String name){
        String ext=name.toLowerCase(Locale.US).endsWith(".jpg")?".jpg":".amv";
        String base=name.substring(0,Math.max(0,name.length()-ext.length()));
        HashSet<String> existing=new HashSet<>();
        Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,docId);
        try(Cursor cursor=getContentResolver().query(children,
            new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},null,null,null)){
            if(cursor!=null)while(cursor.moveToNext()){
                String item=cursor.getString(0);
                if(item!=null)existing.add(item.toLowerCase(Locale.US));
            }
        }catch(Exception err){
            // Storage providers may not permit listing; createDocument normally chooses a unique name.
            android.util.Log.w("ClipAMV","Could not enumerate folder for duplicates",err);
            return name;
        }
        if(!existing.contains(name.toLowerCase(Locale.US)))return name;
        for(int n=2;n<=999;n++){
            String suffix="-"+n;
            String candidate=base.substring(0,Math.min(base.length(),Math.max(1,16-suffix.length())))+suffix+ext;
            if(!existing.contains(candidate.toLowerCase(Locale.US)))return candidate;
        }
        return name;
    }

    private void update(int index,int total,int pct,double speed,long eta,String label,boolean done){
        int overall=total<=0?pct:(int)Math.min(100,((index+pct/100.0)/total)*100);
        String msg=done?label:label+" • "+pct+"%"+(speed>0?" • "+String.format(Locale.US,"%.1fx",speed):"")+(eta>0?" • "+eta(eta)+" left":"");
        if(!done)((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFY_ID,notification(overall,msg));
        String tele="Speed: "+(speed>0?String.format(Locale.US,"%.1fx",speed):"—")+
                " • CPU: "+performanceLabel()+" • Thermal: "+thermalLabel()+" • Battery: "+batteryLabel();
        Intent i=new Intent(ACTION_PROGRESS);i.setPackage(getPackageName());
        i.putExtra("pct",pct);i.putExtra("overall",overall);i.putExtra("msg",msg);i.putExtra("done",done);
        i.putExtra("telemetry",tele);i.putExtra("isQueue",activeQueue);i.putExtra("pauseRequested",pauseRequested);sendBroadcast(i);
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
        Intent pi=new Intent(this,ConversionService.class);pi.setAction(ACTION_PAUSE_AFTER_CURRENT);
        PendingIntent pause=PendingIntent.getService(this,3,pi,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);
        b.setContentTitle("ClipAMV").setContentText(text).setSmallIcon(R.drawable.ic_stat_clipamv)
                .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(pct<100)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,"Cancel",cancel).build());
        if(activeQueue&&!pauseRequested)
            b.addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause,"Pause after file",pause).build());
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
    @Override public void onDestroy(){
        working=false;
        getSharedPreferences("state",MODE_PRIVATE).edit().putBoolean("jobActive",false).apply();
        releaseWake();super.onDestroy();
    }
}
