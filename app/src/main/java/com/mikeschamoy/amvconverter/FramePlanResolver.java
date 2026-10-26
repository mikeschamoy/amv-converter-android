package com.mikeschamoy.amvconverter;

/**
 * ONE frame-plan decision for foreground preview, still preview and full AMV
 * encoding.  Android media probing is injected so this policy is JVM-testable.
 */
public final class FramePlanResolver {
    private FramePlanResolver() {}

    public interface Probe {
        PresetEngine.Crop sourceAt(double seconds);
        PresetEngine.Crop renderedAt(PresetEngine.Preset preset, double seconds);
        boolean cancelled();
    }

    public static PresetEngine.Preset resolve(
            PresetEngine.SourceInfo info, PresetEngine.Type type, PresetEngine.Mode mode,
            int height, int fps, boolean preserve43, PresetEngine.PortraitMode portrait,
            PresetEngine.Crop manualCrop, PresetEngine.FrameMode frame,
            PresetEngine.PictureMode processing, boolean autoBars,
            double start, double end, Probe probe) {
        if (info == null) throw new IllegalArgumentException("Missing video metadata");
        PresetEngine.Crop chosen = manualCrop;
        double sourceDuration = Math.max(0, info.durationSeconds);
        double begin = Math.max(0, start);
        double span = end > begin ? end-begin : Math.max(0, sourceDuration-begin);
        if (span < 0.1) span = sourceDuration;
        if(chosen==null && autoBars && frame==PresetEngine.FrameMode.AUTO &&
                height<=0 && !info.image && probe!=null && !probe.cancelled()){
            // Multiple independent points. A dark or transition frame is not
            // a negative vote; conflicting meaningful aspect ratios are.
            PresetEngine.Crop a=probe.sourceAt(begin+span*.20);
            PresetEngine.Crop b=span>5?probe.sourceAt(begin+span*.48):a;
            PresetEngine.Crop c=span>5?probe.sourceAt(begin+span*.77):b;
            PresetEngine.Crop[] candidates={a,b,c};
            for(PresetEngine.Crop candidate:candidates){
                if(!validSourceCrop(info,candidate))continue;
                int agrees=0,conflicts=0;
                for(PresetEngine.Crop other:candidates){
                    if(!validSourceCrop(info,other))continue;
                    if(sourceCropAgrees(candidate,other))agrees++;
                    else conflicts++;
                }
                if(agrees>=2 && conflicts==0){chosen=candidate;break;}
            }
        }
        PresetEngine.Preset selected = PresetEngine.choose(info,type,mode,height,fps,
                preserve43,portrait,chosen,frame,processing);
        selected.visiblePictureVerified=false;
        if(chosen!=null && selected.detectedCrop!=null){
            selected.frameAnalysis=String.format(java.util.Locale.US,
                    "Content ratio %.2f:1 detected; Auto selected %dpx (source crop)",
                    selected.effectiveAspectRatio,selected.pictureHeight);
        }else{
            selected.frameAnalysis="No consistent embedded bars detected; using source aspect ratio";
        }
        // An Auto picture already corrected using the detected source crop
        // should NOT enter the older 216-only output-level repair path.
        if (probe == null || probe.cancelled() || !autoBars || manualCrop != null || info.image ||
                frame != PresetEngine.FrameMode.AUTO || height > 0 || chosen != null ||
                selected.pictureWidth != 256 || selected.pictureHeight != 216 || span < 3)
            return selected;

        // Probe ACTUAL filtered pixels, not metadata.  The same sample positions
        // and correction policy are used for preview and full conversions.
        double[] positions={.10,.24,.38,.52,.66,.82};
        PresetEngine.Crop[] samples=new PresetEngine.Crop[positions.length];
        for (int i=0;i<positions.length && !probe.cancelled();i++)
            samples[i]=probe.renderedAt(selected,begin+span*positions[i]);
        if (probe.cancelled()) return selected;
        PresetEngine.Crop anchor=PictureContentGuard.consensusUndersized(samples);
        if(anchor==null){
            if (cleanFrameConsensus(samples)) {
                selected.visiblePictureVerified=true;
                selected.frameAnalysis="216px picture confirmed by independent frames";
            } else {
                selected.frameAnalysis="Mixed/dark frames: 216px encoded region, visible image unverified";
            }
            return selected;
        }
        // A cropped source has its OWN aspect ratio, which determines the
        // Auto height. Never force a detected 2.35:1 film back to 216px.
        PresetEngine.Crop fixed=PictureContentGuard.sourceCrop(info,selected,anchor);
        if(fixed!=null){
            PresetEngine.Preset corrected=PresetEngine.choose(info,type,mode,height,fps,
                    preserve43,portrait,fixed,frame,processing);
            if(corrected.pictureWidth==256 && corrected.pictureHeight>=184 &&
                    corrected.pictureHeight<=216 &&
                    verifySamples(probe,corrected,anchor,samples,positions,begin,span)){
                corrected.visiblePictureVerified=true;
                corrected.frameAnalysis=String.format(java.util.Locale.US,
                    "Detected %.2f:1 picture; %dpx verified (source borders removed)",
                    corrected.effectiveAspectRatio,corrected.pictureHeight);
                return corrected;
            }
        }
        // Fallback only for consistently measured embedded bars. No source
        // crop can be inferred reliably: crop the already-rendered picture,
        // then resize uniformly to the Auto height for inferred content ratio.
        if(anchor.h>=136 && anchor.h<184 && anchor.w>=248 && anchor.x>=62 && anchor.x<=66){
            double effectiveRatio=info.ratio()*selected.pictureHeight/(double)anchor.h;
            int target=PresetEngine.autoContentHeight(effectiveRatio);
            String original=selected.filter;
            int oldHeight=selected.pictureHeight;
            int oldRequested=selected.requestedHeight;
            double oldRatio=selected.effectiveAspectRatio;
            PresetEngine.FrameMode oldMode=selected.frameMode;
            String oldName=selected.name;
            selected.filter=original+",crop="+anchor.w+":"+anchor.h+":"+anchor.x+":"+anchor.y+
                    ",scale=-2:"+target+":flags=lanczos,crop=256:"+target+
                    ":x='(iw-ow)/2':y='(ih-oh)/2',pad=320:240:64:"+((240-target)/2)+":black,setsar=1";
            selected.pictureHeight=target;
            selected.requestedHeight=target;
            selected.effectiveAspectRatio=effectiveRatio;
            selected.frameMode=PresetEngine.FrameMode.BALANCED;
            selected.name=String.format(java.util.Locale.US,
                    "MOVIE AUTO (content %.2f:1) • picture 256×%d • %dfps",
                    effectiveRatio,target,selected.outputFps);
            if(verifySamples(probe,selected,anchor,samples,positions,begin,span)){
                selected.visiblePictureVerified=true;
                selected.frameAnalysis=String.format(java.util.Locale.US,
                    "Detected %.2f:1 picture; %dpx verified (frame border crop)",
                    effectiveRatio,target);
                return selected;
            }
            selected.filter=original;
            selected.pictureHeight=oldHeight;
            selected.requestedHeight=oldRequested;
            selected.effectiveAspectRatio=oldRatio;
            selected.frameMode=oldMode;
            selected.name=oldName;
        }
        selected.frameAnalysis="Embedded bars detected but Auto crop unverified; original picture retained";
        return selected;
    }

    private static boolean cleanFrameConsensus(PresetEngine.Crop[] samples){
        int clean=0,shortFrames=0;
        for(PresetEngine.Crop c:samples){
            if(c==null || c.w<248 || c.x<62 || c.x>66)continue;
            if(c.h==216 && c.y==12)clean++;
            else if(c.h<184)shortFrames++;
        }
        return clean>=3 && shortFrames==0;
    }

    private static boolean verifySamples(Probe probe,PresetEngine.Preset candidate,
            PresetEngine.Crop anchor,PresetEngine.Crop[] samples,double[] positions,double begin,double span){
        int passes=0;
        for(int i=0;i<samples.length && !probe.cancelled();i++){
            if(!PictureContentGuard.consistent(anchor,samples[i]))continue;
            PresetEngine.Crop measured=probe.renderedAt(candidate,begin+span*positions[i]);
            // Compare to the actual Auto-selected picture size, never a
            // hard-coded 216. Dark scenes remain unverified, not a pass.
            if(measured!=null && measured.w>=248 &&
                    measured.h==candidate.pictureHeight &&
                    measured.y==(240-candidate.pictureHeight)/2)passes++;
            if(passes>=2)return true;
        }
        return false;
    }

    private static boolean sourceCropAgrees(PresetEngine.Crop a,PresetEngine.Crop b){
        return a!=null && b!=null && Math.abs(a.w-b.w)<=16 &&
                Math.abs(a.h-b.h)<=16 && Math.abs(a.x-b.x)<=16 &&
                Math.abs(a.y-b.y)<=16;
    }

    private static boolean validSourceCrop(PresetEngine.SourceInfo source,PresetEngine.Crop crop) {
        return PresetEngine.isMeaningfulCrop(source,crop);
    }
}
