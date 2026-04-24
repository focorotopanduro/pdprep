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
 * Bulletproof JS -> native Android TextToSpeech bridge.
 *
 * Hardening in this version:
 *  - Queues a speak request that arrives before the engine is initialized, then plays
 *    automatically once init completes (no lost first tap).
 *  - Retries init once if it fails (handles cold-boot TTS services that 500 on first ask).
 *  - Falls back from Locale.US -> Locale.ENGLISH -> device default locale.
 *  - Reports via callback whenever speak fails so the JS can show a toast.
 *  - Survives a missing voice-data bundle: speak() still returns an id so the UI keeps
 *    moving, but also fires an error callback telling the user what to install.
 */
public class TtsBridge {

    private final Context appCtx;
    private WebView webView;
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextToSpeech tts;
    private volatile int state = STATE_INIT;      // 0 init, 1 ready, 2 failed
    private volatile int activeLen = 0;
    private int initAttempts = 0;

    private static final int STATE_INIT = 0;
    private static final int STATE_READY = 1;
    private static final int STATE_FAILED = 2;

    // pending request queued while engine is still initializing
    private String pendingText = null;
    private float  pendingRate = 1.0f;
    private String pendingId   = null;

    // Audio focus
    private AudioManager am;
    private AudioFocusRequest afr;
    private final Object focusLock = new Object();
    private boolean hasFocus = false;

    public TtsBridge(Context ctx, WebView wv) {
        this.appCtx = ctx.getApplicationContext();
        this.webView = wv;
        this.am = (AudioManager) appCtx.getSystemService(Context.AUDIO_SERVICE);
        initTts();
    }

    public void attachWebView(WebView wv) { this.webView = wv; }

    private void initTts() {
        initAttempts++;
        state = STATE_INIT;
        try {
            tts = new TextToSpeech(appCtx, new TextToSpeech.OnInitListener() {
                @Override public void onInit(int status) {
                    if (status != TextToSpeech.SUCCESS) {
                        if (initAttempts < 2) {
                            main.postDelayed(new Runnable() { @Override public void run() { initTts(); } }, 800);
                        } else {
                            state = STATE_FAILED;
                            fireErrorIfPending("Couldn't start the phone's text-to-speech engine. Settings → General management → Text-to-speech output — pick Google TTS.");
                        }
                        return;
                    }
                    try {
                        int r = tts.setLanguage(Locale.US);
                        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                            r = tts.setLanguage(Locale.ENGLISH);
                        }
                        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                            r = tts.setLanguage(Locale.getDefault());
                        }
                        tts.setSpeechRate(1.0f);
                        tts.setPitch(1.0f);

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            AudioAttributes aa = new AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .build();
                            tts.setAudioAttributes(aa);
                        }

                        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                            @Override public void onStart(String utteranceId) { requestFocus(); }
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
                        state = STATE_READY;
                        // fire the queued tap, if any
                        if (pendingText != null) {
                            final String t = pendingText; final float r2 = pendingRate; final String id = pendingId;
                            pendingText = null; pendingId = null;
                            doSpeak(t, r2, id);
                        }
                    } catch (Exception e) {
                        state = STATE_FAILED;
                        fireErrorIfPending("Text-to-speech engine failed to configure. Try a reboot or pick a different TTS engine in Settings.");
                    }
                }
            });
        } catch (Exception e) {
            state = STATE_FAILED;
            fireErrorIfPending("No text-to-speech engine is installed on this phone. Install 'Google Text-to-Speech' from the Play Store.");
        }
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

    private void runJs(final String js) {
        main.post(new Runnable() {
            @Override public void run() {
                if (webView == null) return;
                try { webView.evaluateJavascript(js, null); } catch (Exception ignored) {}
            }
        });
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "");
    }

    private void fireEnd(String id) {
        runJs("if(window._pdTtsOnEnd){window._pdTtsOnEnd('" + esc(id) + "');}");
    }

    private void fireProgress(String id, int pct) {
        runJs("if(window._pdTtsOnProgress){window._pdTtsOnProgress('" + esc(id) + "'," + pct + ");}");
    }

    private void fireError(String id, String msg) {
        runJs("if(window._pdTtsOnError){window._pdTtsOnError('" + esc(id) + "','" + esc(msg) + "');}");
    }

    private void fireErrorIfPending(String msg) {
        String id = pendingId;
        pendingText = null; pendingId = null;
        if (id != null) fireError(id, msg);
        else fireError("", msg);
    }

    // ---- JS-callable ----

    @JavascriptInterface
    public boolean isReady() { return state == STATE_READY; }

    @JavascriptInterface
    public int getState() { return state; }

    @JavascriptInterface
    public String speak(String text) { return speakAtRate(text, 1.0f); }

    @JavascriptInterface
    public String speakAtRate(final String text, final double rate) {
        if (text == null || text.length() == 0) return "";
        final String id = UUID.randomUUID().toString();
        final float r = (float) Math.max(0.5, Math.min(2.0, rate));

        if (state == STATE_READY) {
            doSpeak(text, r, id);
        } else if (state == STATE_INIT) {
            // engine still waking up — queue this request; it plays automatically on init
            pendingText = text; pendingRate = r; pendingId = id;
            runJs("if(window._pdTtsWarming){window._pdTtsWarming('" + id + "');}");
        } else { // FAILED
            fireError(id, "Phone's speech engine isn't available. Settings → General management → Text-to-speech output → pick Google TTS (or tap Install Voice Data).");
        }
        return id;
    }

    private void doSpeak(final String text, final float rate, final String id) {
        activeLen = text.length();
        main.post(new Runnable() {
            @Override public void run() {
                try {
                    tts.stop();
                    tts.setSpeechRate(rate);
                    int res;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        res = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id);
                    } else {
                        Bundle params = new Bundle();
                        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id);
                        res = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null);
                    }
                    if (res != TextToSpeech.SUCCESS) {
                        fireError(id, "Speech couldn't start. If this keeps happening, restart your phone or switch the TTS engine in Settings.");
                    }
                } catch (Exception e) {
                    fireError(id, "Speech error: " + e.getMessage());
                }
            }
        });
    }

    @JavascriptInterface
    public void stop() {
        pendingText = null; pendingId = null;
        main.post(new Runnable() { @Override public void run() { stopInternal(); } });
    }

    private void stopInternal() {
        try { if (tts != null) tts.stop(); } catch (Exception ignored) {}
        releaseFocus();
    }

    // ---- lifecycle ----

    public void release() {
        state = STATE_FAILED;
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
