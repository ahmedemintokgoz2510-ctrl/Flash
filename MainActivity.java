package com.example.flashsync;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.content.SharedPreferences;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int REQ_PERM = 1, REQ_PROJ = 2;
    private Button btn;
    private TextView info;

    @Override protected void onCreate(android.os.Bundle b) {
        super.onCreate(b);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER);
        l.setPadding(48, 48, 48, 48);
        info = new TextView(this);
        info.setTextSize(16);
        info.setGravity(Gravity.CENTER);
        btn = new Button(this);
        l.addView(info);
        l.addView(btn);
        SharedPreferences sp = getSharedPreferences("cfg", MODE_PRIVATE);
        TextView lbl = new TextView(this);
        lbl.setTextSize(16);
        lbl.setPadding(0, 64, 0, 8);
        SeekBar sb = new SeekBar(this);
        sb.setMax(100);
        int cur = sp.getInt("sens", 85);
        sb.setProgress(cur);
        lbl.setText("Hassasiyet: " + cur + "  (yüksek = daha sık yanar)");
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                sp.edit().putInt("sens", p).apply();
                lbl.setText("Hassasiyet: " + p + "  (yüksek = daha sık yanar)");
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        l.addView(lbl);
        l.addView(sb);
        setContentView(l);
        btn.setOnClickListener(v -> {
            if (SyncService.running) {
                startService(new Intent(this, SyncService.class).setAction(SyncService.ACTION_STOP));
                btn.postDelayed(this::refresh, 300);
            } else askPermissions();
        });
        refresh();
    }

    @Override protected void onResume() { super.onResume(); refresh(); }

    private void refresh() {
        btn.setText(SyncService.running ? "Durdur" : "Başlat");
        info.setText(SyncService.running
            ? "Aktif: Fener çalan sesin ritmine göre yanıp sönüyor."
            : "Başlat'a bas, izinleri ver, sonra istediğin uygulamada müzik/video aç.");
    }

    private void askPermissions() {
        java.util.ArrayList<String> need = new java.util.ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        if (need.isEmpty()) askProjection();
        else requestPermissions(need.toArray(new String[0]), REQ_PERM);
    }

    @Override public void onRequestPermissionsResult(int c, String[] p, int[] r) {
        if (c == REQ_PERM) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) askProjection();
            else info.setText("Ses yakalama için mikrofon izni gerekli.");
        }
    }

    private void askProjection() {
        MediaProjectionManager m = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(m.createScreenCaptureIntent(), REQ_PROJ);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PROJ && res == RESULT_OK) {
            Intent i = new Intent(this, SyncService.class)
                .putExtra("code", res).putExtra("data", data);
            startForegroundService(i);
            btn.postDelayed(this::refresh, 500);
        }
    }
}
