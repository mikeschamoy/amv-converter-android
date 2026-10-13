package com.mikeschamoy.amvconverter;

public final class PresetEngine {
    public enum Type { YOUTUBE, MOVIE_SHOW, CARTOON }

    public static final class Crop {
        public int w, h, x, y;
        public Crop(int w, int h, int x, int y) {
            this.w = w; this.h = h; this.x = x; this.y = y;
        }
        public double ratio() { return h == 0 ? 0 : (double) w / h; }
        @Override public String toString() { return "crop=" + w + ":" + h + ":" + x + ":" + y; }
    }

    public static final class SourceInfo {
        public int width;
        public int height;
        public double fps;
        public double durationSeconds;
        public double ratio() { return height == 0 ? 16.0 / 9.0 : (double) width / height; }
        public boolean isFourThree() { return Math.abs(ratio() - (4.0/3.0)) < 0.035; }
    }

    public static final class Preset {
        public String name;
        public String filter;
        public int outputFps;
        public int blockSize;
        public boolean cartoonQ1;
        public int visibleHeight;
        public boolean fourThree;
        public boolean fourThreePreserve;
        public Crop detectedCrop;
    }

    private PresetEngine() {}

    public static Preset choose(SourceInfo s, Type type, int heightOverride,
                                boolean fourThreePreserve, Crop crop) {
        Preset p = new Preset();
        p.outputFps = s.fps >= 27.0 ? 30 : 25;
        p.blockSize = p.outputFps == 30 ? 735 : 882;
        p.cartoonQ1 = type == Type.CARTOON;
        p.detectedCrop = crop;

        boolean cropLooksFourThree = crop != null && Math.abs(crop.ratio() - 4.0/3.0) < 0.05;
        if (cropLooksFourThree && !s.isFourThree()) {
            int correctedX = Math.max(0, crop.x - 32);
            p.name = "PILLARBOX 4:3 - " + p.outputFps;
            p.filter = "crop=" + crop.w + ":" + crop.h + ":" + correctedX + ":" + crop.y
                    + ",scale=320:240:flags=lanczos,setsar=1";
            p.cartoonQ1 = false;
            p.fourThree = true;
            p.visibleHeight = 240;
            return p;
        }

        if (s.isFourThree()) {
            p.fourThree = true;
            p.cartoonQ1 = false;
            if (fourThreePreserve) {
                p.name = "4:3 PRESERVE - " + p.outputFps;
                p.filter = "scale=288:216:flags=lanczos,pad=320:240:32:12:black,setsar=1";
                p.fourThreePreserve = true;
                p.visibleHeight = 216;
            } else {
                p.name = "4:3 FULL - " + p.outputFps;
                p.filter = "scale=320:240:flags=lanczos,setsar=1";
                p.visibleHeight = 240;
            }
            return p;
        }

        double effectiveRatio = crop != null ? crop.ratio() : s.ratio();
        int h = heightOverride;
        if (h <= 0) {
            if (type == Type.YOUTUBE) h = 208;
            else if (type == Type.CARTOON) h = 216;
            else if (effectiveRatio <= 1.82) h = 216;
            else if (effectiveRatio <= 1.95) h = 208;
            else if (effectiveRatio <= 2.15) h = 192;
            else h = 184;
        }

        int pad = (240 - h) / 2;
        String prefix = crop == null ? "" : crop.toString() + ",";

        if (type == Type.YOUTUBE && h == 208) {
            p.filter = prefix
                    + "scale=-2:216:flags=lanczos,"
                    + "crop=320:208:x='(iw-ow)/2-32':y='(ih-oh)/2',"
                    + "pad=320:240:0:16:black";
        } else {
            String flags = type == Type.CARTOON ? "lanczos+accurate_rnd" : "lanczos";
            p.filter = prefix
                    + "scale=-2:" + h + ":flags=" + flags + ","
                    + "crop=320:" + h + ":x='(iw-ow)/2-32':y=0,"
                    + "pad=320:240:0:" + pad + ":black";
        }

        p.visibleHeight = h;
        String label = type == Type.YOUTUBE ? "YT" : type == Type.CARTOON ? "CARTOON" : "MOVIE";
        p.name = label + " " + h + " - " + p.outputFps;
        return p;
    }

    public static String buildCommand(String inputSaf, String outputSaf, Preset p,
                                      double trimStart, double trimEnd, boolean testClip) {
        StringBuilder c = new StringBuilder();
        c.append("-y ");
        if (trimStart > 0) c.append("-ss ").append(fmt(trimStart)).append(" ");
        c.append("-i ").append(q(inputSaf)).append(" ");
        if (testClip) {
            c.append("-t 30 ");
        } else if (trimEnd > trimStart && trimEnd > 0) {
            c.append("-t ").append(fmt(trimEnd - trimStart)).append(" ");
        }
        c.append("-vf ").append(q(p.filter)).append(" ");
        c.append("-map 0:v:0 -map 0:a:0? -sn ");
        c.append("-r ").append(p.outputFps).append(" ");
        c.append("-c:v amv ");
        if (p.cartoonQ1) c.append("-q:v 1 -qmin 1 ");
        c.append("-c:a adpcm_ima_amv -ac 1 -ar 22050 ");
        c.append("-block_size ").append(p.blockSize).append(" ");
        c.append(q(outputSaf));
        return c.toString();
    }

    private static String fmt(double x) {
        return String.format(java.util.Locale.US, "%.3f", x);
    }

    private static String q(String s) {
        return "\"" + s.replace("\"", "\\\"") + "\"";
    }
}
