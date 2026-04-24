package com.carlos.pdprep;

import android.content.Context;
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
 * Bridges web JS -> Android native TextToSpeech.
 * Android WebView's window.speechSynthesis is unreliable (empty voice list on many OEM builds).
 * This class exposes NativeTTS.speak(text) / NativeTTS.stop() / NativeTTS.isReady() to JS.
 * When speech finishes, it fires window._pdTtsOnEnd(id) so the JS button can reset state.
 */
public class TtsBridge {

    private final Context appCtx;
    private final WebView webView;
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextToSpeech tts;
    private volatile boolean ready = false;

    public TtsBridge(Context ctx, WebView wv) {
        this.appCtx = ctx.getApplicationContext();
        this.webView = wv;
        initTts();
    }

    private void initTts() {
        tts = new TextToSpeech(appCtx, new TextToSpeech.OnInitListener() {
            @Override public void onInit(int status) {
                if (status != TextToSpeech.SUCCESS) { ready = false; return; }
                try {
                    int r = tts.setLanguage(Locale.US);
                    if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                        tts.setLanguage(Locale.ENGLISH);
                    }
                    tts.setSpeechRate(0.98f);
                    tts.setPitch(1.0f);
                    tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                        @Override public void onStart(String utteranceId) {}
                        @Override public void onDone(String utteranceId) { fireEnd(utteranceId); }
                        @Override public void onError(String utteranceId) { fireEnd(utteranceId); }
                        @Override public void onError(String utteranceId, int errorCode) { fireEnd(utteranceId); }
                    });
                    ready = true;
                } catch (Exception e) {
                    ready = false;
                }
            }
        });
    }

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

    // ---- JS-callable methods ----

    @JavascriptInterface
    public boolean isReady() { return ready; }

    @JavascriptInterface
    public String speak(String text) {
        if (!ready || tts == null || text == null) return "";
        final String id = UUID.randomUUID().toString();
        // must run on main thread for reliability on some OEM builds
        main.post(new Runnable() {
            @Override public void run() {
                try {
                    tts.stop();
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
            @Override public void run() {
                try { if (tts != null) tts.stop(); } catch (Exception ignored) {}
            }
        });
    }

    // ---- lifecycle ----

    public void release() {
        ready = false;
        main.post(new Runnable() {
            @Override public void run() {
                try {
                    if (tts != null) {
                        tts.stop();
                        tts.shutdown();
                    }
                } catch (Exception ignored) {}
                tts = null;
            }
        });
    }
}
