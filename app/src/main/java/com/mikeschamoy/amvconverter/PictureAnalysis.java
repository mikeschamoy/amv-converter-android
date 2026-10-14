package com.mikeschamoy.amvconverter;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegKitConfig;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.ReturnCode;
import java.io.File;
import java.util.Locale;

/** A modest frame-based recommendation, not a subjective picture-quality oracle. */
public final class PictureAnalysis {
    private PictureAnalysis() {}
    public static final class Result {
        public final PresetEngine.PictureMode mode;
        public final String reason;
        Result(PresetEngine.PictureMode mode,String reason){this.mode=mode;this.reason=reason;}
    }
    private static String quote(String s){return "\""+s.replace("\"","\\\"")+"\"";}
    public static Result analyze(Context ctx,Uri uri,PresetEngine.SourceInfo info){
        if(info==null||info.image)return new Result(PresetEngine.PictureMode.SHARP,"Image/default: preserve detail");
        double bpp=info.inputBytes>0&&info.durationSeconds>1&&info.fps>0?
            info.inputBytes*8.0/info.durationSeconds/Math.max(1.0,info.width*(double)info.height*info.fps):Double.NaN;
        double boundary=0; int valid=0;
        String input=FFmpegKitConfig.getSafParameterForRead(ctx,uri);
        double duration=Math.max(0,info.durationSeconds);
        for(int n=0;n<3;n++){
            double at=duration>8?Math.min(duration-0.3,duration*(0.20+0.30*n)):Math.max(0,duration/2);
            File f=new File(ctx.getCacheDir(),"clipamv_quality_"+Thread.currentThread().getId()+"_"+n+".png");
            String cmd="-y -ss "+String.format(Locale.US,"%.3f",at)+" -autorotate -i "+quote(input)+
                " -frames:v 1 -vf scale=160:90:force_original_aspect_ratio=decrease,pad=160:90:(ow-iw)/2:(oh-ih)/2:black "+quote(f.getAbsolutePath());
            try {
                FFmpegSession result=FFmpegKit.execute(cmd);
                if(ReturnCode.isSuccess(result.getReturnCode())){
                    Bitmap b=BitmapFactory.decodeFile(f.getAbsolutePath());
                    if(b!=null){double e=edgeBlockRatio(b);b.recycle();if(!Double.isNaN(e)){boundary+=e;valid++;}}
                }
            }catch(Exception ignored){}finally{f.delete();}
        }
        double ratio=valid>0?boundary/valid:1;
        // Noticeable 8-pixel macroblock discontinuities or scarce source bits favour Bicubic.
        boolean blocky=valid>0&&ratio>1.38;
        boolean lowBit=Double.isFinite(bpp)&&bpp<0.11;
        boolean clean=Double.isFinite(bpp)&&bpp>0.22;
        if(blocky||lowBit)return new Result(PresetEngine.PictureMode.SMOOTH,
            blocky?"8-pixel blocking detected in sampled frames":"Low source bits per pixel");
        return new Result(PresetEngine.PictureMode.SHARP,valid==0?
            "Sampling unavailable; preserve detail":clean?"Higher source detail budget; sampled frames look clean":
            "No strong compression artifacts in sampled frames");
    }
    private static double edgeBlockRatio(Bitmap bitmap){
        int w=bitmap.getWidth(),h=bitmap.getHeight();
        if(w<32||h<32)return Double.NaN;
        double edge=0,interior=0,brightness=0;int ne=0,ni=0;
        for(int y=9;y<h-8;y+=2){
            for(int x=9;x<w-8;x++){
                int a=bitmap.getPixel(x,y),c=bitmap.getPixel(x-1,y);
                int lum=(int)(0.2126*((a>>16)&255)+0.7152*((a>>8)&255)+0.0722*(a&255));
                int old=(int)(0.2126*((c>>16)&255)+0.7152*((c>>8)&255)+0.0722*(c&255));
                double g=Math.abs(lum-old);brightness+=lum;
                if((x&7)==0){edge+=g;ne++;}else {interior+=g;ni++;}
            }
        }
        if(ne==0||ni==0||brightness/(ne+ni)<12)return Double.NaN;
        return (edge/ne+1.0)/(interior/ni+1.0);
    }
}
