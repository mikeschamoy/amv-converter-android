package com.mikeschamoy.amvconverter;
import android.content.Context;
import android.graphics.*;
import android.media.MediaPlayer;
import android.net.Uri;
import android.util.AttributeSet;
import android.view.*;

public class PreviewTextureView extends TextureView implements TextureView.SurfaceTextureListener {
    public interface Listener{void onReady(int w,int h,int d);void onError();}
    private MediaPlayer player;private Uri uri;private Listener listener;private PresetEngine.Preset preset;
    private boolean playerView=true;private int sourceW,sourceH;
    public PreviewTextureView(Context c,AttributeSet a){super(c,a);setSurfaceTextureListener(this);}
    public void setListener(Listener l){listener=l;}
    public void setVideo(Uri u){uri=u;if(isAvailable())prepare();}
    public void setPreset(PresetEngine.Preset p,boolean pv){preset=p;playerView=pv;apply();}
    public void toggle(){if(player==null)return;if(player.isPlaying())player.pause();else player.start();}
    public boolean isPlaying(){return player!=null&&player.isPlaying();}
    public int duration(){return player==null?0:player.getDuration();}
    public int position(){return player==null?0:player.getCurrentPosition();}
    public void seekTo(int ms){if(player!=null)player.seekTo(ms);}
    private void prepare(){
        release();if(uri==null||!isAvailable())return;
        try{
            player=new MediaPlayer();player.setDataSource(getContext(),uri);player.setSurface(new Surface(getSurfaceTexture()));player.setLooping(true);
            player.setOnVideoSizeChangedListener((mp,w,h)->{sourceW=w;sourceH=h;apply();});
            player.setOnPreparedListener(mp->{sourceW=mp.getVideoWidth();sourceH=mp.getVideoHeight();apply();if(listener!=null)listener.onReady(sourceW,sourceH,mp.getDuration());});
            player.setOnErrorListener((mp,w,e)->{if(listener!=null)listener.onError();return true;});player.prepareAsync();
        }catch(Exception e){if(listener!=null)listener.onError();}
    }
    private void apply(){
        if(sourceW<=0||sourceH<=0||getWidth()<=0||getHeight()<=0||preset==null)return;
        float vw=getWidth(),vh=getHeight();Matrix m=new Matrix();
        if(preset.fourThree){
            if(preset.fourThreePreserve){
                RectF src=new RectF(0,0,vw,vh);float dx=playerView?0:vw*(32f/320f);
                RectF dst=new RectF(dx,vh*(12f/240f),dx+vw*(288f/320f),vh*((12f+216f)/240f));
                m.setRectToRect(src,dst,Matrix.ScaleToFit.FILL);
            }
            setTransform(m);return;
        }
        if(preset.name.startsWith("PORTRAIT")){
            if(preset.name.contains("ROTATE")){m.setRotate(90,vw/2f,vh/2f);float sc=Math.min(vw/vh,vh/vw);m.postScale(sc,sc,vw/2f,vh/2f);}
            setTransform(m);return;
        }
        int h=preset.visibleHeight;float sh=h,cy=0;if(preset.name.startsWith("YT 208")){sh=216;cy=4;}
        double aw=sourceW,ah=sourceH,bx=0,by=0;if(preset.detectedCrop!=null){bx=preset.detectedCrop.x;by=preset.detectedCrop.y;aw=preset.detectedCrop.w;ah=preset.detectedCrop.h;}
        double scaledW=aw*sh/ah,center=(scaledW-320)/2.0,raw=Math.max(0,Math.min(center-32,Math.max(0,scaledW-320))),shown=playerView?Math.max(0,Math.min(center,Math.max(0,scaledW-320))):raw;
        double sx=bx+(shown/scaledW)*aw,sy=by+(cy/sh)*ah,sw=(320.0/scaledW)*aw,srcH=(h/sh)*ah;
        RectF srcV=new RectF((float)(sx/sourceW*vw),(float)(sy/sourceH*vh),(float)((sx+sw)/sourceW*vw),(float)((sy+srcH)/sourceH*vh));
        float pad=(240f-h)/2f;RectF dst=new RectF(0,vh*(pad/240f),vw,vh*((pad+h)/240f));
        m.setRectToRect(srcV,dst,Matrix.ScaleToFit.FILL);setTransform(m);
    }
    public void release(){if(player!=null){try{player.stop();}catch(Exception ignored){}player.release();player=null;}}
    public void onSurfaceTextureAvailable(SurfaceTexture s,int w,int h){prepare();}
    public void onSurfaceTextureSizeChanged(SurfaceTexture s,int w,int h){apply();}
    public boolean onSurfaceTextureDestroyed(SurfaceTexture s){release();return true;}
    public void onSurfaceTextureUpdated(SurfaceTexture s){}
}
