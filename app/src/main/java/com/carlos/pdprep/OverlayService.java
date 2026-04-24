package com.carlos.pdprep;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Display;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.ImageView;

public class OverlayService extends Service {

    public static final String ACTION_START = "pdprep.START";
    public static final String ACTION_STOP  = "pdprep.STOP";
    private static final String CHANNEL_ID  = "pdprep_overlay";
    private static final int NOTIF_ID       = 42;

    private WindowManager wm;
    private int touchSlop;

    private View bubbleView;
    private WindowManager.LayoutParams bubbleParams;

    private View panelView;
    private WebView webView;
    private WindowManager.LayoutParams panelParams;
    private boolean panelShown = false;
    private boolean panelLoaded = false;

    // remembered panel position so it doesn't jump on re-open
    private int savedPanelX = Integer.MIN_VALUE, savedPanelY = Integer.MIN_VALUE;
    private float savedAlpha = 0.96f;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        startAsForeground();

        // if permission was revoked while we were gone, stop quietly
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            stopSelf();
            return;
        }
        addBubble();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // re-clamp and re-size on rotation
        Point size = screenSize();
        if (bubbleView != null && bubbleParams != null) {
            bubbleParams.x = Math.max(0, Math.min(bubbleParams.x, size.x - dp(56)));
            bubbleParams.y = Math.max(0, Math.min(bubbleParams.y, size.y - dp(80)));
            safeUpdate(bubbleView, bubbleParams);
        }
        if (panelView != null && panelParams != null && panelShown) {
            int[] wh = computePanelSize();
            panelParams.width = wh[0];
            panelParams.height = wh[1];
            panelParams.x = Math.max(0, Math.min(panelParams.x, size.x - panelParams.width));
            panelParams.y = Math.max(0, Math.min(panelParams.y, size.y - panelParams.height));
            safeUpdate(panelView, panelParams);
        }
    }

    @Override
    public void onDestroy() {
        removePanelFromWindow();
        if (webView != null) {
            try {
                webView.stopLoading();
                webView.loadUrl("about:blank");
                webView.clearHistory();
                webView.removeAllViews();
                webView.destroy();
            } catch (Exception ignored) {}
            webView = null;
        }
        panelView = null;
        if (bubbleView != null) {
            safeRemove(bubbleView);
            bubbleView = null;
        }
        super.onDestroy();
    }

    // ---------------- helpers ----------------

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    private Point screenSize() {
        Point p = new Point();
        Display d = wm.getDefaultDisplay();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            d.getRealSize(p);
        } else {
            d.getSize(p);
        }
        return p;
    }

    private int overlayType() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    private void safeUpdate(View v, WindowManager.LayoutParams p) {
        try { wm.updateViewLayout(v, p); } catch (Exception ignored) {}
    }
    private void safeRemove(View v) {
        try { wm.removeView(v); } catch (Exception ignored) {}
    }

    // ---------------- notification (required for foreground) ----------------

    private void startAsForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Bubble",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ch.setSound(null, null);
            ch.enableVibration(false);
            nm.createNotificationChannel(ch);
        }
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) piFlags |= PendingIntent.FLAG_IMMUTABLE;

        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), piFlags);
        PendingIntent piStop = PendingIntent.getService(this, 1,
                new Intent(this, OverlayService.class).setAction(ACTION_STOP), piFlags);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setContentTitle("PD Prep bubble running")
         .setContentText("Tap the gold bubble on screen to open your cheat sheet.")
         .setSmallIcon(android.R.drawable.ic_menu_info_details)
         .setContentIntent(pi)
         .setOngoing(true);
        b.addAction(0, "Stop", piStop);

        try { startForeground(NOTIF_ID, b.build()); }
        catch (Exception e) { /* some OEM Androids throw here; keep service alive anyway */ }
    }

    // ---------------- bubble ----------------

    @SuppressLint("ClickableViewAccessibility")
    private void addBubble() {
        bubbleView = LayoutInflater.from(this).inflate(R.layout.overlay_bubble, null);
        bubbleParams = new WindowManager.LayoutParams(
                dp(56), dp(56),
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        bubbleParams.gravity = Gravity.TOP | Gravity.START;
        Point size = screenSize();
        bubbleParams.x = size.x - dp(72);
        bubbleParams.y = size.y / 3;

        setupBubbleTouch(bubbleView);
        try { wm.addView(bubbleView, bubbleParams); }
        catch (Exception e) { stopSelf(); }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupBubbleTouch(final View v) {
        v.setOnTouchListener(new View.OnTouchListener() {
            int initX, initY;
            float downX, downY;
            long downTime;
            boolean moved;
            boolean longFired;

            final long longPressMs = ViewConfiguration.getLongPressTimeout();
            Runnable longPressCheck = new Runnable() {
                @Override public void run() {
                    if (!moved && !longFired && bubbleView != null) {
                        longFired = true;
                        bubbleView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                        stopSelf();
                    }
                }
            };

            @Override
            public boolean onTouch(View view, MotionEvent ev) {
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        initX = bubbleParams.x;
                        initY = bubbleParams.y;
                        downX = ev.getRawX();
                        downY = ev.getRawY();
                        downTime = System.currentTimeMillis();
                        moved = false;
                        longFired = false;
                        view.postDelayed(longPressCheck, longPressMs);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (ev.getRawX() - downX);
                        int dy = (int) (ev.getRawY() - downY);
                        if (!moved && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                            moved = true;
                            view.removeCallbacks(longPressCheck);
                        }
                        if (moved) {
                            bubbleParams.x = initX + dx;
                            bubbleParams.y = initY + dy;
                            safeUpdate(bubbleView, bubbleParams);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        view.removeCallbacks(longPressCheck);
                        if (longFired) return true;
                        long dur = System.currentTimeMillis() - downTime;
                        if (!moved && dur < longPressMs) togglePanel();
                        else snapToEdge();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        view.removeCallbacks(longPressCheck);
                        if (moved) snapToEdge();
                        return true;
                }
                return false;
            }
        });
    }

    private void snapToEdge() {
        Point size = screenSize();
        int mid = size.x / 2;
        bubbleParams.x = (bubbleParams.x + dp(28) < mid) ? 0 : size.x - dp(56);
        if (bubbleParams.y < 0) bubbleParams.y = 0;
        if (bubbleParams.y > size.y - dp(80)) bubbleParams.y = size.y - dp(80);
        safeUpdate(bubbleView, bubbleParams);
    }

    // ---------------- panel (WebView cached across show/hide) ----------------

    private int[] computePanelSize() {
        Point size = screenSize();
        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        int w = landscape ? (int)(size.x * 0.55) : (int)(size.x * 0.92);
        int h = landscape ? (int)(size.y * 0.85) : (int)(size.y * 0.72);
        return new int[]{ w, h };
    }

    private void togglePanel() {
        if (panelShown) hidePanel();
        else showPanel();
    }

    @SuppressLint({"ClickableViewAccessibility", "SetJavaScriptEnabled"})
    private void ensurePanelCreated() {
        if (panelView != null) return;
        panelView = LayoutInflater.from(this).inflate(R.layout.overlay_panel, null);
        webView = panelView.findViewById(R.id.webview);

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        webView.setBackgroundColor(Color.TRANSPARENT);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        if (!panelLoaded) {
            webView.loadUrl("file:///android_asset/app.html");
            panelLoaded = true;
        }

        final View dragBar = panelView.findViewById(R.id.dragBar);
        final ImageView btnClose = (ImageView) panelView.findViewById(R.id.btnClose);
        final ImageView btnOpacity = (ImageView) panelView.findViewById(R.id.btnOpacity);

        dragBar.setOnTouchListener(new View.OnTouchListener() {
            int ix, iy; float dx, dy;
            @Override public boolean onTouch(View v, MotionEvent ev) {
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        ix = panelParams.x; iy = panelParams.y;
                        dx = ev.getRawX(); dy = ev.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        panelParams.x = ix + (int)(ev.getRawX() - dx);
                        panelParams.y = iy + (int)(ev.getRawY() - dy);
                        Point size = screenSize();
                        if (panelParams.x < -panelParams.width + dp(64)) panelParams.x = -panelParams.width + dp(64);
                        if (panelParams.x > size.x - dp(64)) panelParams.x = size.x - dp(64);
                        if (panelParams.y < 0) panelParams.y = 0;
                        if (panelParams.y > size.y - dp(64)) panelParams.y = size.y - dp(64);
                        savedPanelX = panelParams.x; savedPanelY = panelParams.y;
                        safeUpdate(panelView, panelParams);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        return true;
                }
                return false;
            }
        });

        btnClose.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { hidePanel(); }
        });

        btnOpacity.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                float a = panelParams.alpha;
                float next = (a <= 0.55f) ? 0.96f : (a <= 0.75f) ? 0.55f : 0.75f;
                panelParams.alpha = next;
                savedAlpha = next;
                safeUpdate(panelView, panelParams);
            }
        });
    }

    private void showPanel() {
        if (panelShown) return;
        ensurePanelCreated();

        int[] wh = computePanelSize();
        panelParams = new WindowManager.LayoutParams(
                wh[0], wh[1],
                overlayType(),
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        panelParams.gravity = Gravity.TOP | Gravity.START;

        Point size = screenSize();
        if (savedPanelX == Integer.MIN_VALUE) {
            panelParams.x = Math.max(dp(8), (size.x - wh[0]) / 2);
            panelParams.y = dp(48);
        } else {
            panelParams.x = Math.max(0, Math.min(savedPanelX, size.x - wh[0]));
            panelParams.y = Math.max(0, Math.min(savedPanelY, size.y - wh[1]));
        }
        panelParams.alpha = savedAlpha;

        try {
            wm.addView(panelView, panelParams);
            panelShown = true;
            if (bubbleView != null) bubbleView.setVisibility(View.GONE);
            if (webView != null) webView.onResume();
        } catch (Exception e) {
            panelShown = false;
        }
    }

    private void removePanelFromWindow() {
        if (panelView != null && panelShown) safeRemove(panelView);
        panelShown = false;
    }

    private void hidePanel() {
        removePanelFromWindow();
        if (webView != null) {
            try { webView.onPause(); } catch (Exception ignored) {}
        }
        if (bubbleView != null) bubbleView.setVisibility(View.VISIBLE);
    }
}
