package com.mikeschamoy.amvconverter;

import android.content.Context;
import android.net.Uri;
import com.arthenica.ffmpegkit.*;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Locale;

/** Optional TEXT subtitle burn-in. Default OFF; image/bitmap tracks are not OCRed. */
public final class SubtitleSupport {
    public static final int OFF=0, FORCED=1, FIRST_TEXT=2;
    public static final int NORMAL=0, LARGE=1;
    private SubtitleSupport(){}
    private static String q(String s){return "\""+s.replace("\"","\\\"")+"\"";}
    static final class Track {int index; String codec="",language=""; boolean forced;}
    private static boolean textCodec(String x){
        return x.equals("subrip")||x.equals("srt")||x.equals("ass")||x.equals("ssa")||
            x.equals("webvtt")||x.equals("mov_text")||x.equals("text")||x.equals("microdvd");
    }
    private static Track findTrack(Context context,Uri source,int mode)throws IOException{
        String saf=FFmpegKitConfig.getSafParameterForRead(context,source);
        FFprobeSession p=FFprobeKit.execute("-v error -select_streams s -show_entries " +
            "stream=index,codec_name:stream_disposition=forced:stream_tags=language -of default=noprint_wrappers=1 "+q(saf));
        if(!ReturnCode.isSuccess(p.getReturnCode()))throw new IOException("Subtitle inspection failed");
        String report=p.getOutput()==null?"":p.getOutput();
        ArrayList<Track> found=new ArrayList<>();Track active=null;
        for(String raw:report.split("\\r?\\n")){
            String line=raw.trim();
            if(line.startsWith("index=")){
                try {active=new Track();active.index=Integer.parseInt(line.substring(6));found.add(active);}
                catch(NumberFormatException ignored){active=null;}
            } else if(active!=null){
                if(line.startsWith("codec_name="))active.codec=line.substring(11);
                if(line.equals("DISPOSITION:forced=1"))active.forced=true;
                if(line.startsWith("TAG:language="))active.language=line.substring(13);
            }
        }
        // Prefer English where available, without silently selecting a different
        // subtitle kind; otherwise use the first compatible track in input order.
        Track fallback=null;
        for(Track t:found)if(textCodec(t.codec)&&(mode==FIRST_TEXT||t.forced)){
            if(fallback==null)fallback=t;
            if(t.language.equalsIgnoreCase("eng")||t.language.equalsIgnoreCase("en"))return t;
        }
        return fallback;
    }
    /** Returns null when no compatible/forced text track exists. Caller owns returned file. */
    public static File extract(Context ctx,Uri source,int mode,double start,double seconds)throws IOException{
        if(mode==OFF)return null;
        Track track=findTrack(ctx,source,mode);
        if(track==null)return null;
        File output=File.createTempFile("clipamv_subtitle_", ".srt",ctx.getCacheDir());
        try{
            String saf=FFmpegKitConfig.getSafParameterForRead(ctx,source);
            StringBuilder cmd=new StringBuilder("-y -nostdin -v error ");
            if(start>0)cmd.append("-ss ").append(String.format(Locale.US,"%.3f",start)).append(' ');
            cmd.append("-i ").append(q(saf));
            if(seconds>0)cmd.append(" -t ").append(String.format(Locale.US,"%.3f",seconds));
            cmd.append(" -map 0:").append(track.index).append(" -c:s srt -f srt ").append(q(output.getAbsolutePath()));
            FFmpegSession s=FFmpegKit.execute(cmd.toString());
            if(!ReturnCode.isSuccess(s.getReturnCode()))throw new IOException("Could not extract embedded text subtitles: "+track.codec);
            if(output.length()==0) { output.delete();return null; }
            return output;
        }catch(Exception e){output.delete();if(e instanceof IOException)throw (IOException)e;throw new IOException("Subtitle extraction failed",e);}
    }
    /** Overlay in pre-pad picture coordinate space, so subtitles center at X64 on Gen 2. */
    public static String withSubtitles(String videoFilter,File subtitle,int size){
        if(subtitle==null)return videoFilter;
        if(!videoFilter.contains(",pad=320:240:"))throw new IllegalArgumentException("Unsupported framing filter");
        // Escape filename for FFmpeg's filter parser, independently of command quoting.
        String path=subtitle.getAbsolutePath().replace("\\","\\\\").replace(":","\\:").replace("'","\\'").replace("[","\\[").replace("]","\\]");
        String ass="subtitles=filename='"+path+"':force_style='FontSize="+(size==LARGE?22:16)+
            ",Alignment=2,MarginV=10,Outline=2,Shadow=1'";
        return videoFilter.replace(",pad=320:240:",","+ass+",pad=320:240:");
    }
}
