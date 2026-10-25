package com.mikeschamoy.amvconverter;

public final class PictureContentGuardRegression {
    private static void yes(boolean x,String reason){if(!x)throw new AssertionError(reason);}
    public static void main(String[] args){
        PresetEngine.SourceInfo s=new PresetEngine.SourceInfo();
        s.width=1920;s.height=1080;s.sampleAspect=1;s.fps=24000.0/1001.0;
        s.durationSeconds=3366;s.audioPresent=true;
        PresetEngine.Preset p=PresetEngine.choose(s,PresetEngine.Type.MOVIE_SHOW,
          PresetEngine.Mode.STANDARD,216,25,false,PresetEngine.PortraitMode.FIT,null,
          PresetEngine.FrameMode.FILL,PresetEngine.PictureMode.SHARP);
        yes(p.pictureHeight==216,"must start at 216");
        PresetEngine.Crop measured=new PresetEngine.Crop(256,164,64,38);
        PresetEngine.Crop derived=PictureContentGuard.sourceCrop(s,p,measured);
        yes(derived!=null,"must derive correction");
        yes(derived.w==1920 && derived.h==820 && derived.x==0 && derived.y==130,
                "expected high-resolution crop 1920:820:0:130; got "+derived);
        PresetEngine.Preset corrected=PresetEngine.choose(s,PresetEngine.Type.MOVIE_SHOW,
          PresetEngine.Mode.STANDARD,216,25,false,PresetEngine.PortraitMode.FIT,derived,
          PresetEngine.FrameMode.FILL,PresetEngine.PictureMode.SHARP);
        yes(corrected.pictureHeight>=184 && corrected.pictureHeight<=216,"height range");
        yes(PictureContentGuard.consistent(measured,new PresetEngine.Crop(256,164,64,38)),"stability");
        yes(!PictureContentGuard.consistent(measured,new PresetEngine.Crop(256,205,64,15)),"reject transients");
        yes(PictureContentGuard.sourceCrop(s,p,new PresetEngine.Crop(256,216,64,12))==null,"no unnecessary correction");
        yes(PictureContentGuard.evaluate(new PresetEngine.Crop[]{null,null,null})==
                PictureContentGuard.UNKNOWN,"unavailable probes must not pass");
        yes(PictureContentGuard.evaluate(new PresetEngine.Crop[]{measured,null,null})==
                PictureContentGuard.UNKNOWN,"single measurement must not pass");
        yes(PictureContentGuard.evaluate(new PresetEngine.Crop[]{measured,measured,null})==
                PictureContentGuard.REPAIR,"stable 164px samples require repair");
        yes(PictureContentGuard.evaluate(new PresetEngine.Crop[]{
                new PresetEngine.Crop(256,184,64,28),
                new PresetEngine.Crop(256,216,64,12),null})==
                PictureContentGuard.ACCEPT,"two adequate samples can pass");
        yes(PictureContentGuard.evaluate(new PresetEngine.Crop[]{measured,
                new PresetEngine.Crop(256,184,64,28),
                new PresetEngine.Crop(256,216,64,12)})==
                PictureContentGuard.UNKNOWN,"mixed borders cannot be silently accepted");
        yes(PictureContentGuard.evaluate(new PresetEngine.Crop[]{measured,measured,
                new PresetEngine.Crop(256,216,64,12)})==
                PictureContentGuard.UNKNOWN,"variable format must not be cropped");
        s.rotation=90;
        yes(PictureContentGuard.sourceCrop(s,p,measured)==null,"skip rotated geometry");
        System.out.println("PASS: 12 height guard cases including fail-closed probe validation and 164px repair");
    }
}
