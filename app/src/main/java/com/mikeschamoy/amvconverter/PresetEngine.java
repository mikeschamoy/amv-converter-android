package com.mikeschamoy.amvconverter;
import java.util.Locale;

public final class PresetEngine {
    public static final String PRESET_VERSION="4.1";
    public enum Type { YOUTUBE, MOVIE_SHOW, CARTOON, PORTRAIT }
    public enum Mode { SPACE_SAVER, STANDARD, QUALITY }
    public enum FrameMode { FIT, FILL }
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
        public FrameMode frameMode=FrameMode.FIT;
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

    // Old callers default to fit. Preview and conversion use the same filter.
    public static Preset choose(SourceInfo s,Type type,Mode mode,int heightOverride,int fpsOverride,
                                boolean preserve43,PortraitMode portraitMode,Crop crop){
        return choose(s,type,mode,heightOverride,fpsOverride,preserve43,portraitMode,crop,FrameMode.FIT);
    }
    public static Preset choose(SourceInfo s,Type type,Mode mode,int heightOverride,int fpsOverride,
                                boolean preserve43,PortraitMode portraitMode,Crop crop,FrameMode framing){
        return choose(s,type,mode,heightOverride,fpsOverride,preserve43,portraitMode,crop,framing,PictureMode.SHARP);
    }
    public static Preset choose(SourceInfo s,Type type,Mode mode,int heightOverride,int fpsOverride,
                                boolean preserve43,PortraitMode portraitMode,Crop crop,FrameMode framing,PictureMode processing){
        // AUTO is resolved after analyzing this particular file. SHARP is the safe fallback.
        String resize=processing==PictureMode.SMOOTH?"bicubic":"lanczos";
        Preset p=new Preset();p.mode=mode;p.imageOutput=s.image;p.audioPresent=s.audioPresent;p.frameMode=framing;
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
        if(fourThree){h=preserve43?216:240;p.fourThree=true;p.fourThreePreserve=preserve43;}
        else if(portrait){h=portraitMode==PortraitMode.ROTATE?216:240;}
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
        p.visibleHeight=h;
        boolean zoom=framing==FrameMode.FILL||(portrait&&portraitMode==PortraitMode.ZOOM);
        boolean rotate=portrait&&portraitMode==PortraitMode.ROTATE;
        String initial=p.detectedCrop==null?"":p.detectedCrop.toString()+",";
        // Normalize non-square source pixels to square pixels before geometry calculations.
        // FFmpeg auto-applies container rotation at decode. Optional ROTATE adds 90 degrees.
        initial+="scale=w='max(2,2*floor(iw*sar/2))':h=ih:flags=bicubic,setsar=1,";
        if(rotate)initial+="transpose=1,";
        // Directly scale into the calibrated visible 256-column viewport.
        // The picture is centered at encoded X=192, the viewport midpoint.
        // FIT never crops content; FILL crops uniformly around the center.
        String rescale="scale=256:"+h+":force_original_aspect_ratio="+(zoom?"increase":"decrease")+
                ":force_divisible_by=2:flags="+resize+",";
        if(zoom)rescale+="crop=256:"+h+":x='(iw-ow)/2':y='(ih-oh)/2',";
        rescale+="pad=320:240:x='(ow-iw)/2+32':y='(oh-ih)/2':color=black,setsar=1";
        p.filter=initial+rescale;
        String label=portrait?"PORTRAIT":fourThree?"4:3":type==Type.YOUTUBE?"YT":type==Type.CARTOON?"CARTOON":"MOVIE";
        p.name=label+" "+h+" "+(zoom?"FILL":"FIT")+" - "+p.outputFps;
        return p;
    }
    public static String buildVideoCommand(String in,String out,Preset p,double start,double end,boolean test){
        return buildVideoCommand(in,out,p,start,end,test,0);
    }
    public static String buildVideoCommand(String in,String out,Preset p,double start,double end,boolean test,int performance){
        StringBuilder c=new StringBuilder("-y -fflags +genpts ");
        if(performance==1)c.append("-filter_threads 2 ");
        else if(performance==2)c.append("-filter_threads 0 ");
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
