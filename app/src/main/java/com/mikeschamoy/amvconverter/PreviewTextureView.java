package com.mikeschamoy.amvconverter;
import android.content.Context;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.net.Uri;
import android.util.AttributeSet;
import android.view.Surface;
import android.view.TextureView;

public class PreviewTextureView extends TextureView implements TextureView.SurfaceTextureListener {
    public interface Listener {void onReady(int w,int h,int d);void onError();void onFinished();}
    private MediaPlayer player;
    private boolean prepared=false;
    private int videoW=320,videoH=240;
    private Uri uri;
    private Listener listener;
    private boolean playerView=true,encodedPreview=false;
    public PreviewTextureView(Context c,AttributeSet a){super(c,a);setSurfaceTextureListener(this);}
    public void setListener(Listener l){listener=l;}
    public void setEncodedPreview(boolean b){encodedPreview=b;apply();}
    public void setVideo(Uri u){uri=u;if(isAvailable())prepare();}
    public void clearSource(){uri=null;release();setTransform(new Matrix());}
    public void setPreset(PresetEngine.Preset p,boolean pv){playerView=pv;apply();}
    public void toggle(){if(player==null||!prepared)return;if(player.isPlaying())player.pause();else player.start();}
    public void play(){if(player!=null&&prepared&&!player.isPlaying())player.start();}
    public boolean isPlaying(){return player!=null&&prepared&&player.isPlaying();}
    public int duration(){return player==null||!prepared?0:player.getDuration();}
    public int position(){return player==null||!prepared?0:player.getCurrentPosition();}
    public void seekTo(int ms){if(player!=null&&prepared)player.seekTo(ms);}
    private void prepare(){
        release();prepared=false;if(uri==null||!isAvailable())return;
        try{
            player=new MediaPlayer();player.setDataSource(getContext(),uri);
            Surface surface=new Surface(getSurfaceTexture());
            try{player.setSurface(surface);}finally{surface.release();}
            player.setLooping(false);
            player.setOnPreparedListener(mp->{
                if(mp!=player)return;
                prepared=true;videoW=Math.max(1,mp.getVideoWidth());videoH=Math.max(1,mp.getVideoHeight());
                apply();if(listener!=null)listener.onReady(videoW,videoH,mp.getDuration());
            });
            player.setOnVideoSizeChangedListener((mp,w,h)->{
                if(mp!=player)return;
                videoW=Math.max(1,w);videoH=Math.max(1,h);apply();
            });
            player.setOnCompletionListener(mp->{if(listener!=null)listener.onFinished();});
            player.setOnErrorListener((mp,w,e)->{if(mp!=player)return true;prepared=false;if(listener!=null)listener.onError();return true;});
            player.prepareAsync();
        }catch(Exception e){release();if(listener!=null)listener.onError();}
    }
    private void apply(){
        if(getWidth()<=0||getHeight()<=0)return;
        Matrix m=new Matrix();
        if(!encodedPreview){
            // An unfiltered original video should retain its display aspect ratio.
            // Fit it into the preview rectangle without stretching portrait/3:2 footage.
            float displayAspect=videoW/(float)Math.max(1,videoH);
            float boxAspect=getWidth()/(float)getHeight();
            if(displayAspect>boxAspect)m.setScale(1f,boxAspect/displayAspect,
                getWidth()/2f,getHeight()/2f);
            else m.setScale(displayAspect/boxAspect,1f,
                getWidth()/2f,getHeight()/2f);
        }else if(playerView){
            // The physical Gen 2 shows encoded columns 64..319; enlarge that crop.
            float stretch=320f/PresetEngine.PLAYER_VIEW_WIDTH;
            m.setScale(stretch,1f);
            m.postTranslate(-PresetEngine.PLAYER_VIEW_X*stretch*getWidth()/320f,0);
        }
        setTransform(m);
    }
    public void release(){prepared=false;if(player!=null){try{player.stop();}catch(Exception ignored){}player.release();player=null;}}
    public void onSurfaceTextureAvailable(SurfaceTexture s,int w,int h){prepare();}
    public void onSurfaceTextureSizeChanged(SurfaceTexture s,int w,int h){apply();}
    public boolean onSurfaceTextureDestroyed(SurfaceTexture s){release();return true;}
    public void onSurfaceTextureUpdated(SurfaceTexture s){}
}
