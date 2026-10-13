package com.mikeschamoy.amvconverter;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;

import com.arthenica.ffmpegkit.FFmpegKitConfig;
import com.arthenica.ffmpegkit.FFprobeKit;

import java.util.Locale;

public class MainActivity extends Activity {
    private static final int PICK_INPUT = 1001;
    private static final int PICK_OUTPUT = 1002;

    private Uri inputUri;
    private PresetEngine.SourceInfo sourceInfo;

    private TextView fileText;
    private TextView sourceText;
    private TextView presetText;
    private TextView statusText;
    private Spinner typeSpinner;
    private Spinner heightSpinner;
    private Button convertButton;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);

        fileText = findViewById(R.id.fileText);
        sourceText = findViewById(R.id.sourceText);
        presetText = findViewById(R.id.presetText);
        statusText = findViewById(R.id.statusText);
        typeSpinner = findViewById(R.id.typeSpinner);
        heightSpinner = findViewById(R.id.heightSpinner);
        convertButton = findViewById(R.id.convertButton);

        findViewById(R.id.selectButton).setOnClickListener(v -> chooseInput());
        convertButton.setOnClickListener(v -> chooseOutput());

        View.OnFocusChangeListener update = (v, hasFocus) -> {
            if (!hasFocus) refreshPreset();
        };
        typeSpinner.setOnItemSelectedListener(new SimpleItemSelectedListener(this::refreshPreset));
        heightSpinner.setOnItemSelectedListener(new SimpleItemSelectedListener(this::refreshPreset));

        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 77);
        }
    }

    private void chooseInput() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("video/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "video/mp4", "video/x-matroska", "video/x-msvideo",
                "video/quicktime", "video/webm", "application/octet-stream"
        });
        startActivityForResult(i, PICK_INPUT);
    }

    private void chooseOutput() {
        if (inputUri == null || sourceInfo == null) return;

        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/octet-stream");
        String base = displayName(inputUri).replaceFirst("\\.[^.]+$", "");
        i.putExtra(Intent.EXTRA_TITLE, base + ".amv");
        startActivityForResult(i, PICK_OUTPUT);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;

        Uri uri = data.getData();
        int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try { getContentResolver().takePersistableUriPermission(uri, flags); } catch (Exception ignored) {}

        if (request == PICK_INPUT) {
            inputUri = uri;
            fileText.setText(displayName(uri));
            inspect(uri);
        } else if (request == PICK_OUTPUT) {
            startConversion(uri);
        }
    }

    private void inspect(Uri uri) {
        convertButton.setEnabled(false);
        statusText.setText("Inspecting source…");
        sourceText.setText("SOURCE\nChecking…");

        String saf = FFmpegKitConfig.getSafParameterForRead(this, uri);
        String cmd = "-v error -select_streams v:0 "
                + "-show_entries stream=width,height,avg_frame_rate "
                + "-show_entries format=duration "
                + "-of default=noprint_wrappers=1 " + quote(saf);

        FFprobeKit.executeAsync(cmd, session -> runOnUiThread(() -> {
            try {
                String out = session.getOutput();
                PresetEngine.SourceInfo s = parseProbe(out);
                sourceInfo = s;
                sourceText.setText(String.format(Locale.US,
                        "SOURCE\n%d×%d\n%.3f fps\n%.2f:1\n%s",
                        s.width, s.height, s.fps, s.ratio(), formatDuration(s.durationSeconds)));
                statusText.setText("Ready.");
                convertButton.setEnabled(true);
                refreshPreset();
            } catch (Exception e) {
                statusText.setText("Could not inspect this file.");
                sourceText.setText("SOURCE\nInspection failed");
            }
        }));
    }

    private PresetEngine.SourceInfo parseProbe(String out) {
        PresetEngine.SourceInfo s = new PresetEngine.SourceInfo();
        for (String raw : out.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.startsWith("width=")) s.width = Integer.parseInt(line.substring(6));
            else if (line.startsWith("height=")) s.height = Integer.parseInt(line.substring(7));
            else if (line.startsWith("avg_frame_rate=")) s.fps = rational(line.substring(15));
            else if (line.startsWith("duration=")) s.durationSeconds = Double.parseDouble(line.substring(9));
        }
        if (s.width <= 0 || s.height <= 0) throw new IllegalArgumentException("No video");
        if (s.fps <= 0) s.fps = 25.0;
        return s;
    }

    private double rational(String x) {
        if (x.contains("/")) {
            String[] p = x.split("/");
            double a = Double.parseDouble(p[0]);
            double b = Double.parseDouble(p[1]);
            return b == 0 ? 0 : a / b;
        }
        return Double.parseDouble(x);
    }

    private void refreshPreset() {
        if (sourceInfo == null) return;
        PresetEngine.Preset p = currentPreset();
        presetText.setText("PRESET\n" + p.name + "\n" + p.filter);
    }

    private PresetEngine.Preset currentPreset() {
        PresetEngine.Type type;
        switch (typeSpinner.getSelectedItemPosition()) {
            case 1: type = PresetEngine.Type.MOVIE_SHOW; break;
            case 2: type = PresetEngine.Type.CARTOON; break;
            default: type = PresetEngine.Type.YOUTUBE;
        }
        return PresetEngine.choose(sourceInfo, type, String.valueOf(heightSpinner.getSelectedItem()));
    }

    private void startConversion(Uri outputUri) {
        PresetEngine.Preset p = currentPreset();

        Intent svc = new Intent(this, ConversionService.class);
        svc.putExtra(ConversionService.EXTRA_INPUT, inputUri.toString());
        svc.putExtra(ConversionService.EXTRA_OUTPUT, outputUri.toString());
        svc.putExtra(ConversionService.EXTRA_FILTER, p.filter);
        svc.putExtra(ConversionService.EXTRA_FPS, p.outputFps);
        svc.putExtra(ConversionService.EXTRA_BLOCK, p.blockSize);
        svc.putExtra(ConversionService.EXTRA_Q1, p.cartoonQ1);
        svc.putExtra(ConversionService.EXTRA_DURATION_MS, (long) (sourceInfo.durationSeconds * 1000.0));

        if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
        else startService(svc);

        statusText.setText("Conversion started. You can leave the app; watch the notification.");
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {}
        return "video";
    }

    private static String quote(String x) {
        return "\"" + x.replace("\"", "\\\"") + "\"";
    }

    private String formatDuration(double s) {
        long sec = (long) s;
        long h = sec / 3600;
        long m = (sec % 3600) / 60;
        long x = sec % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, x)
                : String.format(Locale.US, "%d:%02d", m, x);
    }
}
