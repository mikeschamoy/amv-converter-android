package com.mikeschamoy.amvconverter;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.net.Uri;
import android.util.AttributeSet;
import android.view.Surface;
import android.view.TextureView;

public class PreviewTextureView extends TextureView implements TextureView.SurfaceTextureListener {
    public interface Listener {
        void onReady(int width, int height, int durationMs);
        void onPreviewError();
    }

    private MediaPlayer player;
    private Uri pendingUri;
    private Listener listener;
    private int sourceW, sourceH;
    private PresetEngine.Preset preset;
    private boolean playerView = true;

    public PreviewTextureView(Context c, AttributeSet a) {
        super(c, a);
        setSurfaceTextureListener(this);
    }

    public void setListener(Listener l) { listener = l; }

    public void setVideo(Uri uri) {
        pendingUri = uri;
        if (isAvailable()) prepare();
    }

    public void setPreset(PresetEngine.Preset p, boolean playerView) {
        this.preset = p;
        this.playerView = playerView;
        applyTransform();
    }

    public void toggle() {
        if (player == null) return;
        if (player.isPlaying()) player.pause(); else player.start();
    }

    public boolean isPlaying() { return player != null && player.isPlaying(); }
    public int getDurationMs() { return player == null ? 0 : player.getDuration(); }
    public int getCurrentMs() { return player == null ? 0 : player.getCurrentPosition(); }
    public void seekTo(int ms) { if (player != null) player.seekTo(ms); }

    private void prepare() {
        release();
        if (pendingUri == null || !isAvailable()) return;
        try {
            player = new MediaPlayer();
            player.setDataSource(getContext(), pendingUri);
            player.setSurface(new Surface(getSurfaceTexture()));
            player.setLooping(true);
            player.setOnVideoSizeChangedListener((mp,w,h)->{
                sourceW = w; sourceH = h; applyTransform();
            });
            player.setOnPreparedListener(mp -> {
                sourceW = mp.getVideoWidth();
                sourceH = mp.getVideoHeight();
                applyTransform();
                if (listener != null) listener.onReady(sourceW, sourceH, mp.getDuration());
            });
            player.setOnErrorListener((mp, what, extra) -> {
                if (listener != null) listener.onPreviewError();
                return true;
            });
            player.prepareAsync();
        } catch (Exception e) {
            if (listener != null) listener.onPreviewError();
        }
    }

    private void applyTransform() {
        if (sourceW <= 0 || sourceH <= 0 || getWidth() <= 0 || getHeight() <= 0 || preset == null) return;

        float vw = getWidth();
        float vh = getHeight();
        Matrix m = new Matrix();

        if (preset.fourThree) {
            if (preset.fourThreePreserve) {
                RectF src = new RectF(0,0,vw,vh);
                float dx = playerView ? 0f : vw * (32f/320f);
                RectF dst = new RectF(dx, vh*(12f/240f),
                        dx + vw*(288f/320f), vh*((12f+216f)/240f));
                m.setRectToRect(src,dst,Matrix.ScaleToFit.FILL);
            } else {
                m.reset();
            }
            setTransform(m);
            return;
        }

        int h = preset.visibleHeight;
        float scaleH = h;
        float cropY = 0;
        if (preset.name.startsWith("YT 208")) {
            scaleH = 216f;
            cropY = 4f;
        }

        double activeW = sourceW;
        double activeH = sourceH;
        double baseX = 0, baseY = 0;
        if (preset.detectedCrop != null) {
            baseX = preset.detectedCrop.x;
            baseY = preset.detectedCrop.y;
            activeW = preset.detectedCrop.w;
            activeH = preset.detectedCrop.h;
        }

        double scaledW = activeW * scaleH / activeH;
        double centered = (scaledW - 320.0) / 2.0;
        double raw = Math.max(0, Math.min(centered - 32.0, Math.max(0, scaledW - 320.0)));
        double shown = playerView
                ? Math.max(0, Math.min(centered, Math.max(0, scaledW - 320.0)))
                : raw;

        double sx = baseX + (shown / scaledW) * activeW;
        double sy = baseY + (cropY / scaleH) * activeH;
        double sw = (320.0 / scaledW) * activeW;
        double sh = (h / scaleH) * activeH;

        RectF srcInView = new RectF(
                (float)(sx/sourceW*vw),
                (float)(sy/sourceH*vh),
                (float)((sx+sw)/sourceW*vw),
                (float)((sy+sh)/sourceH*vh)
        );

        float pad = (240f - h)/2f;
        RectF dst = new RectF(0, vh*(pad/240f), vw, vh*((pad+h)/240f));
        m.setRectToRect(srcInView,dst,Matrix.ScaleToFit.FILL);
        setTransform(m);
    }

    public void release() {
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) {}
            player.release();
            player = null;
        }
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) { prepare(); }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) { applyTransform(); }
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s) { release(); return true; }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture s) {}
}
