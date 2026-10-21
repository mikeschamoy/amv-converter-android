package com.mikeschamoy.amvconverter;

public final class PresetEngineRegression {
    private static void check(boolean ok, String why){if(!ok)throw new AssertionError(why);}
    public static void main(String[] args){
        int cases=0;
        int[][] dims={{540,360},{640,360},{960,720},{352,288},{640,640},{1080,1920},
                      {1920,800},{216,384},{200,1024},{1024,200},{1,1024},{1440,720}};
        for(int[] dimension:dims){
            for(PresetEngine.FrameMode fm:PresetEngine.FrameMode.values()){
                for(PresetEngine.PictureMode pm:new PresetEngine.PictureMode[]{PresetEngine.PictureMode.SHARP,PresetEngine.PictureMode.SMOOTH}){
                    PresetEngine.SourceInfo s=new PresetEngine.SourceInfo();
                    s.width=dimension[0];s.height=dimension[1];s.fps=24;s.audioPresent=true;
                    PresetEngine.Preset p=PresetEngine.choose(s,PresetEngine.Type.MOVIE_SHOW,
                        PresetEngine.Mode.STANDARD,192,25,false,PresetEngine.PortraitMode.FIT,null,fm,pm);
                    check(p.filter.contains("pad=320:240"),"final canvas missing");
                    check(p.filter.contains("setsar=1"),"square pixel reset missing");
                    check(p.filter.contains(pm==PresetEngine.PictureMode.SMOOTH?"flags=bicubic":"flags=lanczos"),"wrong scaler");
                    check(p.filter.contains(fm==PresetEngine.FrameMode.FIT?"force_original_aspect_ratio=decrease":"force_original_aspect_ratio=increase"),"wrong framing");
                    check(p.outputFps==25,"manual FPS lost");
                    cases++;
                }
            }
        }
        PresetEngine.SourceInfo s=new PresetEngine.SourceInfo();
        s.width=1440;s.height=720;s.fps=24;s.audioPresent=true;
        PresetEngine.Preset p=PresetEngine.choose(s,PresetEngine.Type.MOVIE_SHOW,
             PresetEngine.Mode.STANDARD,192,0,false,PresetEngine.PortraitMode.FIT,null);
        String standard=PresetEngine.buildVideoCommand("in.mkv","out.amv",p,0,30,true,0);
        String faster=PresetEngine.buildVideoCommand("in.mkv","out.amv",p,0,30,true,1);
        String maximum=PresetEngine.buildVideoCommand("in.mkv","out.amv",p,0,30,true,2);
        check(standard.contains("-f amv"),"AMV muxer missing");
        check(faster.contains("-threads 0")&&!faster.contains("-threads 2"),"Faster artificially limits encoder threads");
        check(faster.contains("-filter_threads 2")&&maximum.contains("-filter_threads 0"),"Performance filters incorrect");
        check(!maximum.contains("-autorotate"),"Unsupported explicit autorotate still present");
        check(!standard.contains("libx264"),"Incorrect preview codec in AMV command");
        check(standard.contains("-block_size 882"),"25fps AMV audio block size incorrect");
        System.out.println("PASS: "+cases+" framing/processing cases and 8 command checks");
    }
}
