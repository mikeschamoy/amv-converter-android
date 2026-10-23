package com.mikeschamoy.amvconverter;

import android.app.Activity;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/**
 * Tests ClipAMV's ACTUAL Android ConversionService / FFmpegKit, not a PC ffmpeg
 * recreation of its presets. Intentional gaps: MP3 hardware calibration,
 * handset thermals, some picker UI flows, and subjective picture quality.
 */
@RunWith(AndroidJUnit4.class)
public class ClipAMVDeviceMatrixTest {
    private final Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final Context fixtures = InstrumentationRegistry.getInstrumentation().getContext();
    private final Map<String,Uri> imported = new HashMap<>();
    private final List<String[]> report = new ArrayList<>();

    private static void copy(InputStream in,OutputStream out)throws IOException{
        try(InputStream i=in;OutputStream o=out){byte[] b=new byte[65536];int n;
            while((n=i.read(b))!=-1)if(n>0)o.write(b,0,n);o.flush();}
    }
    private Uri importSource(String name)throws Exception{
        if(imported.containsKey(name))return imported.get(name);
        String mime=name.endsWith(".png")?"image/png":name.endsWith(".mkv")?"video/x-matroska":"video/mp4";
        ContentValues v=new ContentValues();
        v.put(MediaStore.Downloads.DISPLAY_NAME,"QA_IN_"+name);
        v.put(MediaStore.Downloads.MIME_TYPE,mime);
        v.put(MediaStore.Downloads.IS_PENDING,1);
        Uri uri=app.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,v);
        if(uri==null)throw new IOException("MediaStore source insert failed: "+name);
        try { copy(fixtures.getAssets().open("hosts/"+name),app.getContentResolver().openOutputStream(uri)); }
        catch(Exception e){app.getContentResolver().delete(uri,null,null);throw e;}
        ContentValues publish=new ContentValues();publish.put(MediaStore.Downloads.IS_PENDING,0);
        app.getContentResolver().update(uri,publish,null,null);
        imported.put(name,uri);return uri;
    }
    private Map<String,Uri> outputs()throws Exception{
        Map<String,Uri> m=new HashMap<>();
        Uri base=MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        try(Cursor c=app.getContentResolver().query(base,new String[]{MediaStore.Downloads._ID,MediaStore.Downloads.DISPLAY_NAME},null,null,null)){
            if(c==null)throw new IOException("Download query returned null");
            while(c.moveToNext()){
                String n=c.getString(1);
                if(n!=null&&(n.toLowerCase(Locale.US).endsWith(".amv")||n.toLowerCase(Locale.US).endsWith(".jpg"))){
                    m.put(n,ContentUris.withAppendedId(base,c.getLong(0)));
                }
            }
        }return m;
    }
    private static String csv(String s){return '"'+s.replace("\"","\"\"").replace("\r"," ").replace("\n"," ")+'"';}
    private void record(String id,String status,String detail){
        System.out.println("CLIPAMV_QA "+id+" "+status+" "+detail);
        report.add(new String[]{id,status,detail});
        writeReport();
    }
    private void writeReport(){
        try{
            File f=new File(app.getFilesDir(),"ClipAMV-emulator-matrix.csv");
            try(PrintWriter w=new PrintWriter(new FileWriter(f))){
                w.println("test_id,result,details");
                for(String[] row:report)w.println(csv(row[0])+","+csv(row[1])+","+csv(row[2]));
            }
        }catch(Exception ignored){}
    }
    private int type(String s){
        switch(s){case "Movie":return 1;case "Cartoon":return 2;case "Portrait":return 3;default:return 0;}
    }
    private int quality(String s){return "Space Saver".equals(s)?0:"Quality".equals(s)?2:1;}
    private int perf(String s){return "Faster".equals(s)?1:"Maximum".equals(s)?2:0;}
    private int picture(String s){return "Sharp".equals(s)?1:"Smooth".equals(s)?2:0;}
    private static int number(String s){try{return Integer.parseInt(s);}catch(Exception e){return 0;}}
    private Intent makeIntent(JSONObject m)throws Exception{
        Intent i=new Intent(app,ConversionService.class);
        i.putExtra(ConversionService.EXTRA_TYPE,type(m.optString("content")));
        i.putExtra(ConversionService.EXTRA_MODE,quality(m.optString("quality")));
        i.putExtra(ConversionService.EXTRA_PERFORMANCE,perf(m.optString("performance")));
        i.putExtra(ConversionService.EXTRA_PICTURE_MODE,picture(m.optString("picture")));
        String requested=m.optString("framing", "Auto").trim().toLowerCase(Locale.US);
        PresetEngine.FrameMode framing=requested.startsWith("fill")?PresetEngine.FrameMode.FILL:
            requested.startsWith("fit")?PresetEngine.FrameMode.FIT:
            requested.startsWith("balanc")?PresetEngine.FrameMode.BALANCED:PresetEngine.FrameMode.AUTO;
        i.putExtra(ConversionService.EXTRA_FRAME_MODE,framing.ordinal());
        i.putExtra(ConversionService.EXTRA_FPS,number(m.optString("fps")));
        i.putExtra(ConversionService.EXTRA_HEIGHT,number(m.optString("height")));
        i.putExtra(ConversionService.EXTRA_43,"T06".equals(m.optString("test_id")));
        i.putExtra(ConversionService.EXTRA_PORTRAIT,0);
        i.putExtra(ConversionService.EXTRA_AUTOBARS,!"T20".equals(m.optString("test_id")));
        i.putExtra(ConversionService.EXTRA_DOWNLOADS,true);
        i.putExtra(ConversionService.EXTRA_TEST,false);
        if("T32".equals(m.optString("test_id"))){
            i.putExtra(ConversionService.EXTRA_START,5.0);
            i.putExtra(ConversionService.EXTRA_END,10.0);
        }
        String id=m.optString("test_id");
        if(id.equals("T33")||id.equals("T34")){
            ArrayList<String> inputs=new ArrayList<>();
            for(String n:new String[]{"01_YT_WIDE_16x9.mp4","04_FUNKY_3x2.mp4","13_VIDEO_WITHOUT_AUDIO.mp4"})
                inputs.add(importSource(n).toString());
            i.putStringArrayListExtra(ConversionService.EXTRA_INPUTS,inputs);
            i.putExtra(ConversionService.EXTRA_QUEUE_AUTO,id.equals("T33"));
        }else{
            i.putExtra(ConversionService.EXTRA_INPUT,importSource(m.optString("source_file")).toString());
        }
        return i;
    }
    private void checkNewFiles(Map<String,Uri> before,String id)throws Exception{
        Map<String,Uri> after=outputs();
        after.keySet().removeAll(before.keySet());
        int expected=id.equals("T33")||id.equals("T34")?3:1;
        if(after.size()!=expected)throw new AssertionError("Expected "+expected+" new outputs, found "+after.size()+"; "+after.keySet());
        for(Map.Entry<String,Uri> entry:after.entrySet()){
            boolean image=entry.getKey().toLowerCase(Locale.US).endsWith(".jpg");
            File f=File.createTempFile("clipamv_matrix_",image?".jpg":".amv",app.getCacheDir());
            try{
                copy(app.getContentResolver().openInputStream(entry.getValue()),new FileOutputStream(f));
                if(f.length()<1000)throw new AssertionError("Output is nearly empty: "+entry.getKey());
                if(!image){
                    AMVIntegrity.Result audit=AMVIntegrity.inspect(f,0);
                    if(audit.fps!=15&&audit.fps!=25&&audit.fps!=30)throw new AssertionError("Unexpected fps: "+audit.fps);
                    double expectedSeconds="T32".equals(id)?5.0:"SUB_FORCED".equals(id)?9.0:15.0;
                    if(Math.abs(audit.seconds-expectedSeconds)>3.5)throw new AssertionError("Duration "+audit.seconds+" expected "+expectedSeconds);
                    if(audit.videoFrames!=audit.audioFrames)throw new AssertionError("Video/audio pairing mismatch");
                }
            }finally{f.delete();}
        }
    }
    private String startAndWait(Intent i,long seconds)throws Exception{
        CountDownLatch done=new CountDownLatch(1);
        final String[] finalMessage={""};
        BroadcastReceiver listener=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent event){
            if(event.getBooleanExtra("done",false)){
                finalMessage[0]=event.getStringExtra("msg");done.countDown();
            }
        }};
        app.registerReceiver(listener,new IntentFilter(ConversionService.ACTION_PROGRESS));
        try{
            if(Build.VERSION.SDK_INT>=26)app.startForegroundService(i);else app.startService(i);
            if(!done.await(seconds,TimeUnit.SECONDS))throw new AssertionError("Conversion timed out after "+seconds+" seconds");
            if(finalMessage[0]==null)throw new AssertionError("Missing completion broadcast");
            if(finalMessage[0].toLowerCase(Locale.US).contains("failed"))
                throw new AssertionError(finalMessage[0]+" / "+app.getSharedPreferences("state",0).getString("lastFfmpegReason",""));
            return finalMessage[0];
        }finally{app.unregisterReceiver(listener);}
    }
    @Test public void checkRapidSequentialForegroundStarts()throws Exception {
        Uri source=importSource("01_YT_WIDE_16x9.mp4");
        for(int n=0;n<2;n++){
            Intent i=new Intent(app,ConversionService.class);
            i.putExtra(ConversionService.EXTRA_INPUT,source.toString());
            i.putExtra(ConversionService.EXTRA_TYPE,PresetEngine.Type.YOUTUBE.ordinal());
            i.putExtra(ConversionService.EXTRA_MODE,PresetEngine.Mode.STANDARD.ordinal());
            i.putExtra(ConversionService.EXTRA_FRAME_MODE,PresetEngine.FrameMode.AUTO.ordinal());
            i.putExtra(ConversionService.EXTRA_PICTURE_MODE,PresetEngine.PictureMode.SHARP.ordinal());
            i.putExtra(ConversionService.EXTRA_HEIGHT,208);
            i.putExtra(ConversionService.EXTRA_FPS,25);
            i.putExtra(ConversionService.EXTRA_AUTOBARS,false);
            i.putExtra(ConversionService.EXTRA_DOWNLOADS,true);
            i.putExtra(ConversionService.EXTRA_START,2.0*n);
            i.putExtra(ConversionService.EXTRA_END,2.0*n+2.0);
            startAndWait(i,120);
            assertFalse(ConversionService.isWorking());
        }
    }
    /** Real Android test: prove Full-tier libass actually burns an embedded forced SRT. */
    @Test public void checkForcedSubtitleBurnIn()throws Exception {
        Map<String,Uri> before=outputs();
        Intent i=new Intent(app,ConversionService.class);
        i.putExtra(ConversionService.EXTRA_TYPE,PresetEngine.Type.YOUTUBE.ordinal());
        i.putExtra(ConversionService.EXTRA_MODE,PresetEngine.Mode.STANDARD.ordinal());
        i.putExtra(ConversionService.EXTRA_FRAME_MODE,PresetEngine.FrameMode.AUTO.ordinal());
        i.putExtra(ConversionService.EXTRA_PICTURE_MODE,PresetEngine.PictureMode.SHARP.ordinal());
        i.putExtra(ConversionService.EXTRA_SUBTITLE_MODE,SubtitleSupport.FORCED);
        i.putExtra(ConversionService.EXTRA_SUBTITLE_SIZE,SubtitleSupport.LARGE);
        i.putExtra(ConversionService.EXTRA_HEIGHT,208);
        i.putExtra(ConversionService.EXTRA_FPS,25);
        i.putExtra(ConversionService.EXTRA_DOWNLOADS,true);
        i.putExtra(ConversionService.EXTRA_AUTOBARS,false);
        i.putExtra(ConversionService.EXTRA_INPUT,importSource("22_FORCED_SUBTITLE_TEXT.mkv").toString());
        String msg=startAndWait(i,180);
        checkNewFiles(before,"SUB_FORCED");
        System.out.println("CLIPAMV_SUBTITLE_QA PASS "+msg);
    }
    @Test public void checkAutoFramingRegression(){
        PresetEngine.SourceInfo yt=new PresetEngine.SourceInfo();
        yt.width=640;yt.height=360;yt.fps=30;yt.audioPresent=true;
        PresetEngine.Preset p=PresetEngine.choose(yt,PresetEngine.Type.YOUTUBE,
                PresetEngine.Mode.STANDARD,208,30,false,PresetEngine.PortraitMode.FIT,
                null,PresetEngine.FrameMode.AUTO,PresetEngine.PictureMode.SHARP);
        assertEquals(PresetEngine.FrameMode.FILL,p.frameMode);
        assertEquals(208,p.pictureHeight);
        assertTrue(p.filter.contains("crop=256:208"));
        assertTrue(p.filter.contains("+32"));
    }
    @Test public void runMatrixOnActualAndroidEncoder()throws Exception{
        JSONArray data=new JSONArray(readAll(fixtures.getAssets().open("matrix.json")));
        int pass=0,fail=0,skip=0;
        for(int idx=0;idx<data.length();idx++){
            JSONObject m=data.getJSONObject(idx);String id=m.getString("test_id");
            // UI picker, active playback, cancel timing and thermal/speed measurement
            // require separate instrumented UI/device test. Do not label them PASS.
            if(id.equals("T35")||id.equals("T36")||id.equals("T37")||id.equals("T38")||id.equals("T39")){
                record(id,"NOT_RUN","Needs physical UI test or privately held Acura test footage");skip++;continue;
            }
            try{
                while(ConversionService.isWorking())Thread.sleep(200);
                Map<String,Uri> before=outputs();
                Intent intent=makeIntent(m);
                String msg=startAndWait(intent,300);
                checkNewFiles(before,id);
                record(id,"PASS",msg);pass++;
            }catch(Throwable e){
                fail++;record(id,"FAIL",e.getClass().getSimpleName()+": "+e.getMessage());
                if(ConversionService.isWorking()){
                    app.startService(new Intent(app,ConversionService.class).setAction(ConversionService.ACTION_CANCEL));
                    long stop=System.currentTimeMillis()+30000;
                    while(ConversionService.isWorking()&&System.currentTimeMillis()<stop)Thread.sleep(200);
                }
            }
        }
        record("TOTAL",fail==0?"PASS":"FAIL",pass+" pass, "+fail+" fail, "+skip+" not run (hardware/UI)");
        assertEquals("Matrix conversion failures. Download ClipAMV-emulator-matrix.csv artifact for details",0,fail);
    }
    private static String readAll(InputStream in)throws IOException{
        try(InputStream x=in;ByteArrayOutputStream output=new ByteArrayOutputStream()){
            byte[] b=new byte[65536];int n;while((n=x.read(b))!=-1)output.write(b,0,n);
            return output.toString("UTF-8");
        }
    }
}
