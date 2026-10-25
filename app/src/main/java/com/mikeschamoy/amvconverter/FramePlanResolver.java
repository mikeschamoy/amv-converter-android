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
        if (chosen == null && autoBars && !info.image && probe != null && !probe.cancelled()) {
            // A source crop is accepted only when two widely spaced samples agree.
            PresetEngine.Crop a = probe.sourceAt(begin+span*.25);
            PresetEngine.Crop b = span > 5 ? probe.sourceAt(begin+span*.70) : a;
            if (validSourceCrop(info,a) && validSourceCrop(info,b) &&
                    Math.abs(a.w-b.w) <= 16 && Math.abs(a.h-b.h) <= 16 &&
                    Math.abs(a.x-b.x) <= 16 && Math.abs(a.y-b.y) <= 16) chosen = a;
        }
        PresetEngine.Preset selected = PresetEngine.choose(info,type,mode,height,fps,
                preserve43,portrait,chosen,frame,processing);
        // 216 is the encoder's frame geometry.  It does not, by itself,
        // prove that the source has 216 rows of non-black picture.
        selected.visiblePictureVerified=false;
        selected.frameAnalysis="Unverified: source content may contain black borders";
        if (probe == null || probe.cancelled() || !autoBars || manualCrop != null || info.image ||
                type != PresetEngine.Type.MOVIE_SHOW || frame != PresetEngine.FrameMode.AUTO ||
                height > 0 || info.ratio() < 1.70 || info.ratio() > 1.86 ||
                selected.pictureWidth != 256 || selected.pictureHeight != 216 || span < 3) return selected;

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
        PresetEngine.Crop fixed=PictureContentGuard.sourceCrop(info,selected,anchor);
        if(fixed!=null){
            PresetEngine.Preset corrected=PresetEngine.choose(info,type,mode,height,fps,
                    preserve43,portrait,fixed,frame,processing);
            if(corrected.pictureWidth==256 && corrected.pictureHeight==216 &&
                    verifySamples(probe,corrected,anchor,samples,positions,begin,span)){
                corrected.visiblePictureVerified=true;
                corrected.frameAnalysis="216px visible picture verified (source border crop)";
                return corrected;
            }
        }
        // Source-level inversion can fail for non-square pixels or source crops
        // whose scaling direction changed. A VERIFIED output-level viewport crop
        // is a second route: remove the observed black bands, scale uniformly
        // to 216px high and center-crop horizontally. It must pass two probes.
        // Never zoom from a single dark or contradictory frame.
        if(anchor.h>=144 && anchor.h<184 && anchor.w>=248 && anchor.x>=62 && anchor.x<=66){
            String original=selected.filter;
            selected.filter=original+",crop="+anchor.w+":"+anchor.h+":"+anchor.x+":"+anchor.y+
                    ",scale=-2:216:flags=lanczos,crop=256:216:x='(iw-ow)/2':y='(ih-oh)/2'"+
                    ",pad=320:240:64:12:black,setsar=1";
            if(verifySamples(probe,selected,anchor,samples,positions,begin,span)){
                selected.visiblePictureVerified=true;
                selected.frameAnalysis="216px visible picture verified (frame crop)";
                return selected;
            }
            selected.filter=original;
        }
        selected.frameAnalysis="216px encoded frame; embedded bars detected but correction was not verified";
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
            // This is the 216px MOVIE AUTO path, not the general 184px clamp.
            // Only claim 216px visible when cropdetect actually measures 216.
            // Dark edge pixels may leave the result unverified; do not lie.
            if(measured!=null && measured.w>=248 && measured.h==216 &&
                    measured.y==12)passes++;
            if(passes>=2)return true;
        }
        return false;
    }

    private static boolean validSourceCrop(PresetEngine.SourceInfo source,PresetEngine.Crop crop) {
        return PresetEngine.isMeaningfulCrop(source,crop);
    }
}
