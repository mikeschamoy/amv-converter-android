package com.mikeschamoy.amvconverter;
import java.util.Locale;

public final class PresetEngine {
    public static final String PRESET_VERSION="4.2";
    public enum Type { YOUTUBE, MOVIE_SHOW, CARTOON, PORTRAIT }
    public enum Mode { SPACE_SAVER, STANDARD, QUALITY }
    public enum FrameMode { FIT, FILL, AUTO, BALANCED }
    public enum PictureMode { AUTO, SHARP, SMOOTH }
    public static final int PLAYER_VIEW_X=64, PLAYER_VIEW_WIDTH=256;
    public enum PortraitMode { FIT, ZOOM, ROTATE }

    public static final class Crop {
        public int w,h,x,y;
        public Crop(int w,int h,int x,int y){this.w=w;this.h=h;this.x=x;this.y=y;}
        public double ratio(){return h==0?0:(double)w/h;}
        public String toString(){return "crop="+w+":"+h+":"+x+":"+y;}
    }

    public static final class SourceInfo {
        public int width,height;
        public double sampleAspect=1.0;
        public int rotation=0;
        public double fps,durationSeconds;
        public long inputBytes;
        public boolean image,audioPresent;
        public String videoCodec="",audioCodec="";
        public double ratio(){double r=height==0?16.0/9.0:(double)width*sampleAspect/height;return Math.abs(rotation)%180==90?1.0/r:r;}
        public boolean isFourThree(){return Math.abs(ratio()-4.0/3.0)<0.035;}
        public boolean isPortrait(){return ratio()<1.0;}
    }

    public static final class Preset {
        public String name,filter;
        public int outputFps,blockSize,visibleHeight;
        public int q=-1,qmin=-1;
        public boolean fourThree,fourThreePreserve,imageOutput,narrowFit,audioPresent;
        public int contentWidth=320;
        public Crop detectedCrop;
        public Mode mode;
        public FrameMode frameMode=FrameMode.AUTO;
        public int pictureWidth,pictureHeight,requestedHeight;
        public double horizontalCropPercent;
    }

    private PresetEngine(){}
    // Ignore tiny noisy edge margins reported by cropdetect as black bars.
    public static boolean isMeaningfulCrop(SourceInfo source,Crop crop){
        if(source==null||crop==null||crop.w<=0||crop.h<=0||crop.x<0||crop.y<0
                ||crop.x+crop.w>source.width||crop.y+crop.h>source.height)return false;
        int lostW=source.width-crop.w,lostH=source.height-crop.h;
        return lostW>Math.max(16,(int)Math.round(source.width*0.03))
                ||lostH>Math.max(12,(int)Math.round(source.height*0.03));
    }

    // Old callers now use source-aware framing. Preview and conversion share the same filter.
    public static Preset choose(SourceInfo s,Type type,Mode mode,int heightOverride,int fpsOverride,
                                boolean preserve43,PortraitMode portraitMode,Crop crop){
        return choose(s,type,mode,heightOverride,fpsOverride,preserve43,portraitMode,crop,FrameMode.AUTO);
    }
    public static Preset choose(SourceInfo s,Type type,Mode mode,int heightOverride,int fpsOverride,
                                boolean preserve43,PortraitMode portraitMode,Crop crop,FrameMode framing){
        return choose(s,type,mode,heightOverride,fpsOverride,preserve43,portraitMode,crop,framing,PictureMode.SHARP);
    }
    public static Preset choose(SourceInfo s,Type type,Mode mode,int heightOverride,int fpsOverride,
                                boolean preserve43,PortraitMode portraitMode,Crop crop,FrameMode framing,PictureMode processing){
        // AUTO is resolved after analyzing this particular file. SHARP is the safe fallback.
        String resize=processing==PictureMode.SMOOTH?"bicubic":"lanczos";
        Preset p=new Preset();p.mode=mode;p.imageOutput=s.image;p.audioPresent=s.audioPresent;
        p.detectedCrop=isMeaningfulCrop(s,crop)?crop:null;
        int auto=s.fps>=27?30:25;
        if(mode==Mode.SPACE_SAVER)auto=15;
        p.outputFps=fpsOverride>0?fpsOverride:auto;
        p.blockSize=p.outputFps==15?1470:p.outputFps==30?735:882;
        if(mode==Mode.SPACE_SAVER){p.q=3;p.qmin=3;}
        else if(mode==Mode.QUALITY){p.q=1;p.qmin=1;}
        if(type==Type.CARTOON&&mode!=Mode.SPACE_SAVER&&p.q<0){p.q=1;p.qmin=1;}

        // Height controls limit the actual displayed content; they never force an invalid crop.
        int h=heightOverride;
        boolean fourThree=s.isFourThree()&&type!=Type.PORTRAIT;
        boolean portrait=type==Type.PORTRAIT||s.isPortrait();
        if(fourThree){if(h<=0)h=preserve43?216:240;p.fourThree=true;p.fourThreePreserve=preserve43;}
        else if(portrait){if(h<=0)h=portraitMode==PortraitMode.ROTATE?216:240;}
        else if(h<=0){
            double ratio=s.ratio();
            if(type==Type.YOUTUBE)h=208;
            else if(type==Type.CARTOON)h=216;
            else if(ratio<=1.82)h=216;
            else if(ratio<=1.95)h=208;
            else if(ratio<=2.15)h=192;
            else h=184;
        }
        h=Math.max(2,Math.min(240,h));if(h%2!=0)h--;
        p.requestedHeight=h;
        // Resolve framing per source, independently of picture processing and queue mode.
        double ratio=s.ratio();
        if(p.detectedCrop!=null){
            double sar=s.sampleAspect>0?s.sampleAspect:1.0;
            ratio=(double)p.detectedCrop.w*sar/p.detectedCrop.h;
            if(Math.abs(s.rotation)%180==90)ratio=1.0/ratio;
        }
        if(portrait&&portraitMode==PortraitMode.ROTATE)ratio=1.0/ratio;
        if(!Double.isFinite(ratio)||ratio<=0)ratio=16.0/9.0;
        FrameMode chosen=resolveFraming(type,framing,ratio);
        // The two 4:3 controls must do what their labels say: FULL fills
        // the selected height; PRESERVE shows the complete uncropped frame.
        if(fourThree&&framing==FrameMode.AUTO)
            chosen=preserve43?FrameMode.FIT:FrameMode.FILL;
        p.frameMode=chosen;
        boolean rotate=portrait&&portraitMode==PortraitMode.ROTATE;
        boolean zoom=chosen!=FrameMode.FIT||(portrait&&portraitMode==PortraitMode.ZOOM);
        if(portrait&&portraitMode==PortraitMode.FIT&&framing==FrameMode.AUTO){chosen=FrameMode.FIT;zoom=false;p.frameMode=chosen;}
        // BALANCED caps horizontal loss at approximately one third of the source.
        int pictureH=h;
        if(chosen==FrameMode.BALANCED && ratio>1.0){
            int cap=evenFloor(256.0/(ratio*0.67));
            pictureH=Math.min(h,Math.max(2,cap));
        }
        p.visibleHeight=h;
        p.pictureHeight=zoom?pictureH:Math.min(h,evenFloor(256.0/ratio));
        p.pictureWidth=zoom?256:Math.min(256,evenFloor(256.0,p.pictureHeight*ratio));
        p.horizontalCropPercent=zoom?Math.max(0,100.0*(1-256.0/(Math.max(1e-6,ratio)*pictureH))):0;
        // Avoid an unnecessary full-resolution rescale for common square-pixel sources.
        String initial=p.detectedCrop==null?"":p.detectedCrop.toString()+",";
        if(Math.abs(s.sampleAspect-1.0)>0.0001){
            initial+="scale=w='max(2,2*floor(iw*sar/2))':h=ih:flags=bicubic,setsar=1,";
        }else initial+="setsar=1,";
        if(rotate)initial+="transpose=1,";
        // One axis is sufficient to preserve source aspect ratio. FFmpeg's
        // scale=-2:H / scale=W:-2 path avoids runtime force_original_aspect_ratio
        // evaluation while keeping the same centered 256px player viewport.
        // For a crop/fill, scale the shorter limiting axis UP to cover target;
        // for a fit, scale the longer limiting axis DOWN to stay within target.
        boolean widthLimited = ratio >= 256.0/pictureH;
        String dimensions = (zoom == widthLimited)?"-2:"+pictureH:"256:-2";
        String rescale="scale="+dimensions+":flags="+resize+",";
        if(zoom)rescale+="crop=256:"+pictureH+":x='(iw-ow)/2':y='(ih-oh)/2',";
        rescale+="pad=320:240:x='(ow-iw)/2+32':y='(oh-ih)/2':color=black,setsar=1";
        p.filter=initial+rescale;
        String label=portrait?"PORTRAIT":fourThree?"4:3":type==Type.YOUTUBE?"YT":type==Type.CARTOON?"CARTOON":"MOVIE";
        p.name=label+" "+h+" "+(framing==FrameMode.AUTO?"AUTO→":"")+p.frameMode.name()+
                " • picture "+p.pictureWidth+"×"+p.pictureHeight+" • "+p.outputFps+"fps";
        return p;
    }
    private static int evenFloor(double n){return evenFloor(240.0,n);}
    private static int evenFloor(double max,double n){
        return Math.max(2,Math.min((int)max,2*(int)Math.floor(Math.max(2,n)/2.0)));
    }
    public static FrameMode resolveFraming(Type type,FrameMode requested,double ratio){
        if(requested!=FrameMode.AUTO)return requested;
        // Default to exact selected height on ordinary landscape YouTube/skate content.
        if(ratio<1.36)return FrameMode.FIT; // portrait, square, near 4:3
        if(type==Type.YOUTUBE && ratio<=2.15)return FrameMode.FILL;
        if(type==Type.CARTOON && ratio<=2.0)return FrameMode.FILL;
        if(ratio>=2.15)return FrameMode.BALANCED; // avoid extreme cinema crop
        if(type==Type.MOVIE_SHOW && ratio>=1.9)return FrameMode.BALANCED;
        return FrameMode.FILL;
    }

    public static String buildVideoCommand(String in,String out,Preset p,double start,double end,boolean test){
        return buildVideoCommand(in,out,p,start,end,test,0);
    }
    public static String buildVideoCommand(String in,String out,Preset p,double start,double end,boolean test,int performance){
        StringBuilder c=new StringBuilder("-y -fflags +genpts ");
        // Standard/Faster intentionally preserve the same filter-thread defaults as
        // a direct Termux FFmpeg command. Forcing two filter threads on some Android
        // builds was slower than leaving FFmpeg to select its own processing path.
        // Maximum is experimental: automatic filter scheduling can also be slower
        // on thermally constrained phones; measure before choosing it.
        if(performance==2)c.append("-filter_threads 0 ");
        if(start>0)c.append("-ss ").append(fmt(start)).append(" ");
        c.append("-i ").append(q(in)).append(" ");
        if(!p.audioPresent)c.append("-f lavfi -i anullsrc=r=22050:cl=mono ");
        if(test)c.append("-t 30 ");
        else if(end>start&&end>0)c.append("-t ").append(fmt(end-start)).append(" ");
        c.append("-vf ").append(q(p.filter)).append(" -map 0:v:0 ");
        c.append(p.audioPresent?"-map 0:a:0? ":"-map 1:a:0 -shortest ");
        c.append("-sn ");
        c.append("-r ").append(p.outputFps).append(" -c:v amv ");
        if(performance>=1)c.append("-threads 0 ");
        if(p.q>0)c.append("-q:v ").append(p.q).append(" -qmin ").append(p.qmin).append(" ");
        c.append("-c:a adpcm_ima_amv -ac 1 -ar 22050 -block_size ").append(p.blockSize).append(" -avoid_negative_ts make_zero ");
        c.append("-f amv ").append(q(out));return c.toString();
    }

    public static String buildImageCommand(String in,String out,Preset p){
        return "-y -i "+q(in)+" -vf "+q(p.filter)+" -frames:v 1 -q:v 2 -pix_fmt yuvj420p -f image2 -update 1 "+q(out);
    }

    public static long estimateBytes(SourceInfo s,Preset p,double runtime){
        if(s.image)return 350_000L;
        double base=1.45*(p.outputFps/25.0);
        double qualityFactor=p.mode==Mode.SPACE_SAVER?0.68:p.mode==Mode.QUALITY?1.35:1.0;
        double framingFactor=1.0;
        if(!p.fourThree && p.visibleHeight>0){
            double ratio=Math.max(0.70,Math.min(1.0,p.visibleHeight/216.0));
            framingFactor=0.82+0.18*ratio;
        }
        double audio=s.audioPresent?0.088:0;
        return (long)(runtime*((base*qualityFactor*framingFactor+audio)*1_000_000.0/8.0));
    }

    public static String explain(SourceInfo s,Type type,Preset p){
        String a=s.isFourThree()?"4:3":String.format(Locale.US,"%.2f:1",s.ratio());
        String k=type==Type.YOUTUBE?"YouTube":type==Type.CARTOON?"Cartoon":type==Type.PORTRAIT?"Portrait":"Movie/Show";
        return "Detected "+a+" • "+String.format(Locale.US,"%.3f fps",s.fps)+" → "+k+" • "+p.name;
    }
    private static String fmt(double x){return String.format(Locale.US,"%.3f",x);}
    private static String q(String x){return "\""+x.replace("\"","\\\"")+"\"";}
}
