package com.mikeschamoy.amvconverter;

public final class PresetEngine {
    public enum Type { YOUTUBE, MOVIE_SHOW, CARTOON }

    public static final class SourceInfo {
        public int width;
        public int height;
        public double fps;
        public double durationSeconds;

        public double ratio() {
            return height == 0 ? 16.0 / 9.0 : (double) width / (double) height;
        }

        public boolean isFourThree() {
            return Math.abs(ratio() - (4.0 / 3.0)) < 0.035;
        }
    }

    public static final class Preset {
        public String name;
        public String filter;
        public int outputFps;
        public int blockSize;
        public boolean cartoonQ1;
    }

    private PresetEngine() {}

    public static Preset choose(SourceInfo s, Type type, String override) {
        Preset p = new Preset();
        p.outputFps = s.fps >= 27.0 ? 30 : 25;
        p.blockSize = p.outputFps == 30 ? 735 : 882;
        p.cartoonQ1 = type == Type.CARTOON;

        if ("4:3 Full 320x240".equals(override) || ("Auto".equals(override) && s.isFourThree())) {
            p.name = "4:3 FULL - " + p.outputFps;
            p.filter = "scale=320:240:flags=lanczos,setsar=1";
            p.cartoonQ1 = false;
            return p;
        }

        if ("4:3 Preserve 288x216".equals(override)) {
            p.name = "4:3 PRESERVE - " + p.outputFps;
            p.filter = "scale=288:216:flags=lanczos,pad=320:240:32:12:black,setsar=1";
            p.cartoonQ1 = false;
            return p;
        }

        int h;
        if (!"Auto".equals(override)) {
            try { h = Integer.parseInt(override); }
            catch (Exception e) { h = 208; }
        } else if (type == Type.YOUTUBE) {
            h = 208;
        } else if (type == Type.CARTOON) {
            h = 216;
        } else {
            double r = s.ratio();
            if (r <= 1.82) h = 216;
            else if (r <= 1.95) h = 208;
            else if (r <= 2.15) h = 192;
            else h = 184;
        }

        int pad = (240 - h) / 2;

        if (type == Type.YOUTUBE && h == 208) {
            p.filter = "scale=-2:216:flags=lanczos,crop=320:208:x='(iw-ow)/2-32':y='(ih-oh)/2',pad=320:240:0:16:black";
        } else {
            String flags = type == Type.CARTOON ? "lanczos+accurate_rnd" : "lanczos";
            p.filter = "scale=-2:" + h + ":flags=" + flags
                    + ",crop=320:" + h + ":x='(iw-ow)/2-32':y=0"
                    + ",pad=320:240:0:" + pad + ":black";
        }

        String label = type == Type.YOUTUBE ? "YT" : type == Type.CARTOON ? "CARTOON" : "MOVIE";
        p.name = label + " " + h + " - " + p.outputFps;
        return p;
    }

    public static String buildCommand(String inputSaf, String outputSaf, Preset p) {
        StringBuilder c = new StringBuilder();
        c.append("-y ");
        c.append("-i ").append(q(inputSaf)).append(" ");
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

    private static String q(String value) {
        return "\"" + value.replace("\"", "\\\"") + "\"";
    }
}
