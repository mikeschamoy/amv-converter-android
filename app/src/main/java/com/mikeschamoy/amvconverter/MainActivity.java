package com.mikeschamoy.amvconverter;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.*;
import android.text.*;

import com.arthenica.ffmpegkit.*;

import java.io.*;
import java.util.*;
import java.util.regex.*;

public class MainActivity extends Activity {
    private static final int PICK_ONE=1001,PICK_QUEUE=1002,PICK_OUTPUT=1003,PICK_TREE=1004,SAVE_DIAG=1005;

    private Uri inputUri;
    private final ArrayList<Uri> queueUris=new ArrayList<>();
    private PresetEngine.SourceInfo source;
    private PresetEngine.Type type=PresetEngine.Type.YOUTUBE;
    private PresetEngine.Mode mode=PresetEngine.Mode.STANDARD;
    private PresetEngine.PortraitMode portraitMode=PresetEngine.PortraitMode.FIT;
    private int heightOverride=0,fpsOverride=0;
    private boolean preserve43=false,playerView=true,advanced=false;
    private PresetEngine.Crop crop;
    private String latestDiagnostics="ClipAMV diagnostics unavailable.";

    private TextView stateBadge,fileText,sourceText,reasonText,outputNameText,cropText,estimateText,presetText,statusText,historyText,previewTime;
    private Button youtubeButton,movieButton,cartoonButton,portraitButton,spaceSaverButton,standardButton,qualityButton;
    private Button[] heightButtons,fpsButtons;
    private Button full43,preserve43Button,portraitFit,portraitZoom,portraitRotate,playerViewButton,rawViewButton;
    private Button convertButton,startQueueButton,cancelButton,advancedButton,playPreviewButton;
    private EditText startTrim,endTrim;
    private CheckBox testClip;
    private ProgressBar progress;
    private PreviewTextureView preview;
    private ImageView fallback;
    private SeekBar previewSeek;
    private final Handler handler=new Handler(Looper.getMainLooper());

    private final Runnable seekUpdater=new Runnable(){
        @Override public void run(){
            if(preview!=null&&preview.duration()>0){
                previewSeek.setProgress((int)(1000.0*preview.position()/preview.duration()));
                previewTime.setText(fmtTime(preview.position()/1000.0));
            }
            handler.postDelayed(this,500);
        }
    };

    private final BroadcastReceiver receiver=new BroadcastReceiver(){
        @Override public void onReceive(Context c,Intent i){
            int p=i.getIntExtra("pct",0);
            progress.setProgress(p);
            String msg=i.getStringExtra("msg");
            statusText.setText(msg==null?"":msg);
            boolean done=i.getBooleanExtra("done",false);
            cancelButton.setVisibility(done?View.GONE:View.VISIBLE);
            stateBadge.setText(done?"READY":"CONVERTING");
            if(done)loadHistory();
        }
    };

    @Override protected void onCreate(Bundle b){
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        bind();
        setupUi();
        setupPreview();
        selfTest();
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},9);
        if(getSharedPreferences("state",MODE_PRIVATE).getBoolean("jobActive",false))
            statusText.setText("Previous conversion may have been interrupted. Verify the partial output before retrying.");
        handler.post(seekUpdater);
    }

    private void bind(){
        stateBadge=findViewById(R.id.stateBadge);fileText=findViewById(R.id.fileText);sourceText=findViewById(R.id.sourceText);
        reasonText=findViewById(R.id.reasonText);outputNameText=findViewById(R.id.outputNameText);cropText=findViewById(R.id.cropText);
        estimateText=findViewById(R.id.estimateText);presetText=findViewById(R.id.presetText);statusText=findViewById(R.id.statusText);
        historyText=findViewById(R.id.historyText);previewTime=findViewById(R.id.previewTime);
        youtubeButton=findViewById(R.id.youtubeButton);movieButton=findViewById(R.id.movieButton);
        cartoonButton=findViewById(R.id.cartoonButton);portraitButton=findViewById(R.id.portraitButton);
        spaceSaverButton=findViewById(R.id.spaceSaverButton);standardButton=findViewById(R.id.standardButton);qualityButton=findViewById(R.id.qualityButton);
        heightButtons=new Button[]{findViewById(R.id.h216),findViewById(R.id.h208),findViewById(R.id.h200),findViewById(R.id.h192),findViewById(R.id.h184)};
        fpsButtons=new Button[]{findViewById(R.id.fps15),findViewById(R.id.fps25),findViewById(R.id.fps30),findViewById(R.id.fpsAuto)};
        full43=findViewById(R.id.fourThreeFull);preserve43Button=findViewById(R.id.fourThreePreserve);
        portraitFit=findViewById(R.id.portraitFit);portraitZoom=findViewById(R.id.portraitZoom);portraitRotate=findViewById(R.id.portraitRotate);
        playerViewButton=findViewById(R.id.playerViewButton);rawViewButton=findViewById(R.id.rawViewButton);
        convertButton=findViewById(R.id.convertButton);startQueueButton=findViewById(R.id.startQueueButton);
        cancelButton=findViewById(R.id.cancelButton);advancedButton=findViewById(R.id.advancedButton);playPreviewButton=findViewById(R.id.playPreviewButton);
        startTrim=findViewById(R.id.startTrim);endTrim=findViewById(R.id.endTrim);testClip=findViewById(R.id.testClip);
        progress=findViewById(R.id.progressBar);preview=findViewById(R.id.previewTexture);fallback=findViewById(R.id.fallbackImage);previewSeek=findViewById(R.id.previewSeek);
    }

    private void setupUi(){
        findViewById(R.id.selectButton).setOnClickListener(v->pickOne());
        findViewById(R.id.selectQueueButton).setOnClickListener(v->pickQueue());

        youtubeButton.setOnClickListener(v->{type=PresetEngine.Type.YOUTUBE;heightOverride=0;refresh();});
        movieButton.setOnClickListener(v->{type=PresetEngine.Type.MOVIE_SHOW;heightOverride=0;refresh();});
        cartoonButton.setOnClickListener(v->{type=PresetEngine.Type.CARTOON;heightOverride=0;refresh();});
        portraitButton.setOnClickListener(v->{type=PresetEngine.Type.PORTRAIT;portraitMode=source!=null&&source.isPortrait()?PresetEngine.PortraitMode.ROTATE:PresetEngine.PortraitMode.FIT;refresh();});

        spaceSaverButton.setOnClickListener(v->{mode=PresetEngine.Mode.SPACE_SAVER;fpsOverride=0;refresh();});
        standardButton.setOnClickListener(v->{mode=PresetEngine.Mode.STANDARD;fpsOverride=0;refresh();});
        qualityButton.setOnClickListener(v->{mode=PresetEngine.Mode.QUALITY;fpsOverride=0;refresh();});

        int[] hs={216,208,200,192,184};
        for(int i=0;i<heightButtons.length;i++){final int h=hs[i];heightButtons[i].setOnClickListener(v->{heightOverride=h;refresh();});}
        int[] fs={15,25,30,0};
        for(int i=0;i<fpsButtons.length;i++){final int f=fs[i];fpsButtons[i].setOnClickListener(v->{fpsOverride=f;refresh();});}

        full43.setOnClickListener(v->{preserve43=false;refresh();});
        preserve43Button.setOnClickListener(v->{preserve43=true;refresh();});
        portraitFit.setOnClickListener(v->{portraitMode=PresetEngine.PortraitMode.FIT;refresh();});
        portraitZoom.setOnClickListener(v->{portraitMode=PresetEngine.PortraitMode.ZOOM;refresh();});
        portraitRotate.setOnClickListener(v->{portraitMode=PresetEngine.PortraitMode.ROTATE;refresh();});
        playerViewButton.setOnClickListener(v->{playerView=true;style();refreshPreview();});
        rawViewButton.setOnClickListener(v->{playerView=false;style();refreshPreview();});
        findViewById(R.id.refreshPreviewButton).setOnClickListener(v->generateExactPreview());

        findViewById(R.id.detectBarsButton).setOnClickListener(v->detectBars());
        findViewById(R.id.clearCropButton).setOnClickListener(v->{crop=null;cropText.setText("No detected crop.");refresh();});

        convertButton.setOnClickListener(v->chooseOutput());
        startQueueButton.setOnClickListener(v->chooseTree());
        cancelButton.setOnClickListener(v->cancel());
        advancedButton.setOnClickListener(v->toggleAdvanced());
        findViewById(R.id.exportDiagnosticsButton).setOnClickListener(v->saveDiagnostics());
        findViewById(R.id.resetDefaultsButton).setOnClickListener(v->resetDefaults());
        testClip.setOnCheckedChangeListener((b,x)->refreshEstimate());

        startTrim.addTextChangedListener(new SimpleTextWatcher(()->{
            refreshEstimate();
            double sec=parseTime(startTrim.getText().toString());
            if(preview.duration()>0)preview.seekTo((int)(sec*1000));
        }));
        endTrim.addTextChangedListener(new SimpleTextWatcher(this::refreshEstimate));
        loadHistory();
        style();
    }

    private void setupPreview(){
        preview.setListener(new PreviewTextureView.Listener(){
            public void onReady(int w,int h,int d){
                fallback.setVisibility(View.GONE);
                preview.setVisibility(View.VISIBLE);
                refreshPreview();
            }
            public void onError(){generateExactPreview();}
        });
        playPreviewButton.setOnClickListener(v->{preview.toggle();playPreviewButton.setText(preview.isPlaying()?"❚❚":"▶");});
        previewSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar s,int p,boolean user){if(user&&preview.duration()>0)preview.seekTo((int)(preview.duration()*p/1000.0));}
            public void onStartTrackingTouch(SeekBar s){}
            public void onStopTrackingTouch(SeekBar s){}
        });
    }

    private void selfTest(){
        stateBadge.setText("CHECKING");
        FFmpegKit.executeAsync("-hide_banner -encoders",session->runOnUiThread(()->{
            String o=session.getOutput();
            if(o!=null&&o.contains(" amv ")&&o.contains("adpcm_ima_amv")){
                stateBadge.setText("READY");
                statusText.setText("Engine self-test passed.");
            }else{
                stateBadge.setText("WARNING");
                statusText.setText("Required AMV encoder is unavailable.");
            }
        }));
    }

    private void pickOne(){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"video/*","image/jpeg","image/png","video/x-matroska","video/x-msvideo","application/octet-stream"});
        startActivityForResult(i,PICK_ONE);
    }

    private void pickQueue(){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);
        i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"video/*","image/jpeg","image/png","video/x-matroska","video/x-msvideo","application/octet-stream"});
        startActivityForResult(i,PICK_QUEUE);
    }

    private void chooseOutput(){
        if(inputUri==null||source==null)return;
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(source.image?"image/jpeg":"application/octet-stream");
        i.putExtra(Intent.EXTRA_TITLE,FileNameCleaner.clean(displayName(inputUri),type,source.image));
        startActivityForResult(i,PICK_OUTPUT);
    }

    private void chooseTree(){startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE),PICK_TREE);}

    private void saveDiagnostics(){
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TITLE,"ClipAMV-diagnostics.txt");
        startActivityForResult(i,SAVE_DIAG);
    }

    @Override protected void onActivityResult(int req,int result,Intent data){
        super.onActivityResult(req,result,data);
        if(result!=RESULT_OK||data==null)return;

        if(req==PICK_QUEUE){
            queueUris.clear();
            if(data.getClipData()!=null){
                for(int i=0;i<data.getClipData().getItemCount();i++)queueUris.add(data.getClipData().getItemAt(i).getUri());
            }else if(data.getData()!=null)queueUris.add(data.getData());
            for(Uri u:queueUris)takePerm(u,data.getFlags());
            if(!queueUris.isEmpty()){
                inputUri=queueUris.get(0);
                inspect(inputUri);
                fileText.setText(queueUris.size()+" files queued • previewing "+displayName(inputUri));
                startQueueButton.setVisibility(View.VISIBLE);
                startQueueButton.setEnabled(true);
                convertButton.setVisibility(View.GONE);
                statusText.setText("Queue selected. Each file will be inspected before conversion.");
            }
            return;
        }

        Uri uri=data.getData();
        if(uri==null)return;
        if(req==PICK_ONE){
            takePerm(uri,data.getFlags());
            queueUris.clear();
            inputUri=uri;
            inspect(uri);
            startQueueButton.setVisibility(View.GONE);
            convertButton.setVisibility(View.VISIBLE);
        }else if(req==PICK_OUTPUT){
            takePerm(uri,data.getFlags());
            startSingle(uri);
        }else if(req==PICK_TREE){
            takePerm(uri,data.getFlags());
            startQueue(uri);
        }else if(req==SAVE_DIAG){
            try(OutputStream os=getContentResolver().openOutputStream(uri)){
                if(os!=null)os.write(latestDiagnostics.getBytes());
                statusText.setText("Diagnostics saved.");
            }catch(Exception e){statusText.setText("Could not save diagnostics.");}
        }
    }

    private void takePerm(Uri uri,int flags){
        int f=flags&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try{getContentResolver().takePersistableUriPermission(uri,f);}catch(Exception ignored){}
    }

    private void inspect(Uri uri){
        stateBadge.setText("INSPECTING");
        convertButton.setEnabled(false);
        statusText.setText("Inspecting source…");
        crop=null;
        String saf=FFmpegKitConfig.getSafParameterForRead(this,uri);
        String cmd="-v error -show_entries stream=index,codec_type,codec_name,width,height,avg_frame_rate "+
                "-show_entries format=duration -of default=noprint_wrappers=1 "+q(saf);
        FFprobeKit.executeAsync(cmd,session->runOnUiThread(()->{
            try{
                source=parse(session.getOutput(),uri);
                if(source.isPortrait())type=PresetEngine.Type.PORTRAIT;
                fileText.setText(displayName(uri));
                sourceText.setText(sourceBlock(source));
                endTrim.setText(source.image?"":fmtTime(source.durationSeconds));
                stateBadge.setText("READY");
                convertButton.setEnabled(true);
                preview.setVideo(uri);
                refresh();
            }catch(Exception e){
                stateBadge.setText("WARNING");
                statusText.setText("Could not inspect file.");
            }
        }));
    }

    private PresetEngine.SourceInfo parse(String out,Uri uri){
        PresetEngine.SourceInfo s=new PresetEngine.SourceInfo();
        String ct="";
        s.audioPresent=false;
        for(String raw:out.split("\\r?\\n")){
            String line=raw.trim();
            if(line.startsWith("codec_type="))ct=line.substring(11);
            else if(line.startsWith("codec_name=")){
                if("video".equals(ct))s.videoCodec=line.substring(11);
                else if("audio".equals(ct)){s.audioCodec=line.substring(11);s.audioPresent=true;}
            }else if(line.startsWith("width=")&&s.width==0)s.width=Integer.parseInt(line.substring(6));
            else if(line.startsWith("height=")&&s.height==0)s.height=Integer.parseInt(line.substring(7));
            else if(line.startsWith("avg_frame_rate=")&&s.fps==0)s.fps=rational(line.substring(15));
            else if(line.startsWith("duration=")&&!line.endsWith("N/A"))s.durationSeconds=Double.parseDouble(line.substring(9));
        }
        String mime=getContentResolver().getType(uri);
        s.image=mime!=null&&mime.startsWith("image/");
        if(s.image){s.fps=1;s.durationSeconds=0;s.audioPresent=false;}
        if(s.width<=0||s.height<=0)throw new IllegalArgumentException();
        if(!s.image&&s.fps<=0)s.fps=25;
        s.inputBytes=size(uri);
        return s;
    }

    private String sourceBlock(PresetEngine.SourceInfo s){
        return "SOURCE\n"+s.width+"×"+s.height+"\n"+String.format(Locale.US,"%.3f fps",s.fps)+"\n"+
                String.format(Locale.US,"%.2f:1",s.ratio())+"\n"+(s.image?"IMAGE":fmtTime(s.durationSeconds))+
                "\nInput: "+human(s.inputBytes);
    }

    private PresetEngine.Preset preset(){
        return PresetEngine.choose(source,type,mode,heightOverride,fpsOverride,preserve43,portraitMode,crop);
    }

    private void refresh(){
        if(source==null)return;
        PresetEngine.Preset p=preset();
        reasonText.setText(PresetEngine.explain(source,type,p));
        outputNameText.setText("OUTPUT\n"+FileNameCleaner.clean(displayName(inputUri),type,source.image));
        presetText.setText("Preset Engine "+PresetEngine.PRESET_VERSION+"\n"+p.name+"\n"+p.filter+(p.q>0?"\nq="+p.q:""));
        latestDiagnostics=diagnostics(p);
        style();
        refreshPreview();
        refreshEstimate();
    }

    private void style(){
        Button[] content={youtubeButton,movieButton,cartoonButton,portraitButton};
        for(Button b:content)b.setSelected(false);
        content[type.ordinal()].setSelected(true);

        spaceSaverButton.setSelected(mode==PresetEngine.Mode.SPACE_SAVER);
        standardButton.setSelected(mode==PresetEngine.Mode.STANDARD);
        qualityButton.setSelected(mode==PresetEngine.Mode.QUALITY);

        int[] hs={216,208,200,192,184};
        for(int i=0;i<heightButtons.length;i++)heightButtons[i].setSelected(heightOverride==hs[i]);

        int[] fs={15,25,30,0};
        for(int i=0;i<fpsButtons.length;i++)fpsButtons[i].setSelected(fpsOverride==fs[i]);

        full43.setSelected(!preserve43);
        preserve43Button.setSelected(preserve43);
        portraitFit.setSelected(portraitMode==PresetEngine.PortraitMode.FIT);
        portraitZoom.setSelected(portraitMode==PresetEngine.PortraitMode.ZOOM);
        portraitRotate.setSelected(portraitMode==PresetEngine.PortraitMode.ROTATE);
        playerViewButton.setSelected(playerView);
        rawViewButton.setSelected(!playerView);

        boolean is43=source!=null&&source.isFourThree();
        findViewById(R.id.fourThreeRow).setVisibility(is43?View.VISIBLE:View.GONE);
        findViewById(R.id.portraitRow).setVisibility(type==PresetEngine.Type.PORTRAIT?View.VISIBLE:View.GONE);
        findViewById(R.id.frameScroller).setVisibility((is43||type==PresetEngine.Type.PORTRAIT)?View.GONE:View.VISIBLE);
    }

    private void refreshPreview(){if(source!=null)preview.setPreset(preset(),playerView);}

    private void generateExactPreview(){
        if(inputUri==null||source==null)return;
        stateBadge.setText("PREVIEW");
        statusText.setText("Building exact preview…");
        String saf=FFmpegKitConfig.getSafParameterForRead(this,inputUri);
        double ss=Math.max(0,parseTime(startTrim.getText().toString()));
        if(source.image){
            extractFrameAt(0);
            return;
        }
        File out=new File(getCacheDir(),"clipamv_proxy.mp4");
        String cmd="-y -ss "+f3(ss)+" -i "+q(saf)+" -t 8 -vf "+q(preset().filter)+
                " -an -c:v libx264 -preset ultrafast -crf 30 -pix_fmt yuv420p "+q(out.getAbsolutePath());
        FFmpegKit.executeAsync(cmd,session->runOnUiThread(()->{
            if(ReturnCode.isSuccess(session.getReturnCode())){
                preview.setVideo(Uri.fromFile(out));
                statusText.setText("Exact proxy preview loaded.");
            }else extractFrameAt(ss);
            stateBadge.setText("READY");
        }));
    }

    private void extractFrameAt(double sec){
        String saf=FFmpegKitConfig.getSafParameterForRead(this,inputUri);
        File out=new File(getCacheDir(),"clipamv_frame.jpg");
        String cmd="-y "+(!source.image?"-ss "+f3(sec)+" ":"")+"-i "+q(saf)+" -frames:v 1 -vf "+q(preset().filter)+" -q:v 2 "+q(out.getAbsolutePath());
        FFmpegKit.executeAsync(cmd,session->runOnUiThread(()->{
            Bitmap b=BitmapFactory.decodeFile(out.getAbsolutePath());
            if(b!=null){
                fallback.setImageBitmap(b);
                fallback.setVisibility(View.VISIBLE);
                preview.setVisibility(View.GONE);
                statusText.setText("Static preview frame loaded.");
            }else statusText.setText("Preview unavailable.");
            stateBadge.setText("READY");
        }));
    }

    private void detectBars(){
        if(inputUri==null||source==null||source.image)return;
        statusText.setText("Detecting black bars…");
        double ss=Math.max(0,source.durationSeconds/2-4);
        String saf=FFmpegKitConfig.getSafParameterForRead(this,inputUri);
        final StringBuilder logs=new StringBuilder();
        FFmpegKit.executeAsync("-ss "+f3(ss)+" -i "+q(saf)+" -t 8 -vf cropdetect=24:16:0 -an -f null -",
                done->runOnUiThread(()->{
                    Matcher m=Pattern.compile("crop=(\\d+):(\\d+):(\\d+):(\\d+)").matcher(logs.toString());
                    PresetEngine.Crop last=null;
                    while(m.find())last=new PresetEngine.Crop(Integer.parseInt(m.group(1)),Integer.parseInt(m.group(2)),Integer.parseInt(m.group(3)),Integer.parseInt(m.group(4)));
                    if(last!=null&&!(last.w>=source.width-8&&last.h>=source.height-8)){
                        crop=last;
                        cropText.setText("Detected: "+last);
                        refresh();
                    }else cropText.setText("No stable black-bar crop detected.");
                }),log->logs.append(log.getMessage()).append('\n'),stats->{});
    }

    private void refreshEstimate(){
        if(source==null)return;
        PresetEngine.Preset p=preset();
        double st=parseTime(startTrim.getText().toString()),en=parseTime(endTrim.getText().toString());
        double runtime=source.image?0:testClip.isChecked()?30:(en>st?en-st:Math.max(0,source.durationSeconds-st));
        long est=PresetEngine.estimateBytes(source,p,runtime);
        estimateText.setText("STORAGE\nInput: "+human(source.inputBytes)+"\nEstimated output: "+
                human((long)(est*0.90))+"–"+human((long)(est*1.10))+
                (source.image?"":"\nRuntime: "+fmtTime(runtime)+" • "+p.outputFps+" fps • "+mode.name().replace('_',' '))+
                "\nReserve: "+human((long)(est*1.10)));
    }

    private void startSingle(Uri out){
        Intent i=baseServiceIntent();
        i.putExtra(ConversionService.EXTRA_INPUT,inputUri.toString());
        i.putExtra(ConversionService.EXTRA_OUTPUT,out.toString());
        startServiceCompat(i);
    }

    private void startQueue(Uri tree){
        Intent i=baseServiceIntent();
        ArrayList<String> arr=new ArrayList<>();
        for(Uri u:queueUris)arr.add(u.toString());
        i.putStringArrayListExtra(ConversionService.EXTRA_INPUTS,arr);
        i.putExtra(ConversionService.EXTRA_TREE,tree.toString());
        startServiceCompat(i);
    }

    private Intent baseServiceIntent(){
        Intent i=new Intent(this,ConversionService.class);
        i.putExtra(ConversionService.EXTRA_TYPE,type.ordinal());
        i.putExtra(ConversionService.EXTRA_MODE,mode.ordinal());
        i.putExtra(ConversionService.EXTRA_HEIGHT,heightOverride);
        i.putExtra(ConversionService.EXTRA_FPS,fpsOverride);
        i.putExtra(ConversionService.EXTRA_43,preserve43);
        i.putExtra(ConversionService.EXTRA_PORTRAIT,portraitMode.ordinal());
        i.putExtra(ConversionService.EXTRA_AUTOBARS,true);
        i.putExtra(ConversionService.EXTRA_START,parseTime(startTrim.getText().toString()));
        i.putExtra(ConversionService.EXTRA_END,parseTime(endTrim.getText().toString()));
        i.putExtra(ConversionService.EXTRA_TEST,testClip.isChecked());
        return i;
    }

    private void startServiceCompat(Intent i){
        if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);
        stateBadge.setText("CONVERTING");
        cancelButton.setVisibility(View.VISIBLE);
        progress.setProgress(0);
        statusText.setText("Conversion started. Safe to leave app or turn screen off.");
    }

    private void cancel(){
        Intent i=new Intent(this,ConversionService.class);
        i.setAction(ConversionService.ACTION_CANCEL);
        startService(i);
    }

    private void toggleAdvanced(){
        advanced=!advanced;
        findViewById(R.id.advancedPanel).setVisibility(advanced?View.VISIBLE:View.GONE);
        advancedButton.setText(advanced?"ADVANCED ▴":"ADVANCED ▾");
    }

    private void resetDefaults(){
        mode=PresetEngine.Mode.STANDARD;
        heightOverride=0;
        fpsOverride=0;
        preserve43=false;
        crop=null;
        portraitMode=PresetEngine.PortraitMode.FIT;
        playerView=true;
        refresh();
    }

    private String diagnostics(PresetEngine.Preset p){
        return "ClipAMV v0.3\nPreset Engine "+PresetEngine.PRESET_VERSION+
                "\nDevice: "+Build.MANUFACTURER+" "+Build.MODEL+
                "\nAndroid: "+Build.VERSION.RELEASE+
                "\nSource: "+(source==null?"-":source.width+"x"+source.height+" "+source.fps+"fps "+source.videoCodec+" / "+source.audioCodec)+
                "\nType: "+type+"\nMode: "+mode+"\nPreset: "+p.name+"\nFilter: "+p.filter+"\nFPS: "+p.outputFps+"\nQ: "+p.q+"\n";
    }

    private void loadHistory(){
        String h=getSharedPreferences("history",MODE_PRIVATE).getString("items","");
        historyText.setText("RECENT\n"+(h.isEmpty()?"No conversions yet.":h));
    }

    private String displayName(Uri uri){
        try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){
            if(c!=null&&c.moveToFirst())return c.getString(0);
        }catch(Exception ignored){}
        return "media";
    }

    private long size(Uri uri){
        try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.SIZE},null,null,null)){
            if(c!=null&&c.moveToFirst()&&!c.isNull(0))return c.getLong(0);
        }catch(Exception ignored){}
        return 0;
    }

    private static double rational(String x){
        if(x.contains("/")){String[]p=x.split("/");double a=Double.parseDouble(p[0]),b=Double.parseDouble(p[1]);return b==0?0:a/b;}
        return Double.parseDouble(x);
    }
    private static double parseTime(String s){
        s=s.trim();
        if(s.isEmpty())return 0;
        try{
            String[]p=s.split(":");
            if(p.length==1)return Double.parseDouble(p[0]);
            if(p.length==2)return Double.parseDouble(p[0])*60+Double.parseDouble(p[1]);
            return Double.parseDouble(p[0])*3600+Double.parseDouble(p[1])*60+Double.parseDouble(p[2]);
        }catch(Exception e){return 0;}
    }
    private static String fmtTime(double s){
        long x=(long)Math.max(0,s),h=x/3600,m=(x%3600)/60,sec=x%60;
        return h>0?String.format(Locale.US,"%d:%02d:%02d",h,m,sec):String.format(Locale.US,"%d:%02d",m,sec);
    }
    private static String human(long b){
        if(b<=0)return "?";
        double m=b/1048576.0;
        return m>=1024?String.format(Locale.US,"%.2f GB",m/1024):String.format(Locale.US,"%.0f MB",m);
    }
    private static String q(String x){return "\""+x.replace("\"","\\\"")+"\"";}
    private static String f3(double x){return String.format(Locale.US,"%.3f",x);}

    @Override protected void onStart(){
        super.onStart();
        IntentFilter f=new IntentFilter(ConversionService.ACTION_PROGRESS);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(receiver,f,Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver,f);
        loadHistory();
    }

    @Override protected void onStop(){
        try{unregisterReceiver(receiver);}catch(Exception ignored){}
        super.onStop();
    }

    @Override protected void onDestroy(){
        handler.removeCallbacksAndMessages(null);
        preview.release();
        super.onDestroy();
    }

    private static final class SimpleTextWatcher implements TextWatcher {
        private final Runnable r;
        SimpleTextWatcher(Runnable r){this.r=r;}
        public void beforeTextChanged(CharSequence s,int st,int c,int a){}
        public void onTextChanged(CharSequence s,int st,int before,int count){r.run();}
        public void afterTextChanged(Editable e){}
    }
}
