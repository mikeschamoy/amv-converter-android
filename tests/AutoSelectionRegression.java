package com.mikeschamoy.amvconverter;
public final class AutoSelectionRegression {
    private static void check(boolean v,String reason){if(!v)throw new AssertionError(reason);}
    public static void main(String[] args){
        PresetEngine.SourceInfo movie=new PresetEngine.SourceInfo();movie.width=1920;movie.height=800;movie.durationSeconds=6500;movie.fps=24;
        check(AutoSelection.choose(movie,"My.Movie.2026.mkv")==PresetEngine.Type.MOVIE_SHOW,"movie inference");
        PresetEngine.SourceInfo yt=new PresetEngine.SourceInfo();yt.width=1920;yt.height=1080;yt.durationSeconds=700;yt.fps=30;
        check(AutoSelection.choose(yt,"Skate Video.mp4")==PresetEngine.Type.YOUTUBE,"video inference");
        check(AutoSelection.choose(yt,"Animated Series.mp4")==PresetEngine.Type.CARTOON,"cartoon filename inference");
        PresetEngine.SourceInfo vertical=new PresetEngine.SourceInfo();vertical.width=1080;vertical.height=1920;
        check(AutoSelection.choose(vertical,"vertical video.mp4")==PresetEngine.Type.PORTRAIT,"portrait inference");
        String s=FileNameCleaner.clean("Very Long Source Video Name With Special Stuff.mkv",PresetEngine.Type.YOUTUBE,false);
        check(s.endsWith(".amv"),"file must keep .amv for filesystem");
        check(s.length()-4<=16,"base-name length must be max 16");
        check(FileNameCleaner.displayBase("Very Long Source Video Name With Special Stuff.mkv",PresetEngine.Type.YOUTUBE,false).length()<=16,"display base length");
        System.out.println("PASS: Auto metadata cases and 16-character base filename (+ .amv)");
    }
}
