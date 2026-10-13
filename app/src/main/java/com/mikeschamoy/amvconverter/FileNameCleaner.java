package com.mikeschamoy.amvconverter;
import java.util.*;
import java.util.regex.*;

public final class FileNameCleaner {
    private static final Pattern EP=Pattern.compile("(?i)(?:S(\\d{1,2})[ ._-]*E(\\d{1,2})|(\\d{1,2})x(\\d{1,2}))");
    private FileNameCleaner(){}
    public static String clean(String raw,PresetEngine.Type type,boolean image){
        String s=raw.replaceFirst("\\.[^.]+$","").replace('_',' ').replace('.',' ');
        s=s.replaceAll("\\[[^\\]]*\\]"," ");
        s=s.replaceAll("(?i)\\b(2160p|1080p|720p|480p|web[- ]?dl|webrip|bluray|brrip|x26[45]|hevc|h\\.?264|aac|ddp?5?\\.?1|galaxytv|eztvx?\\.?to|yts(?:\\.gg|\\.bz)?|reenc|high|low)\\b"," ");
        s=s.replaceAll("(?i)\\b(19|20)\\d{2}\\b"," ").replaceAll("[(){}]"," ").replaceAll("\\s+"," ").trim();
        Matcher m=EP.matcher(s);
        if(m.find()){
            int se=Integer.parseInt(m.group(1)!=null?m.group(1):m.group(3));
            int ep=Integer.parseInt(m.group(2)!=null?m.group(2):m.group(4));
            String title=s.substring(0,m.start()).replaceAll("[-–—]+$","").trim();
            title=shorten(title,Math.max(3,16-("S"+se+"E"+ep).length()-1));
            return title+"-S"+se+"E"+ep+(image?".jpg":".amv");
        }
        s=s.replaceAll("(?i)\\b(full movie|official video|movieclips|transworld skateboarding|skateboarding)\\b"," ").replaceAll("\\s+"," ").trim();
        return shorten(s,16)+(image?".jpg":".amv");
    }
    private static String shorten(String s,int max){
        if(s.length()<=max)return s;
        String[] words=s.split("\\s+");StringBuilder b=new StringBuilder();
        for(String w:words){
            String x=w.toLowerCase(Locale.US);
            if(x.equals("the")||x.equals("of")||x.equals("and")||x.equals("a")||x.equals("an"))continue;
            if(!w.isEmpty())b.append(Character.toUpperCase(w.charAt(0)));
            if(b.length()>=5)break;
        }
        if(b.length()>=2&&b.length()<=max)return b.toString();
        return s.substring(0,Math.min(max,s.length())).trim();
    }
}
