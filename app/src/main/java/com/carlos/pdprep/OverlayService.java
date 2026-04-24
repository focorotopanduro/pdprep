package com.carlos.pdprep;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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

    public static final String ACTION_START      = "pdprep.START";
    public static final String ACTION_STOP       = "pdprep.STOP";
    public static final String ACTION_OPEN_PANEL = "pdprep.OPEN_PANEL";
    private static final String CHANNEL_ID       = "pdprep_overlay";
    private static final int NOTIF_ID            = 42;
    private static final String PREFS            = "pdprep_prefs";
    private static final String K_BUBBLE_X       = "bubble_x";
    private static final String K_BUBBLE_Y       = "bubble_y";
    private static final String K_PANEL_X        = "panel_x";
    private static final String K_PANEL_Y        = "panel_y";
    private static final String K_PANEL_ALPHA    = "panel_alpha";

    private WindowManager wm;
    private int touchSlop;

    private View bubbleView;
    private WindowManager.LayoutParams bubbleParams;

    private View panelView;
    private WebView webView;
    private TtsBridge tts;
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
        // preload panel off-screen so first tap is instant
        main.postDelayed(new Runnable() {
            @Override public void run() {
                try { ensurePanelCreated(); } catch (Exception ignored) {}
            }
        }, 300);
    }

    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String a = intent.getAction();
            if (ACTION_STOP.equals(a)) { stopSelf(); return START_NOT_STICKY; }
            if (ACTION_OPEN_PANEL.equals(a)) { showPanel(); }
        }
        return START_STICKY;
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
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
        hideDismiss();
        removePanelFromWindow();
        if (tts != null) { try { tts.release(); } catch (Exception ignored) {} tts = null; }
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
        PendingIntent piOpen = PendingIntent.getService(this, 2,
                new Intent(this, OverlayService.class).setAction(ACTION_OPEN_PANEL), piFlags);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setContentTitle("PD Prep bubble running")
         .setContentText("Tap Open Panel to summon the cheat sheet directly.")
         .setSmallIcon(android.R.drawable.ic_menu_info_details)
         .setContentIntent(pi)
         .setOngoing(true);
        b.addAction(0, "Open Panel", piOpen);
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
        SharedPreferences p = prefs();
        int savedX = p.getInt(K_BUBBLE_X, Integer.MIN_VALUE);
        int savedY = p.getInt(K_BUBBLE_Y, Integer.MIN_VALUE);
        if (savedX == Integer.MIN_VALUE) {
            bubbleParams.x = size.x - dp(72);
            bubbleParams.y = size.y / 3;
        } else {
            bubbleParams.x = Math.max(0, Math.min(savedX, size.x - dp(56)));
            bubbleParams.y = Math.max(0, Math.min(savedY, size.y - dp(80)));
        }
        int savedPX = p.getInt(K_PANEL_X, Integer.MIN_VALUE);
        int savedPY = p.getInt(K_PANEL_Y, Integer.MIN_VALUE);
        if (savedPX != Integer.MIN_VALUE) { savedPanelX = savedPX; savedPanelY = savedPY; }
        savedAlpha = p.getFloat(K_PANEL_ALPHA, 0.96f);

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
                            showDismiss();
                        }
                        if (moved) {
                            bubbleParams.x = initX + dx;
                            bubbleParams.y = initY + dy;
                            safeUpdate(bubbleView, bubbleParams);
                            updateDismissHighlight();
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        view.removeCallbacks(longPressCheck);
                        if (longFired) { hideDismiss(); return true; }
                        long dur = System.currentTimeMillis() - downTime;
                        if (!moved && dur < longPressMs) { hideDismiss(); togglePanel(); }
                        else if (bubbleOverDismiss()) {
                            bubbleView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                            hideDismiss();
                            stopSelf();
                        }
                        else { hideDismiss(); snapToEdge(); }
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        view.removeCallbacks(longPressCheck);
                        hideDismiss();
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
        prefs().edit().putInt(K_BUBBLE_X, bubbleParams.x).putInt(K_BUBBLE_Y, bubbleParams.y).apply();
    }

    // ---------------- dismiss-by-drag target ----------------

    private View dismissView;
    private android.widget.TextView dismissX;
    private WindowManager.LayoutParams dismissParams;
    private boolean dismissActive = false;

    private void showDismiss() {
        if (dismissView != null) return;
        dismissView = LayoutInflater.from(this).inflate(R.layout.overlay_dismiss, null);
        dismissX = dismissView.findViewById(R.id.dismissX);
        dismissParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        dismissParams.gravity = Gravity.TOP | Gravity.START;
        dismissParams.windowAnimations = android.R.style.Animation_Toast;
        try { wm.addView(dismissView, dismissParams); }
        catch (Exception ignored) { dismissView = null; }
    }

    private void hideDismiss() {
        if (dismissView != null) {
            try { wm.removeView(dismissView); } catch (Exception ignored) {}
            dismissView = null; dismissX = null; dismissActive = false;
        }
    }

    /** rough distance between bubble center and X center */
    private double bubbleToDismissDist() {
        if (dismissX == null || bubbleParams == null) return Double.MAX_VALUE;
        Point size = screenSize();
        double bx = bubbleParams.x + dp(28);
        double by = bubbleParams.y + dp(28);
        // X target center: bottom-center minus bottom margin minus half-size
        double xX = size.x / 2.0;
        double xY = size.y - dp(48 + 36); // bottom margin 48 + half-height 36
        double dxd = bx - xX, dyd = by - xY;
        return Math.sqrt(dxd*dxd + dyd*dyd);
    }

    private boolean bubbleOverDismiss() {
        return bubbleToDismissDist() < dp(60); // within 60dp of X center
    }

    private void updateDismissHighlight() {
        if (dismissX == null) return;
        boolean over = bubbleOverDismiss();
        if (over != dismissActive) {
            dismissActive = over;
            dismissX.setBackgroundResource(over ? R.drawable.dismiss_bg_active : R.drawable.dismiss_bg);
            if (over && bubbleView != null) {
                bubbleView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            }
        }
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
        tts = new TtsBridge(this, webView);
        webView.addJavascriptInterface(tts, "NativeTTS");
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
                        prefs().edit().putInt(K_PANEL_X, savedPanelX).putInt(K_PANEL_Y, savedPanelY).apply();
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
                // Cycle 3 CSS-based background transparency levels.
                // Text stays 100% opaque — only the background changes.
                int cur = prefs().getInt("trans_level", 0);
                int next = (cur + 1) % 3;
                prefs().edit().putInt("trans_level", next).apply();
                if (webView != null) {
                    try { webView.evaluateJavascript("window._pdSetTrans && window._pdSetTrans(" + next + ");", null); }
                    catch (Exception ignored) {}
                }
            }
        });

        // NEW: fullscreen toggle — tap drag-bar logo area to expand/restore
        final android.widget.TextView dragLogo = panelView.findViewById(R.id.dragLogo);
        if (dragLogo != null) {
            dragLogo.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { toggleFullscreen(); }
            });
        }
    }

    // ---- fullscreen ----
    private boolean fullscreen = false;
    private int[] prePanelSize, prePanelPos;
    private void toggleFullscreen() {
        if (!panelShown || panelParams == null) return;
        Point size = screenSize();
        if (!fullscreen) {
            prePanelSize = new int[]{panelParams.width, panelParams.height};
            prePanelPos  = new int[]{panelParams.x, panelParams.y};
            panelParams.width = size.x;
            panelParams.height = size.y;
            panelParams.x = 0;
            panelParams.y = 0;
            fullscreen = true;
        } else {
            if (prePanelSize != null) {
                panelParams.width = prePanelSize[0];
                panelParams.height = prePanelSize[1];
            }
            if (prePanelPos != null) {
                panelParams.x = prePanelPos[0];
                panelParams.y = prePanelPos[1];
            }
            fullscreen = false;
        }
        safeUpdate(panelView, panelParams);
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
        // Text legibility fix: never dim the whole window (that fades text too).
        // Transparency is now controlled by CSS on the WebView, so text stays 100% opaque.
        panelParams.alpha = 1.0f;

        try {
            wm.addView(panelView, panelParams);
            panelShown = true;
            if (bubbleView != null) bubbleView.setVisibility(View.GONE);
            if (webView != null) {
                webView.onResume();
                // push the last remembered transparency level into the WebView CSS
                final int level = prefs().getInt("trans_level", 0);
                webView.post(new Runnable() {
                    @Override public void run() {
                        try {
                            webView.evaluateJavascript(
                                    "window._pdSetTrans && window._pdSetTrans(" + level + ");", null);
                        } catch (Exception ignored) {}
                    }
                });
            }
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
