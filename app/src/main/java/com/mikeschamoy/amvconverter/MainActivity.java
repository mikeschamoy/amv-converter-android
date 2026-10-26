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
import android.os.Environment;
import android.os.StatFs;
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
    private final LinkedHashMap<String,PresetEngine.SourceInfo> queueInfo=new LinkedHashMap<>();
    private PresetEngine.SourceInfo source;
    private PresetEngine.Type type=PresetEngine.Type.YOUTUBE;
    private PresetEngine.Mode mode=PresetEngine.Mode.STANDARD;
    private PresetEngine.PortraitMode portraitMode=PresetEngine.PortraitMode.FIT;
    private int heightOverride=0,fpsOverride=0;
    private boolean preserve43=false,playerView=true,advanced=false,queueAuto=true,typeAuto=true,queueReady=false,usingProxy=false,generatingProxy=false,conversionActive=false;
    private int performanceMode=0;
    private PresetEngine.FrameMode frameMode=PresetEngine.FrameMode.AUTO;
    private PresetEngine.PictureMode pictureMode=PresetEngine.PictureMode.AUTO;
    private PresetEngine.PictureMode autoPicture=PresetEngine.PictureMode.SHARP;
    private String autoReason="Waiting to analyze selected video";
    private Button pictureAutoButton,pictureSharpButton,pictureSmoothButton,pictureCompareButton;
    private TextView pictureInfo;
    private long previewJobVersion=0;
    private double requestedPreviewSeconds=-1;
    private final Runnable previewRebuild=()->generateRawProxy();
    private long activePreviewSessionId=-1;
    private boolean previewSeeking=false;
    private int subtitleMode=SubtitleSupport.OFF,subtitleSize=SubtitleSupport.LARGE;
    private Button subsOffButton,subsForcedButton,subsTextButton,subsNormalButton,subsLargeButton;
    private boolean previewAutoplayWhenReady=false;
    private double pendingSourceSeekSeconds=-1;
    private double displayedPreviewSeconds=0;
    private String previewDiagnostic="No preview attempt recorded";
    private PresetEngine.Crop crop;
    private volatile PresetEngine.Preset lastResolvedPreviewPreset;
    private String latestDiagnostics="ClipAMV v0.7.7\nNo source selected yet.";
    private long sourceGeneration=0,queueGeneration=0;
    private double proxyBaseSeconds=0,pendingPreviewSeek=-1;

    private TextView stateBadge,fileText,sourceText,reasonText,outputNameText,cropText,estimateText,presetText,statusText,historyText,previewTime,queueHeader,telemetryText,autoSummaryText,resultText;
    private LinearLayout queuePanel,queueList;
    private Button youtubeButton,movieButton,cartoonButton,portraitButton,spaceSaverButton,standardButton,qualityButton,perfStandardButton,perfFasterButton,perfMaximumButton,autoFrameButton,fitFrameButton,balancedFrameButton,fillFrameButton;
    private Button[] heightButtons,fpsButtons;
    private Button full43,preserve43Button,portraitFit,portraitZoom,portraitRotate,playerViewButton,rawViewButton;
    private Button convertButton,startQueueButton,cancelButton,pauseQueueButton,resumeQueueButton,discardQueueButton,historyToggleButton,advancedButton,playPreviewButton,queueAutoButton,queueSameButton,queueDownloadsButton,queueFolderButton,autoTypeButton,saveAsButton;
    private EditText startTrim,endTrim;
    private CheckBox testClip,autoBarsCheck;
    private ProgressBar progress;
    private PreviewTextureView preview;
    private ImageView fallback;
    private SeekBar previewSeek;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable trimProxyRefresh=new Runnable(){@Override public void run(){if(usingProxy)requestPreviewAt(parseTime(startTrim.getText().toString()));}};

    private final Runnable seekUpdater=new Runnable(){
        @Override public void run(){
            if(preview!=null&&preview.duration()>0){
                double shown=(usingProxy?proxyBaseSeconds:0)+preview.position()/1000.0;
                displayedPreviewSeconds=shown;
                if(!previewSeeking){
                    if(source!=null&&source.durationSeconds>0){
                        previewSeek.setProgress((int)Math.max(0,Math.min(1000,
                            1000.0*shown/source.durationSeconds)));
                    }
                    previewTime.setText(fmtTime(shown));
                }
                updatePlaybackButton();
            }
            handler.postDelayed(this,250);
        }
    };
    private void updatePlaybackButton(){
        boolean playing=preview!=null&&preview.isPlaying();
        playPreviewButton.setText(playing?"❚❚":"▶");
        playPreviewButton.setContentDescription(playing?"Pause preview":"Play preview");
    }
    private void recordPreviewProblem(String stage,String info){
        previewDiagnostic=stage+": "+(info==null?"Unknown":info);
        getSharedPreferences("state",MODE_PRIVATE).edit()
            .putString("lastPreviewError",previewDiagnostic).apply();
    }

    private final BroadcastReceiver receiver=new BroadcastReceiver(){
        @Override public void onReceive(Context c,Intent i){
            int p=i.getIntExtra("pct",0);
            progress.setProgress(p);
            progress.setVisibility(i.getBooleanExtra("done",false)?View.GONE:View.VISIBLE);
            telemetryText.setVisibility(i.getBooleanExtra("done",false)?View.GONE:View.VISIBLE);
            String msg=i.getStringExtra("msg");
            statusText.setText(msg==null?"":msg);
            String telemetry=i.getStringExtra("telemetry");
            if(telemetry!=null)telemetryText.setText(telemetry);
            boolean done=i.getBooleanExtra("done",false);
            if(!done){
                int w=i.getIntExtra("pictureWidth",0),h=i.getIntExtra("pictureHeight",0);
                String name=i.getStringExtra("pictureName");
                if(w>0&&h>0)showEncodingPicture(w,h,name);
                else showPictureScanning();
            }
            if(done){
                conversionActive=false;
                convertButton.setEnabled(inputUri!=null&&source!=null&&queuePanel.getVisibility()!=View.VISIBLE);
                queueDownloadsButton.setEnabled(true);queueFolderButton.setEnabled(true);
            }
            cancelButton.setVisibility(done?View.GONE:View.VISIBLE);
            pauseQueueButton.setVisibility(!done&&i.getBooleanExtra("isQueue",false)?View.VISIBLE:View.GONE);
            if(i.getBooleanExtra("pauseRequested",false)){
                pauseQueueButton.setEnabled(false);pauseQueueButton.setText("PAUSE REQUESTED • FINISHING FILE");
            }
            stateBadge.setText(done?(getSharedPreferences("state",MODE_PRIVATE)
                .getInt("pausedRemaining",0)>0?"PAUSED":"READY"):"CONVERTING");
            if(done){showLastPicture();loadHistory();loadLastResult();refreshPauseControls();}
        }
    };

    // Display the exact resolved encode size, never the permitted height range.
    private void showEncodingPicture(int width,int height,String name){
        String shortName=(name==null||name.isEmpty())?"Current video":name;
        String detail="Encoded frame: "+width+"×"+height+" • "+height+"px high";
        autoSummaryText.setText("ENCODING • "+shortName+"\n"+detail);
        if(queuePanel.getVisibility()==View.VISIBLE)
            queueHeader.setText("QUEUE • "+queueUris.size()+" FILES\n"+shortName+"\n"+detail);
    }
    private void showPictureScanning(){
        autoSummaryText.setText("SCANNING VIDEO\nChecking for source black borders…");
        if(queuePanel.getVisibility()==View.VISIBLE)
            queueHeader.setText("QUEUE • "+queueUris.size()+" FILES\nChecking source borders…");
    }
    private void showLastPicture(){
        String report=getSharedPreferences("state",MODE_PRIVATE).getString("lastResult","");
        String last="";
        for(String line:report.split("\n")){
            if(line.startsWith("Last picture: ")){last=line.substring("Last picture: ".length());break;}
        }
        String label=last.isEmpty()?"CONVERSION FINISHED\nSee the summary below":
            "LAST SAVED PICTURE\n"+last;
        autoSummaryText.setText(label);
        if(queuePanel.getVisibility()==View.VISIBLE)
            queueHeader.setText("QUEUE • "+queueUris.size()+" FILES\n"+label);
    }

    @Override protected void onCreate(Bundle b){
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        performanceMode=Math.max(0,Math.min(2,getSharedPreferences("state",MODE_PRIVATE).getInt("performance",0)));
        pictureMode=PresetEngine.PictureMode.values()[Math.max(0,Math.min(2,getSharedPreferences("state",MODE_PRIVATE).getInt("picture",0)))];
        bind(); setupUi(); setupPreview(); selfTest();loadLastResult();refreshPauseControls();
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},9);
        if(getSharedPreferences("state",MODE_PRIVATE).getBoolean("jobActive",false)){
            if(ConversionService.isWorking())
                statusText.setText("Conversion running in background. See notification for progress.");
            else getSharedPreferences("state",MODE_PRIVATE).edit().putBoolean("jobActive",false).apply();
        }
        handler.post(seekUpdater);
    }

    private void bind(){
        stateBadge=findViewById(R.id.stateBadge); fileText=findViewById(R.id.fileText); sourceText=findViewById(R.id.sourceText);
        reasonText=findViewById(R.id.reasonText); outputNameText=findViewById(R.id.outputNameText); cropText=findViewById(R.id.cropText);
        estimateText=findViewById(R.id.estimateText); presetText=findViewById(R.id.presetText); statusText=findViewById(R.id.statusText);
        historyText=findViewById(R.id.historyText); previewTime=findViewById(R.id.previewTime);
        telemetryText=findViewById(R.id.telemetryText);
        autoSummaryText=findViewById(R.id.autoSummaryText);resultText=findViewById(R.id.resultText);
        autoTypeButton=findViewById(R.id.autoTypeButton);saveAsButton=findViewById(R.id.saveAsButton);
        perfStandardButton=findViewById(R.id.perfStandardButton);
        perfFasterButton=findViewById(R.id.perfFasterButton);
        perfMaximumButton=findViewById(R.id.perfMaximumButton);
        pictureAutoButton=findViewById(R.id.pictureAutoButton);
        pictureSharpButton=findViewById(R.id.pictureSharpButton);
        pictureSmoothButton=findViewById(R.id.pictureSmoothButton);
        pictureCompareButton=findViewById(R.id.pictureCompareButton);
        pictureInfo=findViewById(R.id.pictureInfo);
        subsOffButton=findViewById(R.id.subsOffButton);subsForcedButton=findViewById(R.id.subsForcedButton);subsTextButton=findViewById(R.id.subsTextButton);
        subsNormalButton=findViewById(R.id.subsNormalButton);subsLargeButton=findViewById(R.id.subsLargeButton);
        autoFrameButton=findViewById(R.id.autoFrameButton);fitFrameButton=findViewById(R.id.fitFrameButton);balancedFrameButton=findViewById(R.id.balancedFrameButton);fillFrameButton=findViewById(R.id.fillFrameButton);
        queuePanel=findViewById(R.id.queuePanel); queueList=findViewById(R.id.queueList); queueHeader=findViewById(R.id.queueHeader);
        queueAutoButton=findViewById(R.id.queueAutoButton); queueSameButton=findViewById(R.id.queueSameButton);
        queueDownloadsButton=findViewById(R.id.queueDownloadsButton); queueFolderButton=findViewById(R.id.queueFolderButton);
        youtubeButton=findViewById(R.id.youtubeButton); movieButton=findViewById(R.id.movieButton); cartoonButton=findViewById(R.id.cartoonButton); portraitButton=findViewById(R.id.portraitButton);
        spaceSaverButton=findViewById(R.id.spaceSaverButton); standardButton=findViewById(R.id.standardButton); qualityButton=findViewById(R.id.qualityButton);
        heightButtons=new Button[]{findViewById(R.id.h216),findViewById(R.id.h208),findViewById(R.id.h200),findViewById(R.id.h192),findViewById(R.id.h184)};
        fpsButtons=new Button[]{findViewById(R.id.fps15),findViewById(R.id.fps25),findViewById(R.id.fps30),findViewById(R.id.fpsAuto)};
        full43=findViewById(R.id.fourThreeFull); preserve43Button=findViewById(R.id.fourThreePreserve);
        portraitFit=findViewById(R.id.portraitFit); portraitZoom=findViewById(R.id.portraitZoom); portraitRotate=findViewById(R.id.portraitRotate);
        playerViewButton=findViewById(R.id.playerViewButton); rawViewButton=findViewById(R.id.rawViewButton);
        convertButton=findViewById(R.id.convertButton); startQueueButton=findViewById(R.id.startQueueButton); cancelButton=findViewById(R.id.cancelButton);
        pauseQueueButton=findViewById(R.id.pauseQueueButton);resumeQueueButton=findViewById(R.id.resumeQueueButton);
        discardQueueButton=findViewById(R.id.discardQueueButton);
        historyToggleButton=findViewById(R.id.historyToggleButton);
        advancedButton=findViewById(R.id.advancedButton); playPreviewButton=findViewById(R.id.playPreviewButton);
        startTrim=findViewById(R.id.startTrim); endTrim=findViewById(R.id.endTrim); testClip=findViewById(R.id.testClip);autoBarsCheck=findViewById(R.id.autoBarsCheck);
        progress=findViewById(R.id.progressBar); preview=findViewById(R.id.previewTexture); fallback=findViewById(R.id.fallbackImage); previewSeek=findViewById(R.id.previewSeek);
        startQueueButton.setVisibility(View.GONE);
    }

    private void setupUi(){
        findViewById(R.id.selectButton).setOnClickListener(v->pickOne());
        findViewById(R.id.selectQueueButton).setOnClickListener(v->pickQueue());
        autoTypeButton.setOnClickListener(v->{typeAuto=true;refresh();});
        youtubeButton.setOnClickListener(v->{typeAuto=false;type=PresetEngine.Type.YOUTUBE;refresh();});
        movieButton.setOnClickListener(v->{typeAuto=false;type=PresetEngine.Type.MOVIE_SHOW;refresh();});
        cartoonButton.setOnClickListener(v->{typeAuto=false;type=PresetEngine.Type.CARTOON;refresh();});
        portraitButton.setOnClickListener(v->{typeAuto=false;type=PresetEngine.Type.PORTRAIT;portraitMode=source!=null&&source.isPortrait()?PresetEngine.PortraitMode.ROTATE:PresetEngine.PortraitMode.FIT;refresh();});
        spaceSaverButton.setOnClickListener(v->{mode=PresetEngine.Mode.SPACE_SAVER;refresh();});
        standardButton.setOnClickListener(v->{mode=PresetEngine.Mode.STANDARD;refresh();});
        qualityButton.setOnClickListener(v->{mode=PresetEngine.Mode.QUALITY;refresh();});
        perfStandardButton.setOnClickListener(v->setPerformance(0));
        perfFasterButton.setOnClickListener(v->setPerformance(1));
        perfMaximumButton.setOnClickListener(v->setPerformance(2));
        pictureAutoButton.setOnClickListener(v->selectPicture(PresetEngine.PictureMode.AUTO));
        pictureSharpButton.setOnClickListener(v->selectPicture(PresetEngine.PictureMode.SHARP));
        pictureSmoothButton.setOnClickListener(v->selectPicture(PresetEngine.PictureMode.SMOOTH));
        pictureCompareButton.setOnClickListener(v->comparePicture());
        subsOffButton.setOnClickListener(v->setSubtitles(SubtitleSupport.OFF));
        subsForcedButton.setOnClickListener(v->setSubtitles(SubtitleSupport.FORCED));
        subsTextButton.setOnClickListener(v->setSubtitles(SubtitleSupport.FIRST_TEXT));
        subsNormalButton.setOnClickListener(v->setSubtitleSize(SubtitleSupport.NORMAL));
        subsLargeButton.setOnClickListener(v->setSubtitleSize(SubtitleSupport.LARGE));
        autoFrameButton.setOnClickListener(v->{frameMode=PresetEngine.FrameMode.AUTO;refresh();});
        balancedFrameButton.setOnClickListener(v->{frameMode=PresetEngine.FrameMode.BALANCED;refresh();});
        fitFrameButton.setOnClickListener(v->{frameMode=PresetEngine.FrameMode.FIT;refresh();});
        fillFrameButton.setOnClickListener(v->{frameMode=PresetEngine.FrameMode.FILL;refresh();});

        int[] hs={216,208,200,192,184};
        for(int i=0;i<heightButtons.length;i++){final int h=hs[i];heightButtons[i].setOnClickListener(v->{heightOverride=h;refresh();});}
        int[] fs={15,25,30,0};
        for(int i=0;i<fpsButtons.length;i++){final int f=fs[i];fpsButtons[i].setOnClickListener(v->{fpsOverride=f;refresh();});}

        full43.setOnClickListener(v->{preserve43=false;refresh();}); preserve43Button.setOnClickListener(v->{preserve43=true;refresh();});
        portraitFit.setOnClickListener(v->{portraitMode=PresetEngine.PortraitMode.FIT;refresh();});
        portraitZoom.setOnClickListener(v->{portraitMode=PresetEngine.PortraitMode.ZOOM;refresh();});
        portraitRotate.setOnClickListener(v->{portraitMode=PresetEngine.PortraitMode.ROTATE;refresh();});
        playerViewButton.setOnClickListener(v->{playerView=true;style();preview.setPreset(preset(),true);refreshFallbackView();});
        rawViewButton.setOnClickListener(v->{playerView=false;style();preview.setPreset(preset(),false);refreshFallbackView();});
        findViewById(R.id.refreshPreviewButton).setOnClickListener(v->refreshPreviewSource());
        findViewById(R.id.detectBarsButton).setOnClickListener(v->detectBars());
        findViewById(R.id.clearCropButton).setOnClickListener(v->{crop=null;cropText.setText("No detected crop.");refresh();});

        queueAutoButton.setOnClickListener(v->{queueAuto=true;style();refreshQueueEstimate();});
        queueSameButton.setOnClickListener(v->{PresetEngine.Type selected=effectiveType(source,inputUri);queueAuto=false;typeAuto=false;type=selected;style();refreshQueueEstimate();});
        queueDownloadsButton.setOnClickListener(v->startQueueDownloads());
        queueFolderButton.setOnClickListener(v->chooseTree());

        convertButton.setOnClickListener(v->startSingleDownloads());
        saveAsButton.setOnClickListener(v->chooseOutput());cancelButton.setOnClickListener(v->cancel());
        pauseQueueButton.setOnClickListener(v->requestQueuePause());
        resumeQueueButton.setOnClickListener(v->resumeQueue());
        discardQueueButton.setOnClickListener(v->new android.app.AlertDialog.Builder(this)
            .setTitle("Discard remaining queue?")
            .setMessage("Already finished AMV files stay saved. Only the list of files waiting to convert will be removed.")
            .setNegativeButton("Keep queue",(d,w)->{})
            .setPositiveButton("Discard queue",(d,w)->{
                getSharedPreferences("state",MODE_PRIVATE).edit()
                    .remove("pausedJob").remove("pausedRemaining").apply();
                refreshPauseControls();stateBadge.setText("READY");
                statusText.setText("Paused queue discarded. Completed files are unchanged.");
            }).show());
        historyToggleButton.setOnClickListener(v->{
            boolean show=historyText.getVisibility()!=View.VISIBLE;
            historyText.setVisibility(show?View.VISIBLE:View.GONE);
            historyToggleButton.setText(show?"HIDE RECENT FILES ▴":"SHOW RECENT FILES ▾");
        });
        advancedButton.setOnClickListener(v->toggleAdvanced());
        findViewById(R.id.exportDiagnosticsButton).setOnClickListener(v->saveDiagnostics());
        findViewById(R.id.resetDefaultsButton).setOnClickListener(v->resetDefaults());
        testClip.setOnCheckedChangeListener((b,x)->refreshEstimate());
        startTrim.addTextChangedListener(new SimpleTextWatcher(()->{
            refreshEstimate();
            double sec=parseTime(startTrim.getText().toString());
            if(preview.duration()>0){
                if(usingProxy&&sec>=proxyBaseSeconds&&sec<proxyBaseSeconds+preview.duration()/1000.0)
                    preview.seekTo((int)((sec-proxyBaseSeconds)*1000));
                else if(!usingProxy)preview.seekTo((int)(sec*1000));
            }
        }));
        endTrim.addTextChangedListener(new SimpleTextWatcher(this::refreshEstimate));
        loadHistory(); style();
    }

    private void setupPreview(){
        preview.setListener(new PreviewTextureView.Listener(){
            @Override public void onReady(int w,int h,int d){
                fallback.setVisibility(View.GONE);
                preview.setVisibility(View.VISIBLE);
                findViewById(R.id.previewHint).setVisibility(View.GONE);
                if(source!=null)preview.setPreset(preset(),playerView);
                if(!usingProxy&&pendingSourceSeekSeconds>=0){
                    preview.seekTo((int)Math.min(Integer.MAX_VALUE,pendingSourceSeekSeconds*1000));
                    pendingSourceSeekSeconds=-1;
                }
                if(previewAutoplayWhenReady){
                    previewAutoplayWhenReady=false;
                    preview.play();
                }
                updatePlaybackButton();
            }
            @Override public void onFinished(){
                updatePlaybackButton();
                if(usingProxy&&source!=null&&!conversionActive){
                    // The rendered segment ended. Continue the original video without
                    // forcing another expensive FFmpeg encode into the playback loop.
                    switchToSource(proxyBaseSeconds+Math.max(0,preview.duration()/1000.0),false);
                    statusText.setText("Rendered segment ended • source playback available");
                }
            }
            @Override public void onError(){
                recordPreviewProblem(usingProxy?"Rendered preview player":"Source video player",
                    "Android MediaPlayer could not decode the selected stream");
                if(usingProxy){
                    double position=proxyBaseSeconds;
                    switchToSource(position,false);
                    statusText.setText("Rendered preview unsupported • switched to original source");
                }else if(source!=null&&!source.image){
                    statusText.setText("Android playback unavailable; extracting a still frame");
                    extractFrameAt(displayedPreviewSeconds);
                }
                updatePlaybackButton();
            }
        });
        playPreviewButton.setOnClickListener(v->togglePreviewPlayback());
        preview.setOnClickListener(v->togglePreviewPlayback());
        previewSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar bar,int p,boolean user){
                if(user&&source!=null&&!source.image){
                    displayedPreviewSeconds=source.durationSeconds*p/1000.0;
                    previewTime.setText(fmtTime(displayedPreviewSeconds));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar bar){previewSeeking=true;}
            @Override public void onStopTrackingTouch(SeekBar bar){
                if(source!=null&&!source.image){
                    double target=source.durationSeconds*bar.getProgress()/1000.0;
                    displayedPreviewSeconds=target;
                    previewTime.setText(fmtTime(target));
                    if(usingProxy){
                        double clipEnd=proxyBaseSeconds+Math.max(0,preview.duration()/1000.0);
                        if(target>=proxyBaseSeconds&&target<clipEnd&&preview.duration()>0){
                            preview.seekTo((int)((target-proxyBaseSeconds)*1000));
                        }else switchToSource(target,preview.isPlaying());
                    }else if(preview.duration()>0){
                        preview.seekTo((int)Math.min(Integer.MAX_VALUE,target*1000));
                    }else if(inputUri!=null){
                        pendingSourceSeekSeconds=target;
                        previewAutoplayWhenReady=false;
                        preview.setEncodedPreview(false);
                        preview.setVideo(inputUri);
                    }
                    statusText.setText("Seek: "+fmtTime(target)+" • original source playback");
                }
                previewSeeking=false;
            }
        });
    }

    private void switchToSource(double position,boolean autoplay){
        if(inputUri==null)return;
        ++previewJobVersion;
        handler.removeCallbacks(previewRebuild);
        if(activePreviewSessionId>0){FFmpegKit.cancel(activePreviewSessionId);activePreviewSessionId=-1;}
        usingProxy=false;generatingProxy=false;
        previewAutoplayWhenReady=autoplay;
        pendingSourceSeekSeconds=Math.max(0,position);
        displayedPreviewSeconds=pendingSourceSeekSeconds;
        preview.setEncodedPreview(false);
        fallback.setVisibility(View.GONE);
        preview.setVisibility(View.VISIBLE);
        preview.setVideo(inputUri);
        updatePlaybackButton();
    }

    private void togglePreviewPlayback(){
        if(source==null||source.image||conversionActive)return;
        if(fallback.getVisibility()==View.VISIBLE){
            switchToSource(displayedPreviewSeconds,true);
            statusText.setText("Starting original video playback…");
            return;
        }
        if(preview.duration()>0){
            preview.toggle();
            updatePlaybackButton();
            if(!usingProxy)statusText.setText("Playing original source • Refresh renders a calibrated 10s preview");
        }else{
            previewAutoplayWhenReady=true;
            if(!usingProxy&&!generatingProxy&&inputUri!=null){
                pendingSourceSeekSeconds=displayedPreviewSeconds;
                preview.setEncodedPreview(false);
                preview.setVideo(inputUri);
            }
            statusText.setText("Preparing original video playback…");
        }
    }
    private void selfTest(){
        stateBadge.setText("CHECKING");
        FFmpegKit.executeAsync("-hide_banner -encoders",session->runOnUiThread(()->{
            String o=session.getOutput();
            if(o!=null&&o.contains(" amv ")&&o.contains("adpcm_ima_amv")){
                if(!ConversionService.isWorking())stateBadge.setText(
                    getSharedPreferences("state",MODE_PRIVATE).getInt("pausedRemaining",0)>0?"PAUSED":"READY");
                statusText.setText("Engine self-test passed.");}
            else{stateBadge.setText("WARNING");statusText.setText("Required AMV encoder is unavailable.");}
        }));
    }

    private void pickOne(){
        if(conversionActive)return;
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"video/*","image/jpeg","image/png","video/x-matroska","video/x-msvideo","application/octet-stream"});
        startActivityForResult(i,PICK_ONE);
    }

    private void pickQueue(){
        if(conversionActive)return;
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("*/*"); i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);
        i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"video/*","image/jpeg","image/png","video/x-matroska","video/x-msvideo","application/octet-stream"});
        startActivityForResult(i,PICK_QUEUE);
    }

    private void chooseOutput(){
        if(inputUri==null||source==null||conversionActive)return;
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType(source.image?"image/jpeg":"application/octet-stream");
        i.putExtra(Intent.EXTRA_TITLE,FileNameCleaner.clean(displayName(inputUri),effectiveType(source,inputUri),source.image)); startActivityForResult(i,PICK_OUTPUT);
    }
    private void chooseTree(){startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE),PICK_TREE);}
    private void saveDiagnostics(){Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("text/plain");i.putExtra(Intent.EXTRA_TITLE,"ClipAMV-diagnostics.txt");startActivityForResult(i,SAVE_DIAG);}

    @Override protected void onActivityResult(int req,int result,Intent data){
        super.onActivityResult(req,result,data); if(result!=RESULT_OK||data==null)return;
        if(req==PICK_QUEUE){
            queueUris.clear(); queueInfo.clear(); queueGeneration++;queueReady=false;
            if(data.getClipData()!=null)for(int i=0;i<data.getClipData().getItemCount();i++)queueUris.add(data.getClipData().getItemAt(i).getUri());
            else if(data.getData()!=null)queueUris.add(data.getData());
            for(Uri u:queueUris)takePerm(u,data.getFlags());
            if(!queueUris.isEmpty())enterQueueMode();
            return;
        }
        Uri uri=data.getData(); if(uri==null)return;
        if(req==PICK_ONE){
            takePerm(uri,data.getFlags()); queueGeneration++; queueReady=false;queueUris.clear(); queueInfo.clear(); queuePanel.setVisibility(View.GONE); convertButton.setVisibility(View.VISIBLE);
            inspect(uri,false);
        }else if(req==PICK_OUTPUT){takePerm(uri,data.getFlags());startSingle(uri);}
        else if(req==PICK_TREE){takePerm(uri,data.getFlags());startQueue(uri,false);}
        else if(req==SAVE_DIAG){
            try(OutputStream os=getContentResolver().openOutputStream(uri)){if(os!=null)os.write((latestDiagnostics+"\nLast FFmpeg error: "+
                        getSharedPreferences("state",MODE_PRIVATE).getString("lastFfmpegReason","none")+
                        "\n\nFFmpeg error log:\n"+
                        getSharedPreferences("state",MODE_PRIVATE).getString("lastFfmpegLog","")+
                        "\n\nPreview error:\n"+getSharedPreferences("state",MODE_PRIVATE)
                            .getString("lastPreviewError",previewDiagnostic)+
                        "\n\nCurrent status: "+statusText.getText()+
                        "\nPlayback: "+(usingProxy?"rendered segment":"original source")+
                        "\nPlayback prepared: "+(preview.duration()>0)+
                        "\nLast position: "+fmtTime(displayedPreviewSeconds)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        statusText.setText("Diagnostics saved.");}
            catch(Exception e){statusText.setText("Could not save diagnostics.");}
        }
    }

    private void enterQueueMode(){
        queuePanel.setVisibility(View.VISIBLE); convertButton.setVisibility(View.GONE); queueAuto=true;queueReady=false;queueDownloadsButton.setEnabled(false);queueFolderButton.setEnabled(false);
        queueHeader.setText("QUEUE • "+queueUris.size()+" FILES • INSPECTING"); queueList.removeAllViews();
        resetPreviewState(); fileText.setText(queueUris.size()+" files selected"); statusText.setText("Inspecting queue before conversion…");
        analyzeQueue();
    }

    private void analyzeQueue(){
        final long gen=++queueGeneration;
        new Thread(()->{
            PresetEngine.SourceInfo first=null;
            for(int i=0;i<queueUris.size();i++){
                if(gen!=queueGeneration||conversionActive)return;
                Uri u=queueUris.get(i); PresetEngine.SourceInfo info=probe(u);
                if(info!=null){synchronized(queueInfo){queueInfo.put(u.toString(),info);}}
                PresetEngine.SourceInfo finalFirst=first;
                if(first==null&&info!=null)first=info;
                int idx=i; PresetEngine.SourceInfo finfo=info;
                runOnUiThread(()->{if(gen==queueGeneration&&!conversionActive)addQueueRow(idx,u,finfo,finalFirst);});
            }
            runOnUiThread(()->{
                if(gen!=queueGeneration||conversionActive)return;
                queueReady=true;queueDownloadsButton.setEnabled(true);queueFolderButton.setEnabled(true);
                queueHeader.setText("QUEUE • "+queueUris.size()+" FILES");
                refreshQueueEstimate(); statusText.setText("Queue inspected. Tap any item to preview it.");
                if(!queueUris.isEmpty())inspect(queueUris.get(0),true);
            });
        }).start();
    }

    private void addQueueRow(int index,Uri uri,PresetEngine.SourceInfo info,PresetEngine.SourceInfo first){
        Button b=new Button(this); b.setAllCaps(false); b.setBackgroundResource(R.drawable.button_selector); b.setTextColor(getColor(R.color.text));
        String name=displayName(uri); String meta="inspect failed";
        if(info!=null){
            PresetEngine.Type itemType=queueAuto?AutoSelection.choose(info,name):effectiveType(info,uri);
            PresetEngine.Preset rec=PresetEngine.choose(info,itemType,mode,heightOverride,fpsOverride,preserve43,portraitMode,null,frameMode,effectivePicture());
            // Queue is inspected for metadata only. Black-bar detection is done later
            // per file in ConversionService, so this is not the final picture size.
            meta=info.width+"×"+info.height+" • "+String.format(Locale.US,"%.3f",info.fps)+
                " fps • estimated picture "+rec.pictureWidth+"×"+rec.pictureHeight+
                " • exact size appears when encoding";
        }
        boolean odd=first!=null&&info!=null&&(Math.abs(first.ratio()-info.ratio())>0.08||Math.abs(first.fps-info.fps)>3.0);
        b.setText((index+1)+". "+name+"\n"+meta+(odd?"  ⚠ differs":""));
        b.setOnClickListener(v->inspect(uri,true)); queueList.addView(b);
    }

    private void rebuildQueueRows(){
        if(queuePanel.getVisibility()!=View.VISIBLE||queueInfo.isEmpty())return;
        queueList.removeAllViews();
        PresetEngine.SourceInfo first=null;
        for(int i=0;i<queueUris.size();i++){
            Uri u=queueUris.get(i); PresetEngine.SourceInfo info=queueInfo.get(u.toString());
            addQueueRow(i,u,info,first);
            if(first==null&&info!=null)first=info;
        }
    }

    private void inspect(Uri uri,boolean fromQueue){
        if(conversionActive)return;
        long gen=++sourceGeneration;
        inputUri=uri;source=null;resetPreviewState();crop=null;
        findViewById(R.id.previewPanel).setVisibility(View.GONE);
        sourceText.setVisibility(View.GONE);estimateText.setVisibility(View.GONE);
        stateBadge.setText("INSPECTING");
        convertButton.setEnabled(false);
        statusText.setText("Inspecting source…");
        new Thread(()->{
            PresetEngine.SourceInfo info=probe(uri);
            runOnUiThread(()->{
                if(gen!=sourceGeneration)return;
                if(info==null){
                    stateBadge.setText("WARNING");
                    statusText.setText("Could not inspect file. Export Diagnostics for details.");
                    return;
                }
                source=info;
                findViewById(R.id.previewPanel).setVisibility(View.VISIBLE);
                sourceText.setVisibility(View.VISIBLE);estimateText.setVisibility(View.VISIBLE);
                autoPicture=PresetEngine.PictureMode.SHARP;
                autoReason=pictureMode==PresetEngine.PictureMode.AUTO?
                    "Analyzing in background":"Manual processing selected";
                // Automatic source selection is resolved without overriding manual choices.
                fileText.setText((fromQueue?"Queue preview • ":"")+displayName(uri));
                sourceText.setText(sourceBlock(source));
                startTrim.setText("");
                endTrim.setText(source.image?"":fmtTime(source.durationSeconds));
                stateBadge.setText("READY");
                convertButton.setEnabled(!fromQueue);
                findViewById(R.id.previewHint).setVisibility(View.GONE);
                requestedPreviewSeconds=0;
                if(source.image){
                    extractFrameAt(0);
                }else{
                    previewAutoplayWhenReady=false;
                    pendingSourceSeekSeconds=0;
                    preview.setEncodedPreview(false);
                    preview.setVideo(uri);
                }
                refresh();
                statusText.setText(source.image?"Image preview ready":
                    "Ready • ▶ plays source • Refresh renders exact AMV framing");
            });
            if(info!=null&&pictureMode==PresetEngine.PictureMode.AUTO){
                PictureAnalysis.Result choice=PictureAnalysis.analyze(this,uri,info);
                runOnUiThread(()->{
                    if(gen!=sourceGeneration||conversionActive||
                        pictureMode!=PresetEngine.PictureMode.AUTO)return;
                    autoPicture=choice.mode;autoReason=choice.reason;
                    refresh();
                });
            }
        }).start();
    }

    private PresetEngine.SourceInfo probe(Uri uri){
        String saf=FFmpegKitConfig.getSafParameterForRead(this,uri);
        String cmd="-v error -show_entries stream=index,codec_type,codec_name,width,height,avg_frame_rate,sample_aspect_ratio,display_aspect_ratio:stream_side_data=rotation:stream_tags=rotate -show_entries format=duration -of default=noprint_wrappers=1 "+q(saf);
        FFprobeSession ss=FFprobeKit.execute(cmd); String out=ss.getOutput();
        if(out==null||out.trim().isEmpty()){
            recordPreviewProblem("Source inspection",lastErrorLine(ss.getAllLogsAsString(),
                "FFprobe produced no metadata"));
            return null;
        }
        try{
            PresetEngine.SourceInfo s=new PresetEngine.SourceInfo(); String ct="",pendingCodec=""; s.audioPresent=false;
            for(String raw:out.split("\\r?\\n")){
                String line=raw.trim();
                if(line.startsWith("index=")){ct="";pendingCodec="";}
                else if(line.startsWith("codec_type=")){
                    ct=line.substring(11);
                    if(!pendingCodec.isEmpty()){
                        if("video".equals(ct))s.videoCodec=pendingCodec;
                        else if("audio".equals(ct)){s.audioCodec=pendingCodec;s.audioPresent=true;}
                    }
                }else if(line.startsWith("codec_name=")){
                    pendingCodec=line.substring(11);
                    if("video".equals(ct))s.videoCodec=pendingCodec;
                    else if("audio".equals(ct)){s.audioCodec=pendingCodec;s.audioPresent=true;}
                }
                else if(line.startsWith("width=")&&s.width==0)s.width=Integer.parseInt(line.substring(6));
                else if(line.startsWith("height=")&&s.height==0)s.height=Integer.parseInt(line.substring(7));
                else if(line.startsWith("sample_aspect_ratio=")) {double r=line.endsWith("N/A")?0:rational(line.substring(20).replace(':','/'));if(r>0)s.sampleAspect=r;}
                else if(line.startsWith("rotation="))s.rotation=(int)Math.round(Double.parseDouble(line.substring(9)));
                else if(line.startsWith("TAG:rotate="))s.rotation=(int)Math.round(Double.parseDouble(line.substring(11)));
                else if(line.startsWith("avg_frame_rate=")&&s.fps==0)s.fps=rational(line.substring(15));
                else if(line.startsWith("duration=")&&!line.endsWith("N/A"))s.durationSeconds=Double.parseDouble(line.substring(9));
            }
            String mime=getContentResolver().getType(uri); s.image=mime!=null&&mime.startsWith("image/");
            if(s.image){s.fps=1;s.durationSeconds=0;s.audioPresent=false;}
            if(s.width<=0||s.height<=0)return null; if(!s.image&&s.fps<=0)s.fps=25; s.inputBytes=size(uri); return s;
        }catch(Exception e){
            recordPreviewProblem("Source inspection parse",e.toString());
            return null;
        }
    }

    private void resetPreviewState(){
        ++previewJobVersion;handler.removeCallbacks(previewRebuild);
        if(activePreviewSessionId>0){FFmpegKit.cancel(activePreviewSessionId);activePreviewSessionId=-1;}
        usingProxy=false; generatingProxy=false; lastResolvedPreviewPreset=null; proxyBaseSeconds=0; requestedPreviewSeconds=-1;
        pendingPreviewSeek=-1;pendingSourceSeekSeconds=-1;previewAutoplayWhenReady=false;
        displayedPreviewSeconds=0;preview.clearSource();
        findViewById(R.id.previewHint).setVisibility(View.VISIBLE); fallback.setImageDrawable(null); fallback.setVisibility(View.GONE); preview.setVisibility(View.VISIBLE);
        previewSeek.setProgress(0); previewTime.setText("0:00"); playPreviewButton.setText("▶");
        File[] cache=getCacheDir().listFiles(); if(cache!=null)for(File f:cache)if(f.getName().startsWith("clipamv_proxy_")||f.getName().startsWith("clipamv_frame_"))f.delete();
    }

    private void refreshPreviewSource(){
        if(source==null||conversionActive)return;
        requestPreviewAt(Math.max(0,displayedPreviewSeconds));
    }
    private void requestPreviewAt(double second){
        if(source==null||conversionActive)return;
        requestedPreviewSeconds=Math.max(0,Math.min(Math.max(0,source.durationSeconds-1.0),second));
        handler.removeCallbacks(previewRebuild);
        handler.postDelayed(previewRebuild,300);
    }
    private void generateRawProxy(){
        if(conversionActive||source==null||inputUri==null)return;
        if(source.image){extractFrameAt(0);return;}
        long job=++previewJobVersion,gen=sourceGeneration;
        if(activePreviewSessionId>0)FFmpegKit.cancel(activePreviewSessionId);
        generatingProxy=true;
        String saf=FFmpegKitConfig.getSafParameterForRead(this,inputUri);
        double ss=requestedPreviewSeconds>=0?requestedPreviewSeconds:0;
        final PresetEngine.SourceInfo selectedInfo=source;
        final PresetEngine.Type selectedType=effectiveType(source,inputUri);
        final PresetEngine.Mode selectedMode=mode;
        final int selectedHeight=heightOverride,selectedFps=fpsOverride;
        final boolean selected43=preserve43,selectedBars=autoBarsCheck.isChecked();
        final PresetEngine.PortraitMode selectedPortrait=portraitMode;
        final PresetEngine.Crop selectedManualCrop=crop;
        final PresetEngine.FrameMode selectedFraming=frameMode;
        final PresetEngine.PictureMode selectedProcessing=effectivePicture();
        final double selectedStart=parseTime(startTrim.getText().toString());
        final double selectedEnd=parseTime(endTrim.getText().toString());
        File out=new File(getCacheDir(),"clipamv_proxy_"+gen+"_"+job+".mp4");
        stateBadge.setText("PREVIEW");statusText.setText("Scanning framing, then rendering 320×240 preview…");
        final int subChoice=subtitleMode,subFont=subtitleSize;
        final Uri selectedSource=inputUri;
        new Thread(()->{
        final PresetEngine.Preset selected=FramePlanResolver.resolve(selectedInfo,selectedType,
                selectedMode,selectedHeight,selectedFps,selected43,selectedPortrait,
                selectedManualCrop,selectedFraming,selectedProcessing,selectedBars,
                selectedStart,selectedEnd,new FramePlanProbeAndroid(this,selectedSource,selectedInfo,
                ()->job!=previewJobVersion||gen!=sourceGeneration));
        if(job!=previewJobVersion||gen!=sourceGeneration)return;
        File subtitles=null;
        String filter=selected.filter;
        try{
            if(subChoice!=SubtitleSupport.OFF){
                subtitles=SubtitleSupport.extract(this,selectedSource,subChoice,ss,10);
                if(subtitles!=null)filter=SubtitleSupport.withSubtitles(filter,subtitles,subFont);
            }
        }catch(Exception e){
            final String why=e.getMessage();
            runOnUiThread(()->{
                if(job==previewJobVersion&&gen==sourceGeneration){
                    generatingProxy=false;stateBadge.setText("READY");
                    recordPreviewProblem("Subtitle preview",why);
                    statusText.setText("Subtitle preview failed: "+why);
                }
            });
            out.delete();return;
        }
        final File subtitlesFinal=subtitles;
        if(job!=previewJobVersion||gen!=sourceGeneration){if(subtitlesFinal!=null)subtitlesFinal.delete();return;}
        String cmd="-y -ss "+f3(ss)+" -i "+q(saf)+" -t 10 -vf "+q(filter)+
                " -map 0:v:0 -map 0:a:0? -r 15 -c:v mpeg4 -q:v 5 -pix_fmt yuv420p "+
                "-c:a aac -b:a 64k -movflags +faststart "+q(out.getAbsolutePath());
        FFmpegSession rendering=FFmpegKit.executeAsync(cmd,session->runOnUiThread(()->{
            if(subtitlesFinal!=null)subtitlesFinal.delete();
            if(job!=previewJobVersion||gen!=sourceGeneration){out.delete();return;}
            generatingProxy=false;stateBadge.setText("READY");activePreviewSessionId=-1;
            if(ReturnCode.isSuccess(session.getReturnCode())&&out.isFile()&&out.length()>0){
                usingProxy=true;proxyBaseSeconds=ss;
                lastResolvedPreviewPreset=selected;
                previewAutoplayWhenReady=false;
                preview.setEncodedPreview(true);
                fallback.setVisibility(View.GONE);preview.setVisibility(View.VISIBLE);
                preview.setVideo(Uri.fromFile(out));
                preview.setPreset(selected,playerView);
                statusText.setText("Filtered preview • "+selected.frameAnalysis+
                    " • Player View simulates Gen 2.");
            }else{
                String detail=session.getAllLogsAsString();
                recordPreviewProblem("FFmpeg preview render",lastErrorLine(detail,
                    "FFmpeg preview returned "+session.getReturnCode()));
                statusText.setText("Rendered preview failed • original video still playable");
                if(preview.duration()==0)extractFrameAt(ss);
            }
        }));
        activePreviewSessionId=rendering.getSessionId();
        },"clipamv-subtitle-preview").start();
    }
    private void extractFrameAt(double sec){
        if(conversionActive||inputUri==null||source==null)return;
        final long gen=sourceGeneration,job=previewJobVersion; String saf=FFmpegKitConfig.getSafParameterForRead(this,inputUri); File out=new File(getCacheDir(),"clipamv_frame_"+gen+"_"+job+".jpg");
        PresetEngine.Preset fallbackPreset=lastResolvedPreviewPreset!=null?lastResolvedPreviewPreset:preset();
        String cmd="-y "+(!source.image?"-ss "+f3(sec)+" ":"")+"-i "+q(saf)+" -frames:v 1 -vf "+q(fallbackPreset.filter)+" -q:v 2 "+q(out.getAbsolutePath());
        FFmpegKit.executeAsync(cmd,session->runOnUiThread(()->{
            if(gen!=sourceGeneration||job!=previewJobVersion)return; Bitmap b=BitmapFactory.decodeFile(out.getAbsolutePath());
            if(b!=null){fallback.setImageBitmap(playerView?playerBitmap(b):b);fallback.setVisibility(View.VISIBLE);preview.setVisibility(View.GONE);statusText.setText("Exact filtered frame loaded • ▶ plays source");}
            else {recordPreviewProblem("Still frame",lastErrorLine(session.getAllLogsAsString(),
                "Frame extraction returned "+session.getReturnCode()));statusText.setText("Preview unavailable • ▶ plays source");}
            stateBadge.setText("READY");
        }));
    }

    private Bitmap playerBitmap(Bitmap b){
        if(b.getWidth()!=320||b.getHeight()!=240)return b;
        Bitmap clipped=Bitmap.createBitmap(b,PresetEngine.PLAYER_VIEW_X,0,PresetEngine.PLAYER_VIEW_WIDTH,240);
        return Bitmap.createScaledBitmap(clipped,320,240,true);
    }
    private void refreshFallbackView(){
        if(fallback.getVisibility()==View.VISIBLE&&source!=null)extractFrameAt(displayedPreviewSeconds);
    }
    private double currentPreviewSeconds(){return Math.max(0,displayedPreviewSeconds);}

    private void detectBars(){
        if(queuePanel.getVisibility()==View.VISIBLE){statusText.setText("Queue auto-framing is source-aware. Detect Bars is applied to the selected preview file only.");return;}
        if(conversionActive||inputUri==null||source==null||source.image)return; final long gen=sourceGeneration; statusText.setText("Detecting black bars…");
        double ss=Math.max(0,source.durationSeconds/2-4); String saf=FFmpegKitConfig.getSafParameterForRead(this,inputUri); final StringBuilder logs=new StringBuilder();
        FFmpegKit.executeAsync("-ss "+f3(ss)+" -i "+q(saf)+" -t 8 -vf cropdetect=limit=24:round=16:reset=0 -an -f null -",done->runOnUiThread(()->{
            if(gen!=sourceGeneration)return; Matcher m=Pattern.compile("crop=(\\d+):(\\d+):(\\d+):(\\d+)").matcher(logs.toString()); PresetEngine.Crop last=null;
            while(m.find())last=new PresetEngine.Crop(Integer.parseInt(m.group(1)),Integer.parseInt(m.group(2)),Integer.parseInt(m.group(3)),Integer.parseInt(m.group(4)));
            if(PresetEngine.isMeaningfulCrop(source,last)){crop=last;cropText.setText("Detected: "+last);refresh();}
            else cropText.setText("No stable black-bar crop detected.");
        }),log->logs.append(log.getMessage()).append('\n'),stats->{});
    }

    private PresetEngine.PictureMode effectivePicture(){return pictureMode==PresetEngine.PictureMode.AUTO?autoPicture:pictureMode;}
    private PresetEngine.Type effectiveType(PresetEngine.SourceInfo info,Uri uri){
        return typeAuto && info!=null?AutoSelection.choose(info,uri==null?"":displayName(uri)):type;
    }
    private PresetEngine.Preset preset(){return PresetEngine.choose(source,effectiveType(source,inputUri),mode,heightOverride,fpsOverride,preserve43,portraitMode,crop,frameMode,effectivePicture());}
    private void selectPicture(PresetEngine.PictureMode which){
        pictureMode=which;
        getSharedPreferences("state",MODE_PRIVATE).edit().putInt("picture",which.ordinal()).apply();
        if(which==PresetEngine.PictureMode.AUTO&&source!=null&&!conversionActive){
            final long gen=sourceGeneration; final Uri uri=inputUri; final PresetEngine.SourceInfo info=source;
            style();pictureInfo.setText("Auto • analyzing source frames…");
            new Thread(()->{
                PictureAnalysis.Result r=PictureAnalysis.analyze(this,uri,info);
                runOnUiThread(()->{if(gen==sourceGeneration&&pictureMode==PresetEngine.PictureMode.AUTO&&!conversionActive){
                    autoPicture=r.mode;autoReason=r.reason;refresh();
                }});
            }).start();
            return; // Don't render a preview in parallel with picture analysis.
        }
        style();refresh();
    }
    private void comparePicture(){
        if(inputUri==null||source==null||conversionActive){statusText.setText("Choose a video before comparing.");return;}
        final long gen=sourceGeneration;
        final Uri uri=inputUri;
        final double pos=Math.max(0,usingProxy?proxyBaseSeconds+preview.position()/1000.0:currentPreviewSeconds());
        final PresetEngine.SourceInfo info=source;
        final PresetEngine.Type t=effectiveType(info,uri);
        final PresetEngine.Mode m=mode;
        final int hh=heightOverride,ff=fpsOverride;
        final boolean p43=preserve43,view=playerView;
        final PresetEngine.PortraitMode portrait=portraitMode;
        final PresetEngine.Crop c=crop;
        final PresetEngine.FrameMode frame=frameMode;
        pictureCompareButton.setEnabled(false);
        statusText.setText("Rendering Sharp and Smooth from the same frame…");
        final boolean compareAutoBars=autoBarsCheck.isChecked();
        final double compareStart=parseTime(startTrim.getText().toString());
        final double compareEnd=parseTime(endTrim.getText().toString());
        new Thread(()->{
            Bitmap[] images=new Bitmap[2];
            PresetEngine.Preset calibrated=FramePlanResolver.resolve(info,t,m,hh,ff,p43,portrait,
                    c,frame,PresetEngine.PictureMode.SHARP,compareAutoBars,compareStart,compareEnd,
                    new FramePlanProbeAndroid(this,uri,info,()->gen!=sourceGeneration));
            for(int i=0;i<2;i++){
                String saf=FFmpegKitConfig.getSafParameterForRead(this,uri);
                PresetEngine.PictureMode pm=i==0?PresetEngine.PictureMode.SHARP:PresetEngine.PictureMode.SMOOTH;
                PresetEngine.Preset selected=PresetEngine.choose(info,t,m,hh,ff,p43,portrait,
                    calibrated.detectedCrop,frame,pm);
                File out=new File(getCacheDir(),"clipamv_compare_"+gen+"_"+i+".png");
                String cmd="-y "+(!info.image?"-ss "+f3(pos)+" ":"")+"-i "+q(saf)+
                    " -frames:v 1 -vf "+q(selected.filter)+" -an "+q(out.getAbsolutePath());
                FFmpegSession render=FFmpegKit.execute(cmd);
                if(ReturnCode.isSuccess(render.getReturnCode()))images[i]=BitmapFactory.decodeFile(out.getAbsolutePath());
                out.delete();
            }
            runOnUiThread(()->{
                pictureCompareButton.setEnabled(true);
                if(gen!=sourceGeneration||conversionActive)return;
                if(images[0]==null||images[1]==null){statusText.setText("Comparison failed. Try Refresh Preview.");return;}
                LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.HORIZONTAL);panel.setPadding(8,12,8,12);
                for(int i=0;i<2;i++){
                    LinearLayout side=new LinearLayout(this);side.setOrientation(LinearLayout.VERTICAL);
                    side.setPadding(4,0,4,0);
                    TextView title=new TextView(this);title.setText(i==0?"SHARP • LANCZOS":"SMOOTH • BICUBIC");title.setTextSize(11);
                    ImageView img=new ImageView(this);img.setAdjustViewBounds(true);img.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    Bitmap bmp=view?playerBitmap(images[i]):images[i];img.setImageBitmap(bmp);
                    side.addView(title);side.addView(img,new LinearLayout.LayoutParams(-1,-2));
                    panel.addView(side,new LinearLayout.LayoutParams(0,-2,1));
                }
                new android.app.AlertDialog.Builder(this)
                    .setTitle("Same frame • player preview")
                    .setMessage("Compare scaling only. Actual AMV compression and MP3 screen may differ slightly.")
                    .setView(panel)
                    .setPositiveButton("Use Sharp",(d,w)->selectPicture(PresetEngine.PictureMode.SHARP))
                    .setNegativeButton("Use Smooth",(d,w)->selectPicture(PresetEngine.PictureMode.SMOOTH))
                    .setNeutralButton("Close",(d,w)->{})
                    .show();
                statusText.setText("Comparison rendered. Choose your preferred processing.");
            });
        }).start();
    }

    private void refresh(){
        if(source==null)return; PresetEngine.Preset p=preset();
        reasonText.setText(PresetEngine.explain(source,effectiveType(source,inputUri),p)+
            " • X64 calibrated • picture "+p.pictureWidth+"×"+p.pictureHeight+
            (autoBarsCheck.isChecked()&&crop==null&&!source.image?" (estimated before scan)":" (selected)")+
            " • 320×240 AMV");
        outputNameText.setText("OUTPUT\n"+FileNameCleaner.clean(displayName(inputUri),effectiveType(source,inputUri),source.image));
        autoSummaryText.setText((typeAuto?"AUTO SELECTION":"CUSTOM SETTINGS")+"  •  "+effectiveType(source,inputUri).name().replace('_',' ')+
            "\nEstimated picture: "+p.pictureWidth+"×"+p.pictureHeight+" • "+p.outputFps+" fps • "+
            (pictureMode==PresetEngine.PictureMode.AUTO?"picture Auto":pictureMode.name())+
            "\nOutput: "+FileNameCleaner.displayBase(displayName(inputUri),effectiveType(source,inputUri),source.image));
        presetText.setText("Preset Engine "+PresetEngine.PRESET_VERSION+"\n"+p.name+"\n"+p.filter+(p.q>0?"\nq="+p.q:""));
        latestDiagnostics=diagnostics(p); style(); refreshPreview(); refreshEstimate(); refreshQueueEstimate(); if(queueInfo.size()==queueUris.size()&&!queueInfo.isEmpty())rebuildQueueRows();
    }

    private void refreshPreview(){
        if(source==null||conversionActive)return;
        lastResolvedPreviewPreset=null;
        preview.setPreset(preset(),playerView);
        // No automatic 10-second re-encode every time the user changes a setting.
        // Play stays responsive; Refresh explicitly renders the exact AMV framing.
        if(usingProxy){
            double resume=proxyBaseSeconds+preview.position()/1000.0;
            switchToSource(resume,false);
            statusText.setText("Settings changed • showing source; tap Refresh for updated AMV preview");
        }
        if(fallback.getVisibility()==View.VISIBLE)extractFrameAt(displayedPreviewSeconds);
    }

    private void refreshEstimate(){
        if(source==null)return; PresetEngine.Preset p=preset(); double st=parseTime(startTrim.getText().toString()),en=parseTime(endTrim.getText().toString());
        double runtime=source.image?0:testClip.isChecked()?(source.durationSeconds>st?Math.min(30,source.durationSeconds-st):30):(en>st?en-st:Math.max(0,source.durationSeconds-st)); long est=PresetEngine.estimateBytes(source,p,runtime);
        estimateText.setText("STORAGE\nInput: "+human(source.inputBytes)+"\nEstimated output: "+human((long)(est*.90))+"–"+human((long)(est*1.10))+
                (source.image?"":"\nRuntime: "+fmtTime(runtime)+" • "+p.outputFps+" fps • "+mode.name().replace('_',' '))+"\nReserve: "+human((long)(est*1.10)));
    }

    private void refreshQueueEstimate(){
        if(queuePanel.getVisibility()!=View.VISIBLE||queueInfo.isEmpty())return;
        long in=0,out=0; int ok=0;
        synchronized(queueInfo){for(Uri uri:queueUris){
            PresetEngine.SourceInfo info=queueInfo.get(uri.toString());
            if(info==null)continue; in+=info.inputBytes; ok++;
            PresetEngine.Type t=queueAuto?AutoSelection.choose(info,displayName(uri)):effectiveType(info,inputUri);
            int h=heightOverride, f=fpsOverride;
            PresetEngine.Preset p=PresetEngine.choose(info,t,mode,h,f,preserve43,portraitMode,null,frameMode,effectivePicture());
            out+=PresetEngine.estimateBytes(info,p,info.durationSeconds);
        }}
        queueHeader.setText("QUEUE • "+queueUris.size()+" FILES\nInput "+human(in)+" • Est. "+human((long)(out*.90))+"–"+human((long)(out*1.10))+" • "+(queueAuto?"Auto each file":"Same settings")+
                "\nExact picture size appears once each video is scanned for encoding.");
    }

    private void style(){
        Button[] content={youtubeButton,movieButton,cartoonButton,portraitButton}; for(Button b:content)b.setSelected(false);if(!typeAuto)content[type.ordinal()].setSelected(true);autoTypeButton.setSelected(typeAuto);
        spaceSaverButton.setSelected(mode==PresetEngine.Mode.SPACE_SAVER); standardButton.setSelected(mode==PresetEngine.Mode.STANDARD); qualityButton.setSelected(mode==PresetEngine.Mode.QUALITY);
        perfStandardButton.setSelected(performanceMode==0);perfFasterButton.setSelected(performanceMode==1);perfMaximumButton.setSelected(performanceMode==2);
        autoFrameButton.setSelected(frameMode==PresetEngine.FrameMode.AUTO);fitFrameButton.setSelected(frameMode==PresetEngine.FrameMode.FIT);balancedFrameButton.setSelected(frameMode==PresetEngine.FrameMode.BALANCED);fillFrameButton.setSelected(frameMode==PresetEngine.FrameMode.FILL);
        pictureAutoButton.setSelected(pictureMode==PresetEngine.PictureMode.AUTO);
        pictureSharpButton.setSelected(pictureMode==PresetEngine.PictureMode.SHARP);
        pictureSmoothButton.setSelected(pictureMode==PresetEngine.PictureMode.SMOOTH);
        subsOffButton.setSelected(subtitleMode==SubtitleSupport.OFF);
        subsForcedButton.setSelected(subtitleMode==SubtitleSupport.FORCED);
        subsTextButton.setSelected(subtitleMode==SubtitleSupport.FIRST_TEXT);
        subsNormalButton.setSelected(subtitleSize==SubtitleSupport.NORMAL);
        subsLargeButton.setSelected(subtitleSize==SubtitleSupport.LARGE);
        pictureInfo.setText(pictureMode==PresetEngine.PictureMode.AUTO?
            "AUTO → "+autoPicture.name()+" • "+autoReason:
            pictureMode==PresetEngine.PictureMode.SHARP?"SHARP • Lanczos edge detail":"SMOOTH • Bicubic gentle scaling");
        int[] hs={216,208,200,192,184};for(int i=0;i<heightButtons.length;i++)heightButtons[i].setSelected(heightOverride==hs[i]);
        int[] fs={15,25,30,0};for(int i=0;i<fpsButtons.length;i++)fpsButtons[i].setSelected(fpsOverride==fs[i]);
        full43.setSelected(!preserve43);preserve43Button.setSelected(preserve43);portraitFit.setSelected(portraitMode==PresetEngine.PortraitMode.FIT);portraitZoom.setSelected(portraitMode==PresetEngine.PortraitMode.ZOOM);portraitRotate.setSelected(portraitMode==PresetEngine.PortraitMode.ROTATE);
        playerViewButton.setSelected(playerView);rawViewButton.setSelected(!playerView);queueAutoButton.setSelected(queueAuto);queueSameButton.setSelected(!queueAuto);
        boolean is43=source!=null&&source.isFourThree();findViewById(R.id.fourThreeRow).setVisibility(is43?View.VISIBLE:View.GONE);findViewById(R.id.portraitRow).setVisibility(effectiveType(source,inputUri)==PresetEngine.Type.PORTRAIT?View.VISIBLE:View.GONE);findViewById(R.id.frameScroller).setVisibility((is43||effectiveType(source,inputUri)==PresetEngine.Type.PORTRAIT)?View.GONE:View.VISIBLE);
    }

    private void startSingle(Uri out){
        if(inputUri==null||source==null||conversionActive)return;
        Intent i=baseServiceIntent();i.putExtra(ConversionService.EXTRA_INPUT,inputUri.toString());
        if(out!=null)i.putExtra(ConversionService.EXTRA_OUTPUT,out.toString());
        else i.putExtra(ConversionService.EXTRA_DOWNLOADS,true);
        startServiceCompat(i);
    }
    private void startSingleDownloads(){
        if(Build.VERSION.SDK_INT<29){chooseOutput();return;}
        startSingle(null);
    }
    private void startQueueDownloads(){
        if(!queueReady||conversionActive){statusText.setText("Inspecting files before queue conversion. Please retry when ready.");return;}
        if(Build.VERSION.SDK_INT<29){statusText.setText("Direct Downloads requires Android 10+. Choose another folder.");chooseTree();return;}
        long need=(long)(queueEstimatedBytes()*1.10);
        try{
            StatFs fs=new StatFs(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath());
            long free=fs.getAvailableBytes();
            if(need>0&&free<need){statusText.setText("Not enough free space. Need about "+human(need)+" • free "+human(free));stateBadge.setText("WARNING");return;}
        }catch(Exception ignored){}
        startQueue(null,true);
    }
    private long queueEstimatedBytes(){
        long out=0;
        synchronized(queueInfo){for(Uri uri:queueUris){
            PresetEngine.SourceInfo info=queueInfo.get(uri.toString());
            if(info==null)continue;
            PresetEngine.Type t=queueAuto?AutoSelection.choose(info,displayName(uri)):effectiveType(info,inputUri);
            PresetEngine.Preset p=PresetEngine.choose(info,t,mode,heightOverride,fpsOverride,preserve43,portraitMode,null,frameMode,effectivePicture());
            out+=PresetEngine.estimateBytes(info,p,info.durationSeconds);
        }}
        return out;
    }
    private void startQueue(Uri tree,boolean downloads){
        if(queueUris.isEmpty()||!queueReady||conversionActive)return; Intent i=baseServiceIntent(); ArrayList<String> arr=new ArrayList<>();for(Uri u:queueUris)arr.add(u.toString());
        i.putStringArrayListExtra(ConversionService.EXTRA_INPUTS,arr); if(tree!=null)i.putExtra(ConversionService.EXTRA_TREE,tree.toString());
        i.putExtra(ConversionService.EXTRA_DOWNLOADS,downloads); i.putExtra(ConversionService.EXTRA_QUEUE_AUTO,queueAuto);
        i.putExtra("wasQueue",true);
        i.removeExtra("manualCropW");i.removeExtra("manualCropH");i.removeExtra("manualCropX");i.removeExtra("manualCropY");
        i.putExtra(ConversionService.EXTRA_START,0.0);i.putExtra(ConversionService.EXTRA_END,0.0);i.putExtra(ConversionService.EXTRA_TEST,false);
        startServiceCompat(i);
    }

    private Intent baseServiceIntent(){
        Intent i=new Intent(this,ConversionService.class); i.putExtra(ConversionService.EXTRA_TYPE,type.ordinal());i.putExtra(ConversionService.EXTRA_MODE,mode.ordinal());
        i.putExtra(ConversionService.EXTRA_PERFORMANCE,performanceMode);
        i.putExtra(ConversionService.EXTRA_FRAME_MODE,frameMode.ordinal());
        i.putExtra(ConversionService.EXTRA_PICTURE_MODE,pictureMode.ordinal());
        i.putExtra(ConversionService.EXTRA_AUTO_TYPE,typeAuto);
        i.putExtra(ConversionService.EXTRA_SUBTITLE_MODE,subtitleMode);
        i.putExtra(ConversionService.EXTRA_SUBTITLE_SIZE,subtitleSize);
        if(queueUris.isEmpty()&&crop!=null){i.putExtra("manualCropW",crop.w);i.putExtra("manualCropH",crop.h);i.putExtra("manualCropX",crop.x);i.putExtra("manualCropY",crop.y);}
        i.putExtra(ConversionService.EXTRA_HEIGHT,heightOverride);i.putExtra(ConversionService.EXTRA_FPS,fpsOverride);i.putExtra(ConversionService.EXTRA_43,preserve43);i.putExtra(ConversionService.EXTRA_PORTRAIT,portraitMode.ordinal());i.putExtra(ConversionService.EXTRA_AUTOBARS,autoBarsCheck.isChecked());
        i.putExtra(ConversionService.EXTRA_START,parseTime(startTrim.getText().toString()));i.putExtra(ConversionService.EXTRA_END,parseTime(endTrim.getText().toString()));i.putExtra(ConversionService.EXTRA_TEST,testClip.isChecked());return i;
    }

    private void startServiceCompat(Intent i){
        if(ConversionService.isWorking()){statusText.setText("A conversion is already running");return;}
        conversionActive=true;queueGeneration++;previewJobVersion++;handler.removeCallbacks(previewRebuild);
        resultText.setVisibility(View.GONE);
        if(activePreviewSessionId>0){FFmpegKit.cancel(activePreviewSessionId);activePreviewSessionId=-1;}
        if(preview.isPlaying())preview.toggle();
        previewAutoplayWhenReady=false;
        updatePlaybackButton();
        convertButton.setEnabled(false);queueDownloadsButton.setEnabled(false);queueFolderButton.setEnabled(false);
        if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);
        stateBadge.setText("CONVERTING");cancelButton.setVisibility(View.VISIBLE);progress.setProgress(0);
        progress.setVisibility(View.VISIBLE);telemetryText.setVisibility(View.VISIBLE);
        pauseQueueButton.setEnabled(true);pauseQueueButton.setText("PAUSE AFTER CURRENT FILE");
        pauseQueueButton.setVisibility(i.hasExtra(ConversionService.EXTRA_INPUTS)||ConversionService.ACTION_RESUME.equals(i.getAction())?View.VISIBLE:View.GONE);
        resumeQueueButton.setVisibility(View.GONE);discardQueueButton.setVisibility(View.GONE);
        telemetryText.setText("CPU: "+(performanceMode==2?"Maximum":performanceMode==1?"Faster":"Standard")+" • Checking thermal/battery…");
        showPictureScanning();
        statusText.setText("Conversion started. Exact picture size will appear after scanning.");
    }
    private void setSubtitles(int newMode){
        if(conversionActive)return;
        subtitleMode=newMode;style();refreshPreview();
        statusText.setText(newMode==SubtitleSupport.OFF?"Embedded subtitle tracks disabled. Burned-in text cannot be removed.":
            "Text subtitles will be burned into converted AMVs; Refresh renders a subtitled preview.");
    }
    private void setSubtitleSize(int newSize){
        if(conversionActive)return;
        subtitleSize=newSize;style();refreshPreview();
    }
    private void setPerformance(int p){
        if(conversionActive)return;
        performanceMode=p;
        getSharedPreferences("state",MODE_PRIVATE).edit().putInt("performance",p).apply();
        style();
    }
    private void cancel(){Intent i=new Intent(this,ConversionService.class);i.setAction(ConversionService.ACTION_CANCEL);startService(i);}
    private void requestQueuePause(){
        if(!ConversionService.isWorking()&&!conversionActive)return;
        Intent pause=new Intent(this,ConversionService.class);
        pause.setAction(ConversionService.ACTION_PAUSE_AFTER_CURRENT);
        startService(pause);
        pauseQueueButton.setEnabled(false);
        pauseQueueButton.setText("PAUSE REQUESTED • FINISHING FILE");
        statusText.setText("Current file will finish and be verified before the queue pauses.");
    }
    private void resumeQueue(){
        if(ConversionService.isWorking())return;
        if(getSharedPreferences("state",MODE_PRIVATE).getString("pausedJob","").isEmpty()){
            refreshPauseControls();return;
        }
        Intent resume=new Intent(this,ConversionService.class);
        resume.setAction(ConversionService.ACTION_RESUME);
        startServiceCompat(resume);
        statusText.setText("Resuming remaining files. Completed AMVs are kept.");
    }
    private void refreshPauseControls(){
        if(pauseQueueButton==null||resumeQueueButton==null||discardQueueButton==null)return;
        boolean running=ConversionService.isWorking();
        int remaining=getSharedPreferences("state",MODE_PRIVATE).getInt("pausedRemaining",0);
        resumeQueueButton.setVisibility(!running&&remaining>0?View.VISIBLE:View.GONE);
        discardQueueButton.setVisibility(!running&&remaining>0?View.VISIBLE:View.GONE);
        if(running){
            boolean queue=getSharedPreferences("state",MODE_PRIVATE).getBoolean("activeQueue",false);
            pauseQueueButton.setVisibility(queue?View.VISIBLE:View.GONE);
        }else pauseQueueButton.setVisibility(View.GONE);
        if(remaining>0)resumeQueueButton.setText("RESUME "+remaining+" REMAINING FILE"+(remaining==1?"":"S"));
    }
    private void toggleAdvanced(){
        advanced=!advanced;
        int visible=advanced?View.VISIBLE:View.GONE;
        findViewById(R.id.customizePrimaryPanel).setVisibility(visible);
        findViewById(R.id.customizeOutputPanel).setVisibility(visible);
        findViewById(R.id.advancedPanel).setVisibility(visible);
        advancedButton.setText(advanced?"CUSTOMIZE ▴":"CUSTOMIZE ▾ • OPTIONAL SETTINGS");
    }
    private void resetDefaults(){typeAuto=true;type=PresetEngine.Type.YOUTUBE;mode=PresetEngine.Mode.STANDARD;heightOverride=0;fpsOverride=0;preserve43=false;crop=null;portraitMode=PresetEngine.PortraitMode.FIT;playerView=true;frameMode=PresetEngine.FrameMode.AUTO;pictureMode=PresetEngine.PictureMode.AUTO;subtitleMode=SubtitleSupport.OFF;subtitleSize=SubtitleSupport.LARGE;autoBarsCheck.setChecked(true);getSharedPreferences("state",MODE_PRIVATE).edit().putInt("picture",0).apply();queueAuto=true;setPerformance(0);refresh();}

    private String sourceBlock(PresetEngine.SourceInfo s){return "SOURCE\n"+s.width+"×"+s.height+"\n"+String.format(Locale.US,"%.3f fps",s.fps)+"\n"+String.format(Locale.US,"%.2f:1",s.ratio())+"\n"+(s.image?"IMAGE":fmtTime(s.durationSeconds))+"\nInput: "+human(s.inputBytes);}
    private String diagnostics(PresetEngine.Preset p){return "ClipAMV v0.7.7\nPreset Engine "+PresetEngine.PRESET_VERSION+"\nDevice: "+Build.MANUFACTURER+" "+Build.MODEL+"\nAndroid: "+Build.VERSION.RELEASE+"\nSource: "+(source==null?"-":source.width+"x"+source.height+" "+source.fps+"fps "+source.videoCodec+" / "+source.audioCodec)+"\nType: "+effectiveType(source,inputUri)+"\nMode: "+mode+"\nPreset: "+p.name+"\nFilter: "+p.filter+"\nFPS: "+p.outputFps+"\nQ: "+p.q+"\nSubtitles: "+subtitleMode+" size "+subtitleSize+"\nAuto bars: "+autoBarsCheck.isChecked()+
        "\nNative last encode: "+String.format(Locale.US,"%.2fx",getSharedPreferences("state",MODE_PRIVATE).getFloat("lastEncodeSpeed",0))+
        " ("+getSharedPreferences("state",MODE_PRIVATE).getLong("lastEncodeWallMs",0)+" ms encoder only)"+
        "\nLast encoded filter: "+getSharedPreferences("state",MODE_PRIVATE).getString("lastEncodeFilter","-")+
        "\nEquivalent built-in FFmpeg command (input/output placeholders): "+PresetEngine.buildVideoCommand(
            "INPUT","OUTPUT",p,parseTime(startTrim.getText().toString()),
            parseTime(endTrim.getText().toString()),testClip.isChecked(),performanceMode)+"\n";}
    private void loadHistory(){String h=getSharedPreferences("history",MODE_PRIVATE).getString("items","");
        // Keep the real .amv file extension; hide it only in the app's recent-file labels.
        h=h.replaceAll("(?i)\\.amv(?=\\s|$|—)","");
        historyText.setText("RECENT\n"+(h.isEmpty()?"No conversions yet.":h));}
    private void loadLastResult(){
        if(ConversionService.isWorking())return;
        String result=getSharedPreferences("state",MODE_PRIVATE).getString("lastResult","");
        resultText.setText(result);
        resultText.setVisibility(result.isEmpty()?View.GONE:View.VISIBLE);
    }
    private void takePerm(Uri uri,int flags){int f=flags&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);try{getContentResolver().takePersistableUriPermission(uri,f);}catch(Exception ignored){}}
    private String displayName(Uri uri){try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())return c.getString(0);}catch(Exception ignored){}return "media";}
    private long size(Uri uri){try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.SIZE},null,null,null)){if(c!=null&&c.moveToFirst()&&!c.isNull(0))return c.getLong(0);}catch(Exception ignored){}return 0;}
    private static String lastErrorLine(String log,String fallback){
        if(log==null||log.isEmpty())return fallback;
        String last=fallback;
        for(String line:log.split("\\r?\\n")){
            String low=line.toLowerCase(Locale.US);
            if(low.contains("error")||low.contains("failed")||low.contains("invalid")||
                    low.contains("unknown")||low.contains("unable"))last=line.trim();
        }
        return last.length()>350?last.substring(0,350):last;
    }
    private static double rational(String x){if(x.contains("/")){String[]p=x.split("/");double a=Double.parseDouble(p[0]),b=Double.parseDouble(p[1]);return b==0?0:a/b;}return Double.parseDouble(x);}
    private static double parseTime(String s){s=s.trim();if(s.isEmpty())return 0;try{String[]p=s.split(":");if(p.length==1)return Double.parseDouble(p[0]);if(p.length==2)return Double.parseDouble(p[0])*60+Double.parseDouble(p[1]);return Double.parseDouble(p[0])*3600+Double.parseDouble(p[1])*60+Double.parseDouble(p[2]);}catch(Exception e){return 0;}}
    private static String fmtTime(double s){long x=(long)Math.max(0,s),h=x/3600,m=(x%3600)/60,sec=x%60;return h>0?String.format(Locale.US,"%d:%02d:%02d",h,m,sec):String.format(Locale.US,"%d:%02d",m,sec);}
    private static String human(long b){if(b<=0)return "?";double m=b/1048576.0;return m>=1024?String.format(Locale.US,"%.2f GB",m/1024):String.format(Locale.US,"%.0f MB",m);}
    private static String q(String x){return "\""+x.replace("\"","\\\"")+"\"";}
    private static String f3(double x){return String.format(Locale.US,"%.3f",x);}

    @Override protected void onStart(){
        super.onStart();
        conversionActive=ConversionService.isWorking();
        if(!conversionActive)getSharedPreferences("state",MODE_PRIVATE).edit().putBoolean("jobActive",false).apply();
        if(conversionActive){
            progress.setVisibility(View.VISIBLE);telemetryText.setVisibility(View.VISIBLE);
            convertButton.setEnabled(false);queueDownloadsButton.setEnabled(false);queueFolderButton.setEnabled(false);
            if(preview.isPlaying())preview.toggle();
            previewAutoplayWhenReady=false;
        }
        IntentFilter f=new IntentFilter(ConversionService.ACTION_PROGRESS);if(Build.VERSION.SDK_INT>=33)registerReceiver(receiver,f,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(receiver,f);
        if(conversionActive){
            android.content.SharedPreferences state=getSharedPreferences("state",MODE_PRIVATE);
            int w=state.getInt("activePictureWidth",0),h=state.getInt("activePictureHeight",0);
            if(w>0&&h>0)showEncodingPicture(w,h,state.getString("activePictureName",""));
            else showPictureScanning();
        }
        loadHistory();loadLastResult();refreshPauseControls();}
    @Override protected void onStop(){try{unregisterReceiver(receiver);}catch(Exception ignored){}super.onStop();}
    @Override protected void onDestroy(){
        // Async preview and inspection callbacks must not update a destroyed Activity.
        ++sourceGeneration;++queueGeneration;++previewJobVersion;
        if(activePreviewSessionId>0){FFmpegKit.cancel(activePreviewSessionId);activePreviewSessionId=-1;}
        handler.removeCallbacksAndMessages(null);
        preview.release();super.onDestroy();
    }

    private static final class SimpleTextWatcher implements TextWatcher{
        private final Runnable r;SimpleTextWatcher(Runnable r){this.r=r;}
        public void beforeTextChanged(CharSequence s,int st,int c,int a){} public void onTextChanged(CharSequence s,int st,int before,int count){r.run();} public void afterTextChanged(Editable e){}
    }
}
