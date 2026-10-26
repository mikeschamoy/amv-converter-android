package com.mikeschamoy.amvconverter;

/** Source may declare 16:9 yet contain a cinematic picture with black bars. */
public final class ContentAwareAutoRegression {
    private static void require(boolean yes,String why){if(!yes)throw new AssertionError(why);}
    public static void main(String[] args){
        PresetEngine.SourceInfo src=new PresetEngine.SourceInfo();
        src.width=1920;src.height=1080;src.fps=24000.0/1001;src.audioPresent=true;
        int[] crops={1080,1000,924,860,800};
        int[] heights={216,208,200,192,184};
        for(int i=0;i<crops.length;i++){
            PresetEngine.Crop detected=crops[i]==1080?null:
                new PresetEngine.Crop(1920,crops[i],0,(1080-crops[i])/2);
            PresetEngine.Preset auto=PresetEngine.choose(src,PresetEngine.Type.MOVIE_SHOW,
                PresetEngine.Mode.STANDARD,0,0,false,PresetEngine.PortraitMode.FIT,
                detected,PresetEngine.FrameMode.AUTO,PresetEngine.PictureMode.SHARP);
            require(auto.pictureHeight==heights[i],"source content="+crops[i]+" incorrectly chose "+auto.pictureHeight);
            require(auto.pictureWidth==256,"lost 256px viewport");
            require(auto.filter.contains("pad=320:240"),"lost AMV canvas");
            require(auto.filter.contains("crop=256:"+heights[i]),"wrong real picture crop");
            if(detected!=null)require(auto.filter.contains(detected.toString()),"source black bars not removed");
            else require(auto.detectedCrop==null,"invented source crop");
            require(auto.effectiveAspectRatio>0,"missing ratio metadata");
            for(int manual:heights){
                PresetEngine.Preset overridden=PresetEngine.choose(src,PresetEngine.Type.MOVIE_SHOW,
                    PresetEngine.Mode.STANDARD,manual,0,false,PresetEngine.PortraitMode.FIT,
                    detected,PresetEngine.FrameMode.AUTO,PresetEngine.PictureMode.SHARP);
                require(overridden.pictureHeight==manual,"explicit manual "+manual+" ignored");
            }
        }
        PresetEngine.SourceInfo small=new PresetEngine.SourceInfo();
        small.width=640;small.height=360;small.fps=25;small.audioPresent=true;
        for(int h:new int[]{360,280,272}){
            PresetEngine.Crop c=h==360?null:new PresetEngine.Crop(640,h,0,(360-h)/2);
            PresetEngine.Preset p=PresetEngine.choose(small,PresetEngine.Type.MOVIE_SHOW,
                PresetEngine.Mode.STANDARD,0,0,false,PresetEngine.PortraitMode.FIT,
                c,PresetEngine.FrameMode.AUTO,PresetEngine.PictureMode.SHARP);
            String label=h==360?"16_9":h==280?"CINEMA_192":"CINEMA_184";
            System.out.println("FILTER_"+label+"="+p.filter);
        }
        require(PresetEngine.autoContentHeight(Double.NaN)==216,"invalid ratio fallback unsafe");
        System.out.println("PASS: 5 content ratios, 25 manual overrides, X64, 320x240");
    }
}
