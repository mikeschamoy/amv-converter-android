package com.mikeschamoy.amvconverter;

import android.content.Context;
import android.net.Uri;
import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegKitConfig;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.ReturnCode;
import java.util.ArrayList;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Locale;

/** Android FFmpeg probe used by BOTH the Activity and ConversionService. */
public final class FramePlanProbeAndroid implements FramePlanResolver.Probe {
    private static final Pattern CROP=Pattern.compile("crop=(\\d+):(\\d+):(\\d+):(\\d+)");
    private final Context ctx;
    private final Uri uri;
    private final PresetEngine.SourceInfo source;
    private final BooleanSupplier interrupted;

    public FramePlanProbeAndroid(Context ctx,Uri uri,PresetEngine.SourceInfo source,BooleanSupplier interrupted){
        this.ctx=ctx;this.uri=uri;this.source=source;this.interrupted=interrupted;
    }
    @Override public boolean cancelled(){return interrupted!=null&&interrupted.getAsBoolean();}
    @Override public PresetEngine.Crop sourceAt(double seconds){
        ArrayList<PresetEngine.Crop> all=run(seconds,"cropdetect=limit=24:round=16:reset=0",25);
        if(all.isEmpty())return null;
        PresetEngine.Crop winner=null;int best=0;
        for(PresetEngine.Crop c:all){
            if(c==null || !PresetEngine.isMeaningfulCrop(source,c))continue;
            // Letterbox/pillarbox bars normally occur on OPPOSITE edges.
            // Reject one-sided darkness masquerading as a permanent crop.
            int right=source.width-c.x-c.w,bottom=source.height-c.y-c.h;
            if(Math.abs(right-c.x)>Math.max(32,source.width/30) ||
                    Math.abs(bottom-c.y)>Math.max(32,source.height/30))continue;
            int count=0;
            for(PresetEngine.Crop other:all){
                if(other!=null && Math.abs(other.w-c.w)<=16 &&
                        Math.abs(other.h-c.h)<=16 && Math.abs(other.x-c.x)<=16 &&
                        Math.abs(other.y-c.y)<=16)count++;
            }
            if(count>best){best=count;winner=c;}
        }
        // An unstable crop isn't a fact about a video's geometry.
        return best>=Math.max(6,(int)Math.ceil(all.size()*.65))?winner:null;
    }
    @Override public PresetEngine.Crop renderedAt(PresetEngine.Preset preset,double seconds){
        ArrayList<PresetEngine.Crop> crops=run(seconds,preset.filter+",cropdetect=limit=24:round=2:reset=0",32);
        return PictureContentGuard.clusterFrameCrops(crops);
    }
    private ArrayList<PresetEngine.Crop> run(double seconds,String filter,int frames){
        ArrayList<PresetEngine.Crop> result=new ArrayList<>();
        if(cancelled())return result;
        try{
            String saf=FFmpegKitConfig.getSafParameterForRead(ctx,uri);
            String cmd="-hide_banner -ss "+String.format(Locale.US,"%.3f",Math.max(0,seconds))+
                    " -i "+quote(saf)+" -frames:v "+frames+" -vf "+quote(filter)+" -an -f null -";
            FFmpegSession session=FFmpegKit.execute(cmd);
            if(cancelled() || !ReturnCode.isSuccess(session.getReturnCode()))return result;
            String logs=session.getAllLogsAsString();
            if(logs==null)return result;
            Matcher m=CROP.matcher(logs);
            while(m.find()){
                int w=Integer.parseInt(m.group(1)),h=Integer.parseInt(m.group(2));
                int x=Integer.parseInt(m.group(3)),y=Integer.parseInt(m.group(4));
                int maxW=filter.startsWith("cropdetect=")?source.width:320;
                int maxH=filter.startsWith("cropdetect=")?source.height:240;
                if(w>0 && h>0 && x>=0 && y>=0 && x+w<=maxW && y+h<=maxH)
                    result.add(new PresetEngine.Crop(w,h,x,y));
            }
        }catch(Exception ex){android.util.Log.w("ClipAMV","Frame probe unavailable",ex);}
        return result;
    }
    private static String quote(String s){return "\""+s.replace("\"","\\\"")+"\"";}
}
