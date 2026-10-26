package com.mikeschamoy.amvconverter;

public class Fixed216FrameRegression {
  private static void yes(boolean b,String m){if(!b)throw new AssertionError(m);}
  public static void main(String[] args){
    PresetEngine.SourceInfo src=new PresetEngine.SourceInfo();
    src.width=640;src.height=360;src.fps=24000.0/1001.0;src.audioPresent=true;
    for(PresetEngine.Mode mode:PresetEngine.Mode.values()){
      PresetEngine.Preset plain=PresetEngine.choose(src,PresetEngine.Type.MOVIE_SHOW,
        mode,0,0,false,PresetEngine.PortraitMode.FIT,null,
        PresetEngine.FrameMode.AUTO,PresetEngine.PictureMode.SHARP);
      PresetEngine.Preset cropped=PresetEngine.choose(src,PresetEngine.Type.MOVIE_SHOW,
        mode,0,0,false,PresetEngine.PortraitMode.FIT,
        new PresetEngine.Crop(640,280,0,40),
        PresetEngine.FrameMode.AUTO,PresetEngine.PictureMode.SHARP);
      yes(plain.pictureWidth==256&&plain.pictureHeight==216,"plain movie is not 256x216");
      yes(cropped.pictureWidth==256&&cropped.pictureHeight==192,"2.29:1 movie should use 192");
      yes(cropped.frameMode==PresetEngine.FrameMode.BALANCED,"effective widescreen must select balanced");
      yes(cropped.filter.contains("crop=640:280:0:40"),"black-bar removal not applied");
      yes(cropped.filter.contains("crop=256:192"),"final 192 crop wrong");
    }
    PresetEngine.Preset a=PresetEngine.choose(src,PresetEngine.Type.MOVIE_SHOW,
      PresetEngine.Mode.STANDARD,0,0,false,PresetEngine.PortraitMode.FIT,null,
      PresetEngine.FrameMode.AUTO,PresetEngine.PictureMode.SHARP);
    PresetEngine.Preset b=PresetEngine.choose(src,PresetEngine.Type.MOVIE_SHOW,
      PresetEngine.Mode.STANDARD,0,0,false,PresetEngine.PortraitMode.FIT,
      new PresetEngine.Crop(640,280,0,40),
      PresetEngine.FrameMode.AUTO,PresetEngine.PictureMode.SHARP);
    System.out.println("PASS content-aware 216 clean and 192 ultrawide across all quality modes");
    System.out.println("FILTER_PLAIN="+a.filter);
    System.out.println("FILTER_CROPPED="+b.filter);
  }
}
