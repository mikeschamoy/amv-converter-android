package com.mikeschamoy.amvconverter;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.view.View;
import android.widget.*;

import com.arthenica.ffmpegkit.*;

import java.io.File;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final int PICK_INPUT=1001,PICK_OUTPUT=1002;

    private Uri inputUri;
    private PresetEngine.SourceInfo sourceInfo;
    private PresetEngine.Type type=PresetEngine.Type.YOUTUBE;
    private int heightOverride=0;
    private boolean fourThreePreserve=false;
    private boolean playerView=true;
    private PresetEngine.Crop detectedCrop;

    private TextView fileText,sourceText,presetText,statusText,cropText,estimateText,historyText,previewTime;
    private Button convertButton,cancelButton,youtubeButton,movieButton,cartoonButton;
    private Button[] heightButtons;
    private Button fourThreeFull,fourThreePreserveButton,playerViewButton,rawViewButton,playPreviewButton;
    private ProgressBar progressBar;
    private EditText startTrim,endTrim;
    private CheckBox testClip;
    private PreviewTextureView preview;
    private ImageView fallbackImage;
    private SeekBar previewSeek;
    private Handler handler=new Handler(Looper.getMainLooper());

    private final Runnable seekUpdater=new Runnable(){
        @Override public void run(){
            if(preview.getDurationMs()>0){
                int p=preview.getCurrentMs();
                previewSeek.setProgress((int)(1000.0*p/preview.getDurationMs()));
                previewTime.setText(formatDuration(p/1000.0));
            }
            handler.postDelayed(this,500);
        }
    };

    private final BroadcastReceiver progressReceiver=new BroadcastReceiver(){
        @Override public void onReceive(Context c,Intent i){
            int pct=i.getIntExtra("pct",0);
            String msg=i.getStringExtra("msg");
            boolean done=i.getBooleanExtra("done",false);
            progressBar.setProgress(pct);
            statusText.setText(msg==null?"":msg);
            cancelButton.setVisibility(done?View.GONE:View.VISIBLE);
            if(done)loadHistory();
        }
    };

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        setContentView(R.layout.activity_main);

        bind();
        setupButtons();
        setupPreview();

        findViewById(R.id.selectButton).setOnClickListener(v->chooseInput());
        convertButton.setOnClickListener(v->chooseOutput());
        cancelButton.setOnClickListener(v->cancelConversion());
        findViewById(R.id.detectBarsButton).setOnClickListener(v->detectBars());
        findViewById(R.id.clearCropButton).setOnClickListener(v->{detectedCrop=null;cropText.setText("No detected crop.");refresh();});
        testClip.setOnCheckedChangeListener((b,x)->refreshEstimate());

        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},77);

        loadHistory();
        handler.post(seekUpdater);
    }

    private void bind(){
        fileText=findViewById(R.id.fileText);sourceText=findViewById(R.id.sourceText);
        presetText=findViewById(R.id.presetText);statusText=findViewById(R.id.statusText);
        cropText=findViewById(R.id.cropText);estimateText=findViewById(R.id.estimateText);
        historyText=findViewById(R.id.historyText);convertButton=findViewById(R.id.convertButton);
        cancelButton=findViewById(R.id.cancelButton);progressBar=findViewById(R.id.progressBar);
        startTrim=findViewById(R.id.startTrim);endTrim=findViewById(R.id.endTrim);testClip=findViewById(R.id.testClip);
        preview=findViewById(R.id.previewTexture);fallbackImage=findViewById(R.id.fallbackImage);
        previewSeek=findViewById(R.id.previewSeek);previewTime=findViewById(R.id.previewTime);
        playPreviewButton=findViewById(R.id.playPreviewButton);
        youtubeButton=findViewById(R.id.youtubeButton);movieButton=findViewById(R.id.movieButton);cartoonButton=findViewById(R.id.cartoonButton);
        heightButtons=new Button[]{findViewById(R.id.h216),findViewById(R.id.h208),findViewById(R.id.h200),findViewById(R.id.h192),findViewById(R.id.h184)};
        fourThreeFull=findViewById(R.id.fourThreeFull);fourThreePreserveButton=findViewById(R.id.fourThreePreserve);
        playerViewButton=findViewById(R.id.playerViewButton);rawViewButton=findViewById(R.id.rawViewButton);
    }

    private void setupButtons(){
        youtubeButton.setOnClickListener(v->{type=PresetEngine.Type.YOUTUBE;heightOverride=0;styleButtons();refresh();});
        movieButton.setOnClickListener(v->{type=PresetEngine.Type.MOVIE_SHOW;heightOverride=0;styleButtons();refresh();});
        cartoonButton.setOnClickListener(v->{type=PresetEngine.Type.CARTOON;heightOverride=0;styleButtons();refresh();});

        int[] hs={216,208,200,192,184};
        for(int i=0;i<heightButtons.length;i++){
            final int h=hs[i];
            heightButtons[i].setOnClickListener(v->{heightOverride=h;styleButtons();refresh();});
        }
        fourThreeFull.setOnClickListener(v->{fourThreePreserve=false;styleButtons();refresh();});
        fourThreePreserveButton.setOnClickListener(v->{fourThreePreserve=true;styleButtons();refresh();});
        playerViewButton.setOnClickListener(v->{playerView=true;styleButtons();refreshPreview();});
        rawViewButton.setOnClickListener(v->{playerView=false;styleButtons();refreshPreview();});
        styleButtons();
    }

    private void styleButtons(){
        int active=Color.rgb(25,54,84),normal=Color.rgb(95,95,98);
        youtubeButton.setBackgroundColor(type==PresetEngine.Type.YOUTUBE?active:normal);
        movieButton.setBackgroundColor(type==PresetEngine.Type.MOVIE_SHOW?active:normal);
        cartoonButton.setBackgroundColor(type==PresetEngine.Type.CARTOON?active:normal);
        int[] hs={216,208,200,192,184};
        for(int i=0;i<heightButtons.length;i++)heightButtons[i].setBackgroundColor(heightOverride==hs[i]?active:normal);
        fourThreeFull.setBackgroundColor(!fourThreePreserve?active:normal);
        fourThreePreserveButton.setBackgroundColor(fourThreePreserve?active:normal);
        playerViewButton.setBackgroundColor(playerView?active:normal);
        rawViewButton.setBackgroundColor(!playerView?active:normal);
    }

    private void setupPreview(){
        preview.setListener(new PreviewTextureView.Listener(){
            @Override public void onReady(int w,int h,int durationMs){
                fallbackImage.setVisibility(View.GONE);preview.setVisibility(View.VISIBLE);refreshPreview();
            }
            @Override public void onPreviewError(){extractFallbackFrame();}
        });
        playPreviewButton.setOnClickListener(v->{preview.toggle();playPreviewButton.setText(preview.isPlaying()?"❚❚":"▶");});
        previewSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar s,int p,boolean user){if(user&&preview.getDurationMs()>0)preview.seekTo((int)(preview.getDurationMs()*p/1000.0));}
            @Override public void onStartTrackingTouch(SeekBar s){}
            @Override public void onStopTrackingTouch(SeekBar s){}
        });
    }

    private void chooseInput(){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"video/*","video/x-matroska","video/x-msvideo","application/octet-stream"});
        startActivityForResult(i,PICK_INPUT);
    }

    private void chooseOutput(){
        if(inputUri==null||sourceInfo==null)return;
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/octet-stream");
        String base=displayName(inputUri).replaceFirst("\\.[^.]+$","");
        i.putExtra(Intent.EXTRA_TITLE,base+(testClip.isChecked()?"-TEST":"")+".amv");
        startActivityForResult(i,PICK_OUTPUT);
    }

    @Override protected void onActivityResult(int req,int result,Intent data){
        super.onActivityResult(req,result,data);
        if(result!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();
        int flags=data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try{getContentResolver().takePersistableUriPermission(uri,flags);}catch(Exception ignored){}
        if(req==PICK_INPUT){
            inputUri=uri;fileText.setText(displayName(uri));detectedCrop=null;cropText.setText("No detected crop.");
            inspect(uri);
        } else if(req==PICK_OUTPUT) startConversion(uri);
    }

    private void inspect(Uri uri){
        convertButton.setEnabled(false);statusText.setText("Inspecting source…");sourceText.setText("SOURCE\nChecking…");
        String saf=FFmpegKitConfig.getSafParameterForRead(this,uri);
        String cmd="-v error -select_streams v:0 -show_entries stream=width,height,avg_frame_rate "
                +"-show_entries format=duration -of default=noprint_wrappers=1 "+q(saf);
        FFprobeKit.executeAsync(cmd,session->runOnUiThread(()->{
            try{
                sourceInfo=parseProbe(session.getOutput());
                sourceText.setText(String.format(Locale.US,"SOURCE\n%d×%d\n%.3f fps\n%.2f:1\n%s",
                        sourceInfo.width,sourceInfo.height,sourceInfo.fps,sourceInfo.ratio(),formatDuration(sourceInfo.durationSeconds)));
                statusText.setText("Ready.");convertButton.setEnabled(true);
                preview.setVideo(uri);refresh();
            }catch(Exception e){statusText.setText("Could not inspect this file.");}
        }));
    }

    private PresetEngine.SourceInfo parseProbe(String out){
        PresetEngine.SourceInfo s=new PresetEngine.SourceInfo();
        for(String raw:out.split("\\r?\\n")){
            String line=raw.trim();
            if(line.startsWith("width="))s.width=Integer.parseInt(line.substring(6));
            else if(line.startsWith("height="))s.height=Integer.parseInt(line.substring(7));
            else if(line.startsWith("avg_frame_rate="))s.fps=rational(line.substring(15));
            else if(line.startsWith("duration="))s.durationSeconds=Double.parseDouble(line.substring(9));
        }
        if(s.width<=0||s.height<=0)throw new IllegalArgumentException();
        if(s.fps<=0)s.fps=25;
        return s;
    }

    private double rational(String x){
        if(x.contains("/")){String[]p=x.split("/");double a=Double.parseDouble(p[0]),b=Double.parseDouble(p[1]);return b==0?0:a/b;}
        return Double.parseDouble(x);
    }

    private PresetEngine.Preset currentPreset(){
        return PresetEngine.choose(sourceInfo,type,heightOverride,fourThreePreserve,detectedCrop);
    }

    private void refresh(){
        if(sourceInfo==null)return;
        PresetEngine.Preset p=currentPreset();
        presetText.setText("PRESET\n"+p.name+"\n"+p.filter);
        boolean f43=sourceInfo.isFourThree() || (detectedCrop!=null && Math.abs(detectedCrop.ratio()-4.0/3.0)<.05);
        findViewById(R.id.fourThreeRow).setVisibility(sourceInfo.isFourThree()?View.VISIBLE:View.GONE);
        for(Button b:heightButtons)b.setVisibility(f43?View.GONE:View.VISIBLE);
        refreshPreview();refreshEstimate();
    }

    private void refreshPreview(){
        if(sourceInfo==null)return;
        PresetEngine.Preset p=currentPreset();
        preview.setPreset(p,playerView);
        if(fallbackImage.getVisibility()==View.VISIBLE)applyBitmapTransform(p);
    }

    private void refreshEstimate(){
        if(sourceInfo==null)return;
        double start=parseTime(startTrim.getText().toString());
        double end=parseTime(endTrim.getText().toString());
        double dur=testClip.isChecked()?30:(end>start&&end>0?end-start:Math.max(0,sourceInfo.durationSeconds-start));
        double mb=dur*1.5/8.0; // ~1.5 Mb/s empirical AMV estimate
        estimateText.setText(String.format(Locale.US,"ESTIMATE\nRuntime: %s\nOutput: ~%.0f MB",formatDuration(dur),mb));
    }

    private void detectBars(){
        if(inputUri==null||sourceInfo==null)return;
        statusText.setText("Detecting black bars…");
        double ss=Math.max(0,sourceInfo.durationSeconds/2.0-4);
        String saf=FFmpegKitConfig.getSafParameterForRead(this,inputUri);
        String cmd="-ss "+String.format(Locale.US,"%.3f",ss)+" -i "+q(saf)+" -t 8 -vf cropdetect=24:16:0 -an -f null -";
        final StringBuilder logs=new StringBuilder();
        FFmpegKit.executeAsync(cmd,session->runOnUiThread(()->{
            Matcher m=Pattern.compile("crop=(\\d+):(\\d+):(\\d+):(\\d+)").matcher(logs.toString());
            PresetEngine.Crop last=null;
            while(m.find()) last=new PresetEngine.Crop(Integer.parseInt(m.group(1)),Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)),Integer.parseInt(m.group(4)));
            if(last!=null){
                detectedCrop=last;cropText.setText("Detected: "+last+"  (active)");
                statusText.setText("Crop detection complete.");refresh();
            }else statusText.setText("No stable black-bar crop detected.");
        }),log->logs.append(log.getMessage()).append('\n'),stats->{});
    }

    private void extractFallbackFrame(){
        if(inputUri==null)return;
        statusText.setText("Native preview unavailable — extracting preview frame…");
        String saf=FFmpegKitConfig.getSafParameterForRead(this,inputUri);
        File out=new File(getCacheDir(),"preview.jpg");
        String cmd="-y -ss 5 -i "+q(saf)+" -frames:v 1 -q:v 2 "+q(out.getAbsolutePath());
        FFmpegKit.executeAsync(cmd,session->runOnUiThread(()->{
            Bitmap bmp=BitmapFactory.decodeFile(out.getAbsolutePath());
            if(bmp!=null){
                fallbackImage.setImageBitmap(bmp);fallbackImage.setVisibility(View.VISIBLE);preview.setVisibility(View.GONE);
                applyBitmapTransform(currentPreset());statusText.setText("Static preview loaded.");
            }else statusText.setText("Preview unavailable for this file.");
        }));
    }

    private void applyBitmapTransform(PresetEngine.Preset p){
        if(fallbackImage.getDrawable()==null)return;
        int dw=fallbackImage.getDrawable().getIntrinsicWidth(),dh=fallbackImage.getDrawable().getIntrinsicHeight();
        int vw=fallbackImage.getWidth(),vh=fallbackImage.getHeight();
        if(vw<=0||vh<=0||dw<=0||dh<=0)return;
        Matrix m=new Matrix();
        RectF src=new RectF(0,0,dw,dh),dst=new RectF(0,0,vw,vh);
        m.setRectToRect(src,dst,Matrix.ScaleToFit.CENTER);
        fallbackImage.setImageMatrix(m);
    }

    private void startConversion(Uri outputUri){
        PresetEngine.Preset p=currentPreset();
        double start=parseTime(startTrim.getText().toString());
        double end=parseTime(endTrim.getText().toString());

        Intent svc=new Intent(this,ConversionService.class);
        svc.putExtra(ConversionService.EXTRA_INPUT,inputUri.toString());
        svc.putExtra(ConversionService.EXTRA_OUTPUT,outputUri.toString());
        svc.putExtra(ConversionService.EXTRA_FILTER,p.filter);
        svc.putExtra(ConversionService.EXTRA_FPS,p.outputFps);
        svc.putExtra(ConversionService.EXTRA_BLOCK,p.blockSize);
        svc.putExtra(ConversionService.EXTRA_Q1,p.cartoonQ1);
        svc.putExtra(ConversionService.EXTRA_DURATION_MS,(long)(sourceInfo.durationSeconds*1000));
        svc.putExtra(ConversionService.EXTRA_START,start);
        svc.putExtra(ConversionService.EXTRA_END,end);
        svc.putExtra(ConversionService.EXTRA_TEST,testClip.isChecked());
        svc.putExtra(ConversionService.EXTRA_NAME,displayName(outputUri));

        if(Build.VERSION.SDK_INT>=26)startForegroundService(svc);else startService(svc);
        statusText.setText("Conversion started — safe to leave app or turn screen off.");
        cancelButton.setVisibility(View.VISIBLE);progressBar.setProgress(0);
    }

    private void cancelConversion(){
        Intent i=new Intent(this,ConversionService.class);i.setAction(ConversionService.ACTION_CANCEL);startService(i);
    }

    private double parseTime(String s){
        s=s.trim();if(s.isEmpty())return 0;
        try{
            String[] p=s.split(":");
            if(p.length==1)return Double.parseDouble(p[0]);
            if(p.length==2)return Double.parseDouble(p[0])*60+Double.parseDouble(p[1]);
            if(p.length==3)return Double.parseDouble(p[0])*3600+Double.parseDouble(p[1])*60+Double.parseDouble(p[2]);
        }catch(Exception ignored){}
        return 0;
    }

    private void loadHistory(){
        String h=getSharedPreferences("history",MODE_PRIVATE).getString("items","");
        historyText.setText("RECENT\n"+(h.isEmpty()?"No conversions yet.":h));
    }

    private String displayName(Uri uri){
        try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){
            if(c!=null&&c.moveToFirst())return c.getString(0);
        }catch(Exception ignored){}
        return "output.amv";
    }

    private String formatDuration(double s){
        long sec=(long)s,h=sec/3600,m=(sec%3600)/60,x=sec%60;
        return h>0?String.format(Locale.US,"%d:%02d:%02d",h,m,x):String.format(Locale.US,"%d:%02d",m,x);
    }
    private static String q(String x){return "\""+x.replace("\"","\\\"")+"\"";}

    @Override protected void onStart(){
        super.onStart();
        IntentFilter f=new IntentFilter(ConversionService.ACTION_PROGRESS);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(progressReceiver,f,Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(progressReceiver,f);
        loadHistory();
    }

    @Override protected void onStop(){
        try{unregisterReceiver(progressReceiver);}catch(Exception ignored){}
        super.onStop();
    }

    @Override protected void onDestroy(){
        handler.removeCallbacksAndMessages(null);preview.release();super.onDestroy();
    }
}
