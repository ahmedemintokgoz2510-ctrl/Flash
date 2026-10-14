package com.example.flashsync;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

public class SyncService extends Service {
    static final String ACTION_STOP = "stop";
    static volatile boolean running = false;

    private MediaProjection projection;
    private AudioRecord rec;
    private Thread worker;
    private volatile boolean active;
    private CameraManager cam;
    private String camId;
    private int maxLevel = 1;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        if (running) return START_NOT_STICKY;

        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("sync", "FlashSync", NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, "sync")
            .setContentTitle("FlashSync aktif")
            .setContentText("Fener sesin ritmiyle yanıp sönüyor")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true).build();
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);

        try {
            cam = (CameraManager) getSystemService(CAMERA_SERVICE);
            for (String id : cam.getCameraIdList()) {
                CameraCharacteristics c = cam.getCameraCharacteristics(id);
                Boolean f = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (Boolean.TRUE.equals(f) && facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    camId = id;
                    Integer mx = c.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL);
                    if (mx != null) maxLevel = mx;
                    break;
                }
            }
            MediaProjectionManager m = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = m.getMediaProjection(intent.getIntExtra("code", 0), intent.getParcelableExtra("data"));
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { stopSelf(); }
            }, new Handler(Looper.getMainLooper()));

            AudioPlaybackCaptureConfiguration cfg = new AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build();
            AudioFormat fmt = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(44100)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build();
            rec = new AudioRecord.Builder().setAudioFormat(fmt)
                .setBufferSizeInBytes(16384).setAudioPlaybackCaptureConfig(cfg).build();
            rec.startRecording();
        } catch (Exception e) { stopSelf(); return START_NOT_STICKY; }

        running = true;
        active = true;
        worker = new Thread(this::loop, "beat");
        worker.start();
        return START_NOT_STICKY;
    }

    private void loop() {
        short[] buf = new short[1024];
        double[] hist = new double[43];
        int hi = 0;
        double sum = 0, lp = 0;
        long lastBeat = 0, onAt = 0;
        boolean on = false;
        while (active) {
            int n = rec.read(buf, 0, buf.length);
            if (n <= 0) continue;
            double e = 0;
            for (int i = 0; i < n; i++) { lp += 0.03 * (buf[i] - lp); e += lp * lp; }
            e /= n;
            double avg = sum / hist.length;
            int sens = getSharedPreferences("cfg", MODE_PRIVATE).getInt("sens", 85);
            double thr = 1.1 + (100 - sens) / 100.0 * 1.9;
            long now = SystemClock.elapsedRealtime();
            if (e > avg * thr && e > 2e4 && now - lastBeat > 150) {
                double ratio = avg > 0 ? e / avg : 4;
                double frac = Math.max(0, Math.min(1, (ratio - thr) / 2.6));
                torchOn(frac); on = true; onAt = now; lastBeat = now;
            } else if (on && now - onAt > 90) {
                torch(false); on = false;
            }
            sum += e - hist[hi]; hist[hi] = e; hi = (hi + 1) % hist.length;
        }
    }

    private void torchOn(double frac) {
        try {
            if (camId == null) return;
            if (android.os.Build.VERSION.SDK_INT >= 33 && maxLevel > 1) {
                int level = 1 + (int) Math.round(frac * (maxLevel - 1));
                cam.turnOnTorchWithStrengthLevel(camId, level);
            } else cam.setTorchMode(camId, true);
        } catch (Exception ignored) {}
    }

    private void torch(boolean v) {
        try { if (camId != null) cam.setTorchMode(camId, v); } catch (Exception ignored) {}
    }

    @Override public void onDestroy() {
        active = false;
        running = false;
        try { if (worker != null) worker.join(500); } catch (Exception ignored) {}
        torch(false);
        try { if (rec != null) { rec.stop(); rec.release(); } } catch (Exception ignored) {}
        try { if (projection != null) projection.stop(); } catch (Exception ignored) {}
        super.onDestroy();
    }
}
