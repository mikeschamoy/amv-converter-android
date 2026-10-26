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
                    check(p.filter.contains("scale=-2:")||p.filter.contains("scale=256:-2"),"scale must retain source aspect without double resizing");
                    check(p.pictureWidth>0&&p.pictureWidth<=256 &&
                        p.pictureHeight>=PresetEngine.MIN_PICTURE_HEIGHT&&
                        p.pictureHeight<=PresetEngine.MAX_PICTURE_HEIGHT,"height escaped 184..216: "+p.pictureHeight);
                    check(p.requestedHeight>=184&&p.requestedHeight<=216,"requested height escaped guardrails");
                    check(p.outputFps==25,"manual FPS lost");
                    cases++;
                }
            }
        }
        // Regression: YT + 208 + AUTO must NEVER silently shrink to 144px.
        PresetEngine.SourceInfo yt=new PresetEngine.SourceInfo();
        yt.width=640;yt.height=360;yt.fps=30;yt.audioPresent=true;
        PresetEngine.Preset automatic=PresetEngine.choose(yt,PresetEngine.Type.YOUTUBE,
                PresetEngine.Mode.STANDARD,208,30,false,PresetEngine.PortraitMode.FIT,null,
                PresetEngine.FrameMode.AUTO,PresetEngine.PictureMode.SHARP);
        check(automatic.frameMode==PresetEngine.FrameMode.FILL,"YT AUTO should fill");
        check(automatic.pictureHeight==208,"YT 208 AUTO picture became "+automatic.pictureHeight+"px");
        check(automatic.filter.contains("crop=256:208"),"YT auto missing 208px crop");
        check(automatic.filter.contains("+32"),"lost X64 viewport compensation");
        PresetEngine.Preset explicitFit=PresetEngine.choose(yt,PresetEngine.Type.YOUTUBE,
                PresetEngine.Mode.STANDARD,208,30,false,PresetEngine.PortraitMode.FIT,null,
                PresetEngine.FrameMode.FIT,PresetEngine.PictureMode.SHARP);
        check(explicitFit.pictureHeight==208 && explicitFit.frameMode==PresetEngine.FrameMode.FILL,
                "FIT must yield to minimum visible height when widescreen (not 144px)");
        PresetEngine.SourceInfo cinema=new PresetEngine.SourceInfo();
        cinema.width=1920;cinema.height=800;cinema.fps=24;cinema.audioPresent=true;
        PresetEngine.Preset cin=PresetEngine.choose(cinema,PresetEngine.Type.MOVIE_SHOW,
                PresetEngine.Mode.STANDARD,184,25,false,PresetEngine.PortraitMode.FIT,null,
                PresetEngine.FrameMode.AUTO,PresetEngine.PictureMode.SHARP);
        check(cin.frameMode==PresetEngine.FrameMode.BALANCED,"scope movie should be balanced");
        check(cin.pictureHeight==184,"scope movie must preserve minimum 184px");
        // Reproduces Crystal Lake S01E01: a 1920x1080 source whose black-bar
        // scan found a 1920x832 active region previously became 256x164.
        PresetEngine.SourceInfo crystal=new PresetEngine.SourceInfo();
        crystal.width=1920;crystal.height=1080;crystal.fps=24000.0/1001;crystal.audioPresent=true;
        PresetEngine.Preset crystalBeforeScan=PresetEngine.choose(crystal,PresetEngine.Type.MOVIE_SHOW,
                PresetEngine.Mode.STANDARD,0,0,false,PresetEngine.PortraitMode.FIT,null,
                PresetEngine.FrameMode.AUTO,PresetEngine.PictureMode.SHARP);
        PresetEngine.Preset crystalAfterScan=PresetEngine.choose(crystal,PresetEngine.Type.MOVIE_SHOW,
                PresetEngine.Mode.STANDARD,0,0,false,PresetEngine.PortraitMode.FIT,
                new PresetEngine.Crop(1920,832,0,124),PresetEngine.FrameMode.AUTO,PresetEngine.PictureMode.SHARP);
        check(crystalBeforeScan.pictureHeight==216,"pre-scan full-screen estimate changed");
        check(crystalAfterScan.pictureHeight==184,"Content-aware Auto should pick 184px for effective 2.31:1: "+crystalAfterScan.pictureHeight);
        check(crystalAfterScan.filter.contains("crop=256:184:"),
                "encoded crop must use selected 184px target");
        check(crystalAfterScan.filter.contains("crop=1920:832:0:124"),
                "detected source letterbox crop must be retained");
        // A 16:9 *container* with 2.31:1 content must not be forced to 216.
        check(crystalAfterScan.frameMode==PresetEngine.FrameMode.BALANCED,"effective ultrawide content should choose BALANCED");
        check(crystalAfterScan.pictureWidth==256,"16:9 movie must fill Gen2 viewport");
        PresetEngine.Preset movie192=PresetEngine.choose(crystal,PresetEngine.Type.MOVIE_SHOW,
                PresetEngine.Mode.STANDARD,192,25,false,PresetEngine.PortraitMode.FIT,
                new PresetEngine.Crop(1920,832,0,124),PresetEngine.FrameMode.AUTO,PresetEngine.PictureMode.SHARP);
        check(movie192.pictureHeight==192,"manual 192 ignored");
        check(movie192.requestedHeight==192,"manual height override ignored");
        check(PresetEngine.autoContentHeight(1.78)==216,"16:9 should use 216");
        check(PresetEngine.autoContentHeight(1.92)==208,"1.92:1 should use 208");
        check(PresetEngine.autoContentHeight(2.08)==200,"2.08:1 should use 200");
        check(PresetEngine.autoContentHeight(2.22)==192,"2.22:1 should use 192");
        check(PresetEngine.autoContentHeight(2.39)==184,"2.39:1 should use 184");
        check(PresetEngine.autoContentHeight(Double.NaN)==216,"invalid ratio fallback");
        // Even older explicit overrides outside the range are clamped.
        PresetEngine.Preset low=PresetEngine.choose(crystal,PresetEngine.Type.MOVIE_SHOW,
                PresetEngine.Mode.STANDARD,144,25,false,PresetEngine.PortraitMode.FIT,null,
                PresetEngine.FrameMode.FIT,PresetEngine.PictureMode.SHARP);
        PresetEngine.Preset high=PresetEngine.choose(crystal,PresetEngine.Type.MOVIE_SHOW,
                PresetEngine.Mode.STANDARD,240,25,false,PresetEngine.PortraitMode.FIT,null,
                PresetEngine.FrameMode.FILL,PresetEngine.PictureMode.SHARP);
        check(low.pictureHeight==184&&low.requestedHeight==184,"144px override not clamped to 184");
        check(high.pictureHeight==216&&high.requestedHeight==216,"240px override not clamped to 216");
        PresetEngine.SourceInfo tv=new PresetEngine.SourceInfo();
        tv.width=640;tv.height=480;tv.fps=25;tv.audioPresent=true;
        PresetEngine.Preset fourThreeFull=PresetEngine.choose(tv,PresetEngine.Type.YOUTUBE,
                PresetEngine.Mode.STANDARD,0,25,false,PresetEngine.PortraitMode.FIT,null,
                PresetEngine.FrameMode.AUTO,PresetEngine.PictureMode.SHARP);
        check(fourThreeFull.frameMode==PresetEngine.FrameMode.FILL && fourThreeFull.pictureHeight==216,
                "4:3 FULL exceeded 216px height limit");
        PresetEngine.Preset fourThreePreserve=PresetEngine.choose(tv,PresetEngine.Type.YOUTUBE,
                PresetEngine.Mode.STANDARD,0,25,true,PresetEngine.PortraitMode.FIT,null,
                PresetEngine.FrameMode.AUTO,PresetEngine.PictureMode.SHARP);
        check(fourThreePreserve.frameMode==PresetEngine.FrameMode.FIT && fourThreePreserve.pictureHeight==192,
                "4:3 PRESERVE lost uncropped view");
        PresetEngine.Preset manual43=PresetEngine.choose(tv,PresetEngine.Type.YOUTUBE,
                PresetEngine.Mode.STANDARD,208,25,false,PresetEngine.PortraitMode.FIT,null,
                PresetEngine.FrameMode.AUTO,PresetEngine.PictureMode.SHARP);
        check(manual43.requestedHeight==208,"Explicit height lost for 4:3 queue member");
        PresetEngine.SourceInfo vertical=new PresetEngine.SourceInfo();
        vertical.width=360;vertical.height=640;vertical.fps=25;
        PresetEngine.Preset manualPortrait=PresetEngine.choose(vertical,PresetEngine.Type.PORTRAIT,
                PresetEngine.Mode.STANDARD,208,25,false,PresetEngine.PortraitMode.FIT,null,
                PresetEngine.FrameMode.AUTO,PresetEngine.PictureMode.SHARP);
        check(manualPortrait.requestedHeight==208,"Explicit height lost for portrait queue member");
        PresetEngine.SourceInfo square=new PresetEngine.SourceInfo();square.width=640;square.height=640;
        check(PresetEngine.choose(square,PresetEngine.Type.YOUTUBE,PresetEngine.Mode.STANDARD,
                208,25,false,PresetEngine.PortraitMode.FIT,null,
                PresetEngine.FrameMode.AUTO,PresetEngine.PictureMode.SHARP).frameMode==PresetEngine.FrameMode.FIT,
                "square should fit in AUTO");
        PresetEngine.SourceInfo s=new PresetEngine.SourceInfo();
        s.width=1440;s.height=720;s.fps=24;s.audioPresent=true;
        PresetEngine.Preset p=PresetEngine.choose(s,PresetEngine.Type.MOVIE_SHOW,
             PresetEngine.Mode.STANDARD,192,0,false,PresetEngine.PortraitMode.FIT,null);
        String standard=PresetEngine.buildVideoCommand("in.mkv","out.amv",p,0,30,true,0);
        String faster=PresetEngine.buildVideoCommand("in.mkv","out.amv",p,0,30,true,1);
        String maximum=PresetEngine.buildVideoCommand("in.mkv","out.amv",p,0,30,true,2);
        check(standard.contains("-f amv"),"AMV muxer missing");
        check(faster.contains("-threads 0")&&!faster.contains("-threads 2"),"Faster artificially limits encoder threads");
        check(!faster.contains("-filter_threads")&&maximum.contains("-filter_threads 0"),"Faster should preserve Termux-like filter defaults; Maximum auto filters");
        check(!maximum.contains("-autorotate"),"Unsupported explicit autorotate still present");
        check(!standard.contains("libx264"),"Incorrect preview codec in AMV command");
        check(standard.contains("-block_size 882"),"25fps AMV audio block size incorrect");
        System.out.println("PASS: "+cases+" framing/processing cases and 8 command checks");
    }
}
