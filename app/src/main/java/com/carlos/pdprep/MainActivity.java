package com.carlos.pdprep;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final int REQ_OVERLAY = 1001;
    private static final int REQ_NOTIF   = 1002;

    private WebView webView;
    private Button btnStart, btnStop;
    private TextView status;
    private TtsBridge tts;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_main);

        status = findViewById(R.id.status);
        btnStart = findViewById(R.id.btnStart);
        btnStop = findViewById(R.id.btnStop);
        webView = findViewById(R.id.webview);

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(false);
        webView.setBackgroundColor(Color.parseColor("#0A0C10"));
        tts = new TtsBridge(this, webView);
        webView.addJavascriptInterface(tts, "NativeTTS");
        webView.loadUrl("file:///android_asset/app.html");

        btnStart.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startOverlayFlow(); }
        });
        btnStop.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { stopOverlay(); }
        });

        requestNotifPermissionIfNeeded();
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
        refreshStatus();
    }

    @Override
    protected void onPause() {
        if (webView != null) webView.onPause();
        super.onPause();
    }

    private boolean hasOverlayPerm() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(this);
        }
        return true;
    }

    private void refreshStatus() {
        if (!hasOverlayPerm()) {
            status.setText("Step 1: Grant 'Display over other apps' permission.");
            btnStart.setText("Grant Permission");
        } else {
            status.setText("Step 2: Start the floating bubble.\nDrag it anywhere. Long-press to hide it.");
            btnStart.setText("Start Floating Bubble");
        }
    }

    private void requestNotifPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            }
        }
    }

    private void startOverlayFlow() {
        if (!hasOverlayPerm()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                try { startActivityForResult(i, REQ_OVERLAY); }
                catch (Exception e) {
                    Toast.makeText(this,
                            "Open Settings manually and allow 'Draw over other apps' for PD Prep",
                            Toast.LENGTH_LONG).show();
                }
            }
            return;
        }
        Intent svc = new Intent(this, OverlayService.class).setAction(OverlayService.ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc);
            else startService(svc);
            Toast.makeText(this,
                    "Bubble running. Open Zoom/Teams — bubble stays on top.",
                    Toast.LENGTH_LONG).show();
            moveTaskToBack(true);
        } catch (Exception e) {
            Toast.makeText(this, "Could not start overlay: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void stopOverlay() {
        Intent svc = new Intent(this, OverlayService.class).setAction(OverlayService.ACTION_STOP);
        try { stopService(svc); } catch (Exception ignored) {}
        Toast.makeText(this, "Bubble stopped.", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent d) {
        super.onActivityResult(req, res, d);
        if (req == REQ_OVERLAY) refreshStatus();
    }

    @Override
    protected void onDestroy() {
        if (tts != null) { try { tts.release(); } catch (Exception ignored) {} tts = null; }
        if (webView != null) {
            try {
                webView.stopLoading();
                webView.loadUrl("about:blank");
                webView.removeAllViews();
                webView.destroy();
            } catch (Exception ignored) {}
            webView = null;
        }
        super.onDestroy();
    }
}
