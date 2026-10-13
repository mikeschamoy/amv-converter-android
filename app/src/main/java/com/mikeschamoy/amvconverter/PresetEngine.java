package com.mikeschamoy.amvconverter;
import java.util.Locale;

public final class PresetEngine {
    public static final String PRESET_VERSION="3.0";
    public enum Type { YOUTUBE, MOVIE_SHOW, CARTOON, PORTRAIT }
    public enum Mode { SPACE_SAVER, STANDARD, QUALITY }
    public enum PortraitMode { FIT, ZOOM, ROTATE }

    public static final class Crop {
        public int w,h,x,y;
        public Crop(int w,int h,int x,int y){this.w=w;this.h=h;this.x=x;this.y=y;}
        public double ratio(){return h==0?0:(double)w/h;}
        public String toString(){return "crop="+w+":"+h+":"+x+":"+y;}
    }

    public static final class SourceInfo {
        public int width,height;
        public double fps,durationSeconds;
        public long inputBytes;
        public boolean image,audioPresent;
        public String videoCodec="",audioCodec="";
        public double ratio(){return height==0?16.0/9.0:(double)width/height;}
        public boolean isFourThree(){return Math.abs(ratio()-4.0/3.0)<0.035;}
        public boolean isPortrait(){return height>width;}
    }

    public static final class Preset {
        public String name,filter;
        public int outputFps,blockSize,visibleHeight;
        public int q=-1,qmin=-1;
        public boolean fourThree,fourThreePreserve,imageOutput;
        public Crop detectedCrop;
        public Mode mode;
    }

    private PresetEngine(){}

    public static Preset choose(SourceInfo s,Type type,Mode mode,int heightOverride,int fpsOverride,
                                boolean preserve43,PortraitMode portraitMode,Crop crop){
        Preset p=new Preset();p.mode=mode;p.detectedCrop=crop;p.imageOutput=s.image;
        int auto=s.fps>=27?30:25;
        if(mode==Mode.SPACE_SAVER)auto=15;
        p.outputFps=fpsOverride>0?fpsOverride:auto;
        p.blockSize=p.outputFps==15?1470:p.outputFps==30?735:882;
        if(mode==Mode.SPACE_SAVER){p.q=5;p.qmin=5;}
        else if(mode==Mode.QUALITY){p.q=1;p.qmin=1;}

        boolean crop43=crop!=null&&Math.abs(crop.ratio()-4.0/3.0)<0.05;
        if(crop43&&!s.isFourThree()&&type!=Type.PORTRAIT){
            int cx=Math.max(0,crop.x-32);
            p.name="PILLARBOX 4:3 - "+p.outputFps;
            p.filter="crop="+crop.w+":"+crop.h+":"+cx+":"+crop.y+",scale=320:240:flags=lanczos,setsar=1";
            p.fourThree=true;p.visibleHeight=240;return p;
        }

        if(type==Type.PORTRAIT||s.isPortrait()){
            p.name="PORTRAIT "+portraitMode.name()+" - "+p.outputFps;
            if(portraitMode==PortraitMode.ROTATE){
                p.filter="transpose=1,scale=-2:216:flags=lanczos,crop=320:216:x='(iw-ow)/2-32':y=0,pad=320:240:0:12:black,setsar=1";
                p.visibleHeight=216;
            }else if(portraitMode==PortraitMode.ZOOM){
                p.filter="scale=224:-2:flags=lanczos,crop=224:240:0:'(ih-240)/2',pad=320:240:48:0:black,setsar=1";
                p.visibleHeight=240;
            }else{
                p.filter="scale=208:-2:flags=lanczos,crop=208:240:0:'(ih-240)/2',pad=320:240:56:0:black,setsar=1";
                p.visibleHeight=240;
            }
            return p;
        }

        if(s.isFourThree()){
            p.fourThree=true;
            if(preserve43){
                p.name="4:3 PRESERVE - "+p.outputFps;
                p.filter="scale=288:216:flags=lanczos,pad=320:240:32:12:black,setsar=1";
                p.fourThreePreserve=true;p.visibleHeight=216;
            }else{
                p.name="4:3 FULL - "+p.outputFps;
                p.filter="scale=320:240:flags=lanczos,setsar=1";
                p.visibleHeight=240;
            }
            return p;
        }

        double r=crop!=null?crop.ratio():s.ratio();
        int h=heightOverride;
        if(h<=0){
            if(type==Type.YOUTUBE)h=208;
            else if(type==Type.CARTOON)h=216;
            else if(r<=1.82)h=216;
            else if(r<=1.95)h=208;
            else if(r<=2.15)h=192;
            else h=184;
        }
        int pad=(240-h)/2;
        String prefix=crop==null?"":crop.toString()+",";
        if(type==Type.YOUTUBE&&h==208){
            p.filter=prefix+"scale=-2:216:flags=lanczos,crop=320:208:x='(iw-ow)/2-32':y='(ih-oh)/2',pad=320:240:0:16:black";
        }else{
            String flags=type==Type.CARTOON?"lanczos+accurate_rnd":"lanczos";
            p.filter=prefix+"scale=-2:"+h+":flags="+flags+",crop=320:"+h+":x='(iw-ow)/2-32':y=0,pad=320:240:0:"+pad+":black";
        }
        p.visibleHeight=h;
        String label=type==Type.YOUTUBE?"YT":type==Type.CARTOON?"CARTOON":"MOVIE";
        p.name=label+" "+h+" - "+p.outputFps;
        if(type==Type.CARTOON&&mode!=Mode.SPACE_SAVER&&p.q<0){p.q=1;p.qmin=1;}
        return p;
    }

    public static String buildVideoCommand(String in,String out,Preset p,double start,double end,boolean test){
        StringBuilder c=new StringBuilder("-y ");
        if(start>0)c.append("-ss ").append(fmt(start)).append(" ");
        c.append("-i ").append(q(in)).append(" ");
        if(test)c.append("-t 30 ");
        else if(end>start&&end>0)c.append("-t ").append(fmt(end-start)).append(" ");
        c.append("-vf ").append(q(p.filter)).append(" -map 0:v:0 -map 0:a:0? -sn ");
        c.append("-r ").append(p.outputFps).append(" -c:v amv ");
        if(p.q>0)c.append("-q:v ").append(p.q).append(" -qmin ").append(p.qmin).append(" ");
        c.append("-c:a adpcm_ima_amv -ac 1 -ar 22050 -block_size ").append(p.blockSize).append(" ");
        c.append(q(out));return c.toString();
    }

    public static String buildImageCommand(String in,String out,Preset p){
        return "-y -i "+q(in)+" -vf "+q(p.filter)+" -frames:v 1 -q:v 2 -pix_fmt yuvj420p "+q(out);
    }

    public static long estimateBytes(SourceInfo s,Preset p,double runtime){
        if(s.image)return 350_000L;
        double base=1.45*(p.outputFps/25.0);
        double factor=p.mode==Mode.SPACE_SAVER?0.62:p.mode==Mode.QUALITY?1.35:1.0;
        double audio=s.audioPresent?0.088:0;
        return (long)(runtime*((base*factor+audio)*1_000_000.0/8.0));
    }

    public static String explain(SourceInfo s,Type type,Preset p){
        String a=s.isFourThree()?"4:3":String.format(Locale.US,"%.2f:1",s.ratio());
        String k=type==Type.YOUTUBE?"YouTube":type==Type.CARTOON?"Cartoon":type==Type.PORTRAIT?"Portrait":"Movie/Show";
        return "Detected "+a+" • "+String.format(Locale.US,"%.3f fps",s.fps)+" → "+k+" • "+p.name;
    }
    private static String fmt(double x){return String.format(Locale.US,"%.3f",x);}
    private static String q(String x){return "\""+x.replace("\"","\\\"")+"\"";}
}
