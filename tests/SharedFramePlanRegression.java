package com.mikeschamoy.amvconverter;

/** Standalone JVM parity and behavior tests, no Android SDK needed. */
public final class SharedFramePlanRegression {
    private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    private static PresetEngine.SourceInfo film(){
        PresetEngine.SourceInfo s=new PresetEngine.SourceInfo();
        s.width=640;s.height=360;s.fps=23.976;s.durationSeconds=3366.496;
        s.audioPresent=true;return s;
    }
    private static final class Fake implements FramePlanResolver.Probe {
        int sourceCalls=0,renderCalls=0;
        boolean letterbox,sourceBars,dark,partial,weakRepair;
        boolean oneDarkSample,oneCleanSample;
        Fake(boolean letterbox){this.letterbox=letterbox;}
        public PresetEngine.Crop sourceAt(double at){sourceCalls++;
            return sourceBars?new PresetEngine.Crop(640,280,0,40):null;
        }
        public PresetEngine.Crop renderedAt(PresetEngine.Preset p,double at){renderCalls++;
            if(dark || (oneDarkSample && at>750 && at<900))return null;
            if(!letterbox)return new PresetEngine.Crop(256,216,64,12);
            if(oneCleanSample && at>750 && at<900 && !p.filter.contains("pad=320:240:64:12:black,setsar=1")
                    && p.detectedCrop==null) return new PresetEngine.Crop(256,216,64,12);
            if(p.filter.contains(",pad=320:240:64:"))
                return new PresetEngine.Crop(256,weakRepair?180:p.pictureHeight,64,weakRepair?30:(240-p.pictureHeight)/2);
            if(p.detectedCrop!=null && p.detectedCrop.h<=300)
                return new PresetEngine.Crop(256,weakRepair?180:p.pictureHeight,64,weakRepair?30:(240-p.pictureHeight)/2);
            return new PresetEngine.Crop(256,168,64,36);
        }
        public boolean cancelled(){return false;}
    }
    private static PresetEngine.Preset choose(PresetEngine.SourceInfo s,
           int height,PresetEngine.FrameMode framing,boolean bars,
           PresetEngine.Crop manual,FramePlanResolver.Probe probe){
        return FramePlanResolver.resolve(s,PresetEngine.Type.MOVIE_SHOW,
            PresetEngine.Mode.STANDARD,height,0,false,
            PresetEngine.PortraitMode.FIT,manual,framing,
            PresetEngine.PictureMode.SHARP,bars,0,0,probe);
    }
    public static void main(String[] args){
        PresetEngine.SourceInfo source=film();
        Fake preview=new Fake(true);Fake conversion=new Fake(true);
        PresetEngine.Preset a=choose(source,0,PresetEngine.FrameMode.AUTO,true,null,preview);
        PresetEngine.Preset b=choose(source,0,PresetEngine.FrameMode.AUTO,true,null,conversion);
        check(a.filter.equals(b.filter),"preview/full must have identical -vf");
        check(a.pictureHeight==192&&a.pictureWidth==256,"widescreen content should select 192");
        check(a.detectedCrop!=null,"detected source crop must be retained");
        check(a.detectedCrop.h==280,"source-level embedded letterbox correction");
        check(a.filter.contains("crop=640:280:0:40"),"correct crop must precede scale");
        check(preview.renderCalls==conversion.renderCalls,"same scan count");
        check(a.filter.contains("pad=320:240"),"320x240 remains");
        check(PresetEngine.PLAYER_VIEW_X==64,"X+64 remains");
        Fake noBars=new Fake(false);
        PresetEngine.Preset clean=choose(source,0,PresetEngine.FrameMode.AUTO,true,null,noBars);
        check(clean.pictureHeight==216&&clean.detectedCrop==null,"clean widescreen keeps 216 with no crop");
        check(clean.visiblePictureVerified,"clean 216 picture gets positive proof");
        Fake failed=new Fake(true);failed.dark=true;
        PresetEngine.Preset dark=choose(source,0,PresetEngine.FrameMode.AUTO,true,null,failed);
        check(dark.pictureHeight==216,"dark scans must not cancel conversion");
        check(!dark.visiblePictureVerified && dark.frameAnalysis.contains("unverified"),
          "dark scans must not be reported as verified");
        Fake weak=new Fake(true);weak.weakRepair=true;
        PresetEngine.Preset weakResult=choose(source,0,PresetEngine.FrameMode.AUTO,true,null,weak);
        check(!weakResult.visiblePictureVerified && weakResult.filter.equals(
           PresetEngine.choose(source,PresetEngine.Type.MOVIE_SHOW,
           PresetEngine.Mode.STANDARD,0,0,false,PresetEngine.PortraitMode.FIT,null,
           PresetEngine.FrameMode.AUTO,PresetEngine.PictureMode.SHARP).filter),
           "weak correction must not be accepted");
        Fake oneDark=new Fake(true);oneDark.oneDarkSample=true;
        PresetEngine.Preset recovered=choose(source,0,PresetEngine.FrameMode.AUTO,true,null,oneDark);
        check(recovered.visiblePictureVerified && recovered.pictureHeight==192,
           "one dark scan must not veto a consistently verified border correction");
        Fake manualProbe=new Fake(true);
        PresetEngine.Crop explicit=new PresetEngine.Crop(600,300,20,30);
        PresetEngine.Preset man=choose(source,192,PresetEngine.FrameMode.FILL,true,explicit,manualProbe);
        check(man.pictureHeight==192 && manualProbe.renderCalls==0 &&
          man.filter.contains("crop=600:300:20:30"),"manual override preserved");
        Fake disable=new Fake(true);
        PresetEngine.Preset off=choose(source,0,PresetEngine.FrameMode.AUTO,false,null,disable);
        check(off.pictureHeight==216 && disable.sourceCalls==0 && disable.renderCalls==0,
          "auto-bars OFF disables hidden scans");
        Fake src=new Fake(true);src.sourceBars=true;
        PresetEngine.Preset withSource=choose(source,0,PresetEngine.FrameMode.AUTO,true,null,src);
        check(withSource.pictureHeight==192&&withSource.detectedCrop!=null&&
          withSource.detectedCrop.h==280,"source border crop accepted");
        PresetEngine.Crop[] withSingleFull={new PresetEngine.Crop(256,164,64,38),
            new PresetEngine.Crop(256,162,64,38),
            new PresetEngine.Crop(256,216,64,12)};
        check(PictureContentGuard.consensusUndersized(withSingleFull)==null,
            "two short samples and one full scene are not a safe crop consensus");
        PresetEngine.Crop[] withDark={new PresetEngine.Crop(256,164,64,38),
            new PresetEngine.Crop(256,162,64,38), null,
            new PresetEngine.Crop(256,164,64,38), new PresetEngine.Crop(256,216,64,12)};
        check(PictureContentGuard.consensusUndersized(withDark)!=null,
            "three stable undersized samples and one outlier should be actionable");
        // The exact same test also represents queue auto-per-item resolution.
        Fake queued=new Fake(true);
        PresetEngine.Preset queuePreset=choose(source,0,PresetEngine.FrameMode.AUTO,true,null,queued);
        check(queuePreset.filter.equals(a.filter),"queue and single frame plan parity");
        PresetEngine.SourceInfo nonSquare=film();nonSquare.sampleAspect=1.03;
        Fake fallbackProbe=new Fake(true);
        PresetEngine.Preset fallback=choose(nonSquare,0,PresetEngine.FrameMode.AUTO,true,null,fallbackProbe);
        check(fallback.filter.contains(",pad=320:240:64:28:black,setsar=1"),
          "verified output-level crop fallback when source inverse is unavailable");
        System.out.println("PASS: shared frame plan parity, content ratio, manual, dark, queue, X64");
        System.out.println("CORRECTED_FILTER="+a.filter);
        System.out.println("UNCORRECTED_FILTER="+clean.filter);
        System.out.println("FALLBACK_FILTER="+fallback.filter);
    }
}
