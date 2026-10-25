package com.mikeschamoy.amvconverter;

/**
 * Converts a stable black-border measurement from the *rendered* 320x240
 * picture into a high-resolution input crop, before encoding the whole video.
 * This is separate from PresetEngine's mathematical 184..216 height clamp:
 * letterboxing already painted into the source can survive that clamp.
 */
public final class PictureContentGuard {
    private PictureContentGuard() {}

    public static boolean undersized(PresetEngine.Crop measured) {
        return measured != null && measured.h > 0
                && measured.h < PresetEngine.MIN_PICTURE_HEIGHT;
    }

    public static final int UNKNOWN=-1;
    public static final int ACCEPT=0;
    public static final int REPAIR=1;

    /**
     * Fail closed rather than turning an unavailable cropdetect result into
     * a false claim that the visible image is >=184px high.
     * At least two independently sampled pictures must be measurable.
     */
    public static int evaluate(PresetEngine.Crop[] samples){
        if(samples==null)return UNKNOWN;
        int seen=0,undersized=0;
        PresetEngine.Crop reference=null;
        for(PresetEngine.Crop c:samples){
            if(c==null || c.h<=0 || c.w<=0 || c.x<0 || c.y<0
                    || c.x+c.w>320 || c.y+c.h>240)continue;
            seen++;
            if(undersized(c)){
                undersized++;
                if(reference!=null && !consistent(reference,c))return UNKNOWN;
                reference=c;
            }
        }
        if(seen<2)return UNKNOWN;
        if(undersized==0)return ACCEPT;
        // Mixed aspect ratios or one-off dark scenes are not safe to crop.
        if(undersized!=seen || undersized<2 || reference==null
                || reference.w<248)return UNKNOWN;
        return REPAIR;
    }

    /** Combine cropdetect's frame-to-frame 2px rounding fluctuations.
     * Older code demanded 60% EXACT string equality, rejecting valid scenes
     * whose nonblack bounds alternate between e.g. 162 and 164 pixels.
     */
    public static PresetEngine.Crop clusterFrameCrops(java.util.List<PresetEngine.Crop> frames){
        if(frames==null || frames.size()<6)return null;
        int most=0;PresetEngine.Crop best=null;
        for(PresetEngine.Crop candidate:frames){
            if(candidate==null || candidate.x<0 || candidate.y<0 ||
                    candidate.w<=0 || candidate.h<=0 || candidate.x+candidate.w>320 ||
                    candidate.y+candidate.h>240)continue;
            int count=0;
            for(PresetEngine.Crop f:frames){
                if(f!=null && Math.abs(f.w-candidate.w)<=4 &&
                        Math.abs(f.h-candidate.h)<=4 && Math.abs(f.x-candidate.x)<=4 &&
                        Math.abs(f.y-candidate.y)<=4)count++;
            }
            if(count>most){most=count;best=candidate;}
        }
        if(most<Math.max(6,(int)Math.ceil(frames.size()*0.50)))return null;
        return best;
    }

    /** Stable undersized picture borders across independent source positions.
     * Invalid/dark frames do not count as either evidence for or against crop.
     * A measured near-full picture at another position blocks speculative crop.
     */
    public static PresetEngine.Crop consensusUndersized(PresetEngine.Crop[] samples){
        if(samples==null || samples.length<2)return null;
        PresetEngine.Crop best=null;
        int bestVotes=0, good=0, clearlyFull=0;
        for(PresetEngine.Crop c:samples){
            if(!validCanvasCrop(c))continue;
            good++;
            if(c.w>=248 && c.h>=208 && c.x>=62 && c.x<=66)clearlyFull++;
        }
        for(PresetEngine.Crop candidate:samples){
            if(!undersized(candidate) || candidate.h<136 || candidate.w<248 ||
                    candidate.x<62 || candidate.x>66)continue;
            int votes=0;
            for(PresetEngine.Crop other:samples){
                if(validCanvasCrop(other) && consistent(candidate,other))votes++;
            }
            if(votes>bestVotes){bestVotes=votes;best=candidate;}
        }
        // An isolated dark or partial-frame reading is not sufficient.
        // Require at least two independently agreeing positions, a majority of
        // measurable positions, and strong protection against mixed-aspect video.
        if(bestVotes<2 || bestVotes*2<good || clearlyFull>=2 ||
                (clearlyFull==1 && bestVotes<3))return null;
        return best;
    }

    private static boolean validCanvasCrop(PresetEngine.Crop c){
        return c!=null && c.w>0 && c.h>0 && c.x>=0 && c.y>=0 &&
                c.x+c.w<=320 && c.y+c.h<=240;
    }

    public static boolean consistent(PresetEngine.Crop a, PresetEngine.Crop b) {
        return a != null && b != null && a.w >= 248 && b.w >= 248
                && Math.abs(a.x-b.x) <= 4 && Math.abs(a.y-b.y) <= 4
                && Math.abs(a.w-b.w) <= 4 && Math.abs(a.h-b.h) <= 4;
    }

    public static PresetEngine.Crop sourceCrop(PresetEngine.SourceInfo source,
                PresetEngine.Preset preset, PresetEngine.Crop rendered) {
        if(source==null || preset==null || rendered==null || source.width<=0
                || source.height<=0 || Math.abs(source.rotation)%180==90
                || Math.abs(source.sampleAspect-1.0)>0.0001
                || preset.imageOutput || preset.pictureWidth!=256
                || rendered.x<62 || rendered.x>66 || rendered.w<248
                || !undersized(rendered))return null;
        int h=preset.pictureHeight;
        int pad=(240-h)/2;
        if(h<184||h>216 || rendered.y<pad || rendered.y+rendered.h>pad+h)return null;
        PresetEngine.Crop prior=preset.detectedCrop;
        int srcW=prior==null?source.width:prior.w;
        int srcH=prior==null?source.height:prior.h;
        int srcX=prior==null?0:prior.x;
        int srcY=prior==null?0:prior.y;
        // Only invert height-determined scaling. If FFmpeg first crops height,
        // this simple inverse would be incorrect and is deliberately rejected.
        double sourceRatio=(double)srcW/srcH;
        if(sourceRatio+0.0001 < 256.0/h)return null;
        int missingTop=rendered.y-pad;
        int missingBottom=(pad+h)-(rendered.y+rendered.h);
        if(missingTop<0||missingBottom<0)return null;
        int topSrc=even(Math.round(missingTop*(double)srcH/h));
        int bottomSrc=even(Math.round(missingBottom*(double)srcH/h));
        int cropH=srcH-topSrc-bottomSrc;
        if(cropH<Math.max(64,source.height/3))return null;
        PresetEngine.Crop corrected=new PresetEngine.Crop(srcW,cropH,srcX,srcY+topSrc);
        if(!PresetEngine.isMeaningfulCrop(source,corrected))return null;
        return corrected;
    }

    private static int even(long n) { return (int)(2*Math.round(n/2.0)); }
}
