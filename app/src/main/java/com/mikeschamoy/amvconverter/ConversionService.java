package com.mikeschamoy.amvconverter;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegKitConfig;
import com.arthenica.ffmpegkit.ReturnCode;

public class ConversionService extends Service {
    public static final String EXTRA_INPUT = "input";
    public static final String EXTRA_OUTPUT = "output";
    public static final String EXTRA_FILTER = "filter";
    public static final String EXTRA_FPS = "fps";
    public static final String EXTRA_BLOCK = "block";
    public static final String EXTRA_Q1 = "q1";
    public static final String EXTRA_DURATION_MS = "durationMs";

    private static final String ACTION_CANCEL = "com.mikeschamoy.amvconverter.CANCEL";
    private static final String CHANNEL = "conversion";
    private static final int NOTIFY_ID = 100;
    private long currentSessionId = -1L;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        if (ACTION_CANCEL.equals(intent.getAction())) {
            if (currentSessionId > 0) FFmpegKit.cancel(currentSessionId);
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        startInForeground(0, "Preparing conversion…");

        Uri input = Uri.parse(intent.getStringExtra(EXTRA_INPUT));
        Uri output = Uri.parse(intent.getStringExtra(EXTRA_OUTPUT));
        String filter = intent.getStringExtra(EXTRA_FILTER);
        int fps = intent.getIntExtra(EXTRA_FPS, 25);
        int block = intent.getIntExtra(EXTRA_BLOCK, 882);
        boolean q1 = intent.getBooleanExtra(EXTRA_Q1, false);
        long durationMs = intent.getLongExtra(EXTRA_DURATION_MS, 0L);

        String inputSaf = FFmpegKitConfig.getSafParameterForRead(this, input);
        String outputSaf = FFmpegKitConfig.getSafParameterForWrite(this, output);

        PresetEngine.Preset p = new PresetEngine.Preset();
        p.filter = filter;
        p.outputFps = fps;
        p.blockSize = block;
        p.cartoonQ1 = q1;

        String command = PresetEngine.buildCommand(inputSaf, outputSaf, p);

        var session = FFmpegKit.executeAsync(command, completed -> {
            if (ReturnCode.isSuccess(completed.getReturnCode())) {
                updateNotification(100, "Finished");
            } else if (ReturnCode.isCancel(completed.getReturnCode())) {
                updateNotification(0, "Cancelled");
            } else {
                updateNotification(0, "Conversion failed");
            }
            stopForeground(false);
            stopSelf();
        }, log -> {
            // Keep logs available to FFmpegKit session history; no UI spam.
        }, stats -> {
            long t = stats.getTime();
            int pct = durationMs > 0 ? (int) Math.min(99, (t * 100L) / durationMs) : 0;
            String text = pct > 0 ? "Converting… " + pct + "%" : "Converting…";
            updateNotification(pct, text);
        });

        currentSessionId = session.getSessionId();
        return START_NOT_STICKY;
    }

    private void startInForeground(int progress, String text) {
        Notification n = notification(progress, text);
        if (Build.VERSION.SDK_INT >= 35) {
            startForeground(NOTIFY_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING);
        } else {
            startForeground(NOTIFY_ID, n);
        }
    }

    private void updateNotification(int progress, String text) {
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                .notify(NOTIFY_ID, notification(progress, text));
    }

    private Notification notification(int progress, String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(
                this, 1, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent cancel = new Intent(this, ConversionService.class);
        cancel.setAction(ACTION_CANCEL);
        PendingIntent cancelPi = PendingIntent.getService(
                this, 2, cancel, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);

        b.setContentTitle("AMV Converter")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentIntent(openPi)
                .setOngoing(progress < 100 && !"Conversion failed".equals(text))
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelPi).build());

        if (progress > 0 && progress < 100) b.setProgress(100, progress, false);
        else if (progress == 0 && text.startsWith("Convert")) b.setProgress(0, 0, true);

        return b.build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(
                    CHANNEL, "AMV conversions", NotificationManager.IMPORTANCE_LOW);
            c.setDescription("Background AMV conversion progress");
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(c);
        }
    }

    @Override
    public void onTimeout(int startId, int fgsType) {
        if (Build.VERSION.SDK_INT >= 35) {
            if (currentSessionId > 0) FFmpegKit.cancel(currentSessionId);
            stopForeground(true);
            stopSelf();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
