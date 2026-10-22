package com.mikeschamoy.amvconverter;
/** Pure-Java bridge used by the Termux filter benchmark. No Android SDK required. */
public final class ParityPresetCLI {
    public static void main(String[] args) {
        if(args.length!=4)throw new IllegalArgumentException("width height sampleAspect height");
        PresetEngine.SourceInfo s=new PresetEngine.SourceInfo();
        s.width=Integer.parseInt(args[0]);
        s.height=Integer.parseInt(args[1]);
        s.sampleAspect=Double.parseDouble(args[2]);
        s.fps=24000.0/1001;s.audioPresent=true;
        PresetEngine.Preset p=PresetEngine.choose(s,PresetEngine.Type.MOVIE_SHOW,
            PresetEngine.Mode.STANDARD,Integer.parseInt(args[3]),25,false,
            PresetEngine.PortraitMode.FIT,null,PresetEngine.FrameMode.FILL,
            PresetEngine.PictureMode.SHARP);
        System.out.println(p.filter);
    }
}
