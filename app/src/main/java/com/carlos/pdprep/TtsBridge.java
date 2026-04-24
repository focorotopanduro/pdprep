package com.carlos.pdprep;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import java.util.Locale;
import java.util.UUID;

/**
 * JS -> native Android TextToSpeech bridge.
 * - Wraps TextToSpeech with audio focus (ducks Zoom/Teams audio while speaking).
 * - Supports per-utterance rate.
 * - Fires window._pdTtsOnEnd(id) + window._pdTtsOnProgress(id, pct) into the WebView so the JS
 *   button shows a progress bar.
 * - Cross-instance compatible: Context and WebView can live separately (e.g. service preloads
 *   the engine before any WebView exists).
 */
public class TtsBridge {

    private final Context appCtx;
    private WebView webView;
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextToSpeech tts;
    private volatile boolean ready = false;
    private volatile String activeId = null;
    private volatile int activeLen = 0;

    // Audio focus
    private AudioManager am;
    private AudioFocusRequest afr; // 26+
    private final Object focusLock = new Object();
    private boolean hasFocus = false;

    public TtsBridge(Context ctx, WebView wv) {
        this.appCtx = ctx.getApplicationContext();
        this.webView = wv;
        this.am = (AudioManager) appCtx.getSystemService(Context.AUDIO_SERVICE);
        initTts();
    }

    /** Allow later attachment (service preloads TTS before WebView exists). */
    public void attachWebView(WebView wv) { this.webView = wv; }

    private void initTts() {
        tts = new TextToSpeech(appCtx, new TextToSpeech.OnInitListener() {
            @Override public void onInit(int status) {
                if (status != TextToSpeech.SUCCESS) { ready = false; return; }
                try {
                    int r = tts.setLanguage(Locale.US);
                    if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                        tts.setLanguage(Locale.ENGLISH);
                    }
                    // default; overridable per-utterance
                    tts.setSpeechRate(1.0f);
                    tts.setPitch(1.0f);

                    // route through media stream so it obeys the phone's media volume + ducks others
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        AudioAttributes aa = new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build();
                        tts.setAudioAttributes(aa);
                    }

                    tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                        @Override public void onStart(String utteranceId) {
                            requestFocus();
                            activeId = utteranceId;
                        }
                        @Override public void onRangeStart(String utteranceId, int start, int end, int frame) {
                            int len = activeLen;
                            if (len > 0) {
                                int pct = (int) Math.min(100, Math.max(0, (end * 100L) / len));
                                fireProgress(utteranceId, pct);
                            }
                        }
                        @Override public void onDone(String utteranceId) { fireEnd(utteranceId); releaseFocus(); }
                        @Override public void onError(String utteranceId) { fireEnd(utteranceId); releaseFocus(); }
                        @Override public void onError(String utteranceId, int errorCode) { fireEnd(utteranceId); releaseFocus(); }
                        @Override public void onStop(String utteranceId, boolean interrupted) { fireEnd(utteranceId); releaseFocus(); }
                    });
                    ready = true;
                } catch (Exception e) {
                    ready = false;
                }
            }
        });
    }

    // ---- focus ----

    private final AudioManager.OnAudioFocusChangeListener focusListener =
            new AudioManager.OnAudioFocusChangeListener() {
                @Override public void onAudioFocusChange(int f) {
                    if (f == AudioManager.AUDIOFOCUS_LOSS || f == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                        main.post(new Runnable() { @Override public void run() { stopInternal(); } });
                    }
                }
            };

    private void requestFocus() {
        synchronized (focusLock) {
            if (hasFocus || am == null) return;
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    AudioAttributes aa = new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build();
                    afr = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                            .setAudioAttributes(aa)
                            .setOnAudioFocusChangeListener(focusListener, main)
                            .setWillPauseWhenDucked(false)
                            .build();
                    int res = am.requestAudioFocus(afr);
                    hasFocus = (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
                } else {
                    int res = am.requestAudioFocus(focusListener,
                            AudioManager.STREAM_MUSIC,
                            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
                    hasFocus = (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
                }
            } catch (Exception ignored) {}
        }
    }

    private void releaseFocus() {
        synchronized (focusLock) {
            if (!hasFocus || am == null) return;
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && afr != null) {
                    am.abandonAudioFocusRequest(afr);
                } else {
                    am.abandonAudioFocus(focusListener);
                }
            } catch (Exception ignored) {}
            hasFocus = false;
        }
    }

    // ---- JS callbacks ----

    private void fireEnd(final String id) {
        main.post(new Runnable() {
            @Override public void run() {
                if (webView == null) return;
                try {
                    String safeId = (id == null) ? "" : id.replace("\\", "\\\\").replace("'", "\\'");
                    webView.evaluateJavascript(
                            "if(window._pdTtsOnEnd){window._pdTtsOnEnd('" + safeId + "');}",
                            null);
                } catch (Exception ignored) {}
            }
        });
    }

    private void fireProgress(final String id, final int pct) {
        main.post(new Runnable() {
            @Override public void run() {
                if (webView == null) return;
                try {
                    String safeId = (id == null) ? "" : id.replace("\\", "\\\\").replace("'", "\\'");
                    webView.evaluateJavascript(
                            "if(window._pdTtsOnProgress){window._pdTtsOnProgress('" + safeId + "'," + pct + ");}",
                            null);
                } catch (Exception ignored) {}
            }
        });
    }

    // ---- JS-callable ----

    @JavascriptInterface
    public boolean isReady() { return ready; }

    /** speak with default rate */
    @JavascriptInterface
    public String speak(String text) { return speakAtRate(text, 1.0f); }

    /** speak at specific rate (0.5 .. 2.0) */
    @JavascriptInterface
    public String speakAtRate(final String text, final double rate) {
        if (!ready || tts == null || text == null) return "";
        final String id = UUID.randomUUID().toString();
        activeLen = text.length();
        main.post(new Runnable() {
            @Override public void run() {
                try {
                    tts.stop();
                    float r = (float) Math.max(0.5, Math.min(2.0, rate));
                    tts.setSpeechRate(r);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id);
                    } else {
                        Bundle params = new Bundle();
                        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id);
                        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null);
                    }
                } catch (Exception ignored) {}
            }
        });
        return id;
    }

    @JavascriptInterface
    public void stop() {
        main.post(new Runnable() {
            @Override public void run() { stopInternal(); }
        });
    }

    private void stopInternal() {
        try { if (tts != null) tts.stop(); } catch (Exception ignored) {}
        releaseFocus();
    }

    // ---- lifecycle ----

    public void release() {
        ready = false;
        main.post(new Runnable() {
            @Override public void run() {
                try {
                    if (tts != null) { tts.stop(); tts.shutdown(); }
                } catch (Exception ignored) {}
                tts = null;
                releaseFocus();
            }
        });
    }
}
