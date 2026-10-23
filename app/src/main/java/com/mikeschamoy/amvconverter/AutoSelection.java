package com.mikeschamoy.amvconverter;
import java.util.Locale;

/** Deterministic metadata-and-filename hints; no network or content guessing required. */
public final class AutoSelection {
    private AutoSelection(){}
    public static PresetEngine.Type choose(PresetEngine.SourceInfo s,String filename){
        if(s==null)return PresetEngine.Type.YOUTUBE;
        if(s.isPortrait())return PresetEngine.Type.PORTRAIT;
        String n=filename==null?"":filename.toLowerCase(Locale.US);
        if(n.matches(".*(?:anime|cartoon|animated|animation|pixar|disney|spongebob|simpsons|rick.and.morty|family.guy).*"))
            return PresetEngine.Type.CARTOON;
        if(s.ratio()>2.12 || s.durationSeconds>=1800 ||
            n.matches(".*(?:webrip|bluray|brrip|hdrip|\\.mkv|movie|yts|1080p\\.web).*"))
            return PresetEngine.Type.MOVIE_SHOW;
        return PresetEngine.Type.YOUTUBE;
    }
}
