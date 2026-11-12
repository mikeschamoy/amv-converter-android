package com.mikeschamoy.amvconverter;
/** Standalone regression for full-length VFR / unusual-timestamp conversions. */
public final class DurationBoundRegression {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        PresetEngine.Preset p = new PresetEngine.Preset();
        p.filter = "scale=320:240,setsar=1";
        p.outputFps = 25;
        p.blockSize = 882;
        p.audioPresent = true;
        // Regression: a 50:48 movie averaging 24.417 FPS must not encode 52:44.
        String full = PresetEngine.buildVideoCommand(
            "in.mp4", "out.amv", p, 0, 0, false, 0, 3048.0);
        check(full.contains("-t 3048.000 "), "full-length output is unbounded");
        check(full.contains("-r 25 "), "output FPS changed");
        check(full.contains("-block_size 882 "), "AMV audio pairing changed");
        check(full.contains("-c:v amv ") && full.contains("-c:a adpcm_ima_amv "),
              "Gen 2 AMV codecs changed");
        check(full.indexOf("-t 3048.000") > full.indexOf("-i "),
              "duration cap must be an output option, after inputs");
        check(full.indexOf("-t 3048.000") < full.indexOf("-vf "),
              "duration cap placement changed");
        check(full.indexOf("-t 3048.000") == full.lastIndexOf("-t 3048.000"),
              "duplicate output duration cap");
        // The app's computed runtime is authoritative for trimmed and test clips.
        String trimmed = PresetEngine.buildVideoCommand(
            "in.mp4", "out.amv", p, 180, 400, false, 1, 120.0);
        check(trimmed.contains("-ss 180.000 ") && trimmed.contains("-t 120.000 "),
              "trim range was not capped to remaining valid runtime");
        check(!trimmed.contains("-t 220.000 "), "stale manual trim cap");
        String test = PresetEngine.buildVideoCommand(
            "in.mp4", "out.amv", p, 3040, 0, true, 0, 8.0);
        check(test.contains("-t 8.000 ") && !test.contains("-t 30 "),
              "test clip exceeds source remainder");
        // Legacy callers still get their original behavior until supplied runtime.
        String legacy = PresetEngine.buildVideoCommand(
            "in.mp4", "out.amv", p, 0, 0, false, 0);
        check(!legacy.contains("-t "), "legacy API behavior changed");
        String missing = PresetEngine.buildVideoCommand(
            "in.mp4", "out.amv", p, 0, 0, false, 0, Double.NaN);
        check(!missing.contains("-t "), "unknown duration created invalid cap");
        System.out.println("PASS: duration bound, unusual FPS, 25 FPS/882-byte blocks, "
            + "trim, short test, and backward compatibility");
    }
}
