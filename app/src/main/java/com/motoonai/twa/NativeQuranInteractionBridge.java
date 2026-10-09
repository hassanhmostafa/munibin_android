package com.motoonai.twa;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.webkit.WebView;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;
import org.json.JSONArray;

import java.util.ArrayList;

/** Native microphone + compass services used only by the packaged Quran reader. */
final class NativeQuranInteractionBridge implements SensorEventListener {
    static final int MICROPHONE_PERMISSION_REQUEST = 705;

    private final Activity activity;
    private final WebView webView;
    private final Handler speechHandler = new Handler(Looper.getMainLooper());
    private SpeechRecognizer speechRecognizer;
    private SpeechCycle speechCycle;
    private String speechRequestId;
    private boolean speechShouldContinue;
    private boolean microphonePermissionPending;
    private boolean awaitingMicrophonePermission;
    private boolean destroyed;
    private long speechGeneration;
    private long speechUtteranceId;
    private int speechRetryCount;
    private Runnable speechRestart;
    private Runnable speechTimeout;
    private final NativeQuranAudioCapture quranAudioCapture;
    private String quranAudioRequestId;
    private boolean quranAudioShouldContinue;
    private boolean awaitingQuranAudioPermission;
    private long quranAudioGeneration;
    private SensorManager sensorManager;
    private Sensor rotationSensor;

    NativeQuranInteractionBridge(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
        quranAudioCapture = new NativeQuranAudioCapture(new NativeQuranAudioCapture.Listener() {
            @Override public void onFrame(long generation, long sequence, String pcmBase64) {
                if (isQuranAudioSessionActive(generation)) emitQuranAudio(sequence, pcmBase64, "", "");
            }

            @Override public void onState(long generation, String state, String error) {
                if (!isQuranAudioSessionActive(generation)) return;
                if ("error".equals(state)) failQuranAudio(error);
                else emitQuranAudio(0, null, state, error);
            }
        });
        sensorManager = (SensorManager) activity.getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
            if (rotationSensor == null) rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        }
    }

    void startSpeech(String requestId) {
        activity.runOnUiThread(() -> {
            if (destroyed || activity.isFinishing()) return;
            stopQuranAudioOnMain(true);
            stopSpeechOnMain(false);
            speechRequestId = requestId == null ? "" : requestId;
            speechShouldContinue = true;
            speechUtteranceId = 0;
            speechRetryCount = 0;
            emitSpeech(speechRequestId, speechUtteranceId, "", false, "", "starting", null);
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                awaitingMicrophonePermission = true;
                if (!microphonePermissionPending) {
                    microphonePermissionPending = true;
                    try {
                        ActivityCompat.requestPermissions(activity, new String[]{Manifest.permission.RECORD_AUDIO}, MICROPHONE_PERMISSION_REQUEST);
                    } catch (Exception error) {
                        microphonePermissionPending = false;
                        failSpeech("permission-denied");
                    }
                }
                return;
            }
            beginRecognition(speechGeneration);
        });
    }

    void onMicrophonePermissionResult(boolean granted) {
        activity.runOnUiThread(() -> {
            microphonePermissionPending = false;
            // A dismissed/late permission result must not revive a stopped session.
            if (!destroyed && quranAudioShouldContinue && awaitingQuranAudioPermission) {
                awaitingQuranAudioPermission = false;
                if (granted) beginQuranAudio(quranAudioGeneration);
                else failQuranAudio("permission-denied");
                return;
            }
            if (destroyed || !speechShouldContinue || !awaitingMicrophonePermission) return;
            awaitingMicrophonePermission = false;
            if (granted) beginRecognition(speechGeneration);
            else failSpeech("permission-denied");
        });
    }

    private boolean isSpeechSessionActive(long generation) {
        return !destroyed && speechShouldContinue && speechGeneration == generation;
    }

    private void beginRecognition(long generation) {
        if (!isSpeechSessionActive(generation) || speechRecognizer != null) return;
        try {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                failSpeech("permission-denied");
                return;
            }
            if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
                failSpeech("unavailable");
                return;
            }

            // Each utterance owns its recognizer and listener. Android can deliver
            // callbacks after cancellation; they must never become a new session's text.
            SpeechCycle cycle = new SpeechCycle(generation, ++speechUtteranceId, speechRequestId);
            speechCycle = cycle;
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(activity);
            speechRecognizer.setRecognitionListener(cycle);
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-SA");
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ar-SA");
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
            emitSpeech(cycle.requestId, cycle.utteranceId, "", false, "", "starting", null);
            armSpeechTimeout(cycle, 15000L);
            speechRecognizer.startListening(intent);
        } catch (SecurityException error) {
            failSpeech("permission-denied");
        } catch (Exception error) {
            failSpeech("start-failed");
        }
    }

    void stopSpeech() {
        activity.runOnUiThread(() -> {
            stopSpeechOnMain(true);
            stopQuranAudioOnMain(true);
        });
    }

    void startQuranAudio(String requestId) {
        activity.runOnUiThread(() -> {
            if (destroyed || activity.isFinishing()) return;
            stopSpeechOnMain(true);
            stopQuranAudioOnMain(false);
            quranAudioRequestId = requestId == null ? "" : requestId;
            quranAudioShouldContinue = true;
            emitQuranAudio(0, null, "starting", "");
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                awaitingQuranAudioPermission = true;
                if (!microphonePermissionPending) {
                    microphonePermissionPending = true;
                    try {
                        ActivityCompat.requestPermissions(activity, new String[]{Manifest.permission.RECORD_AUDIO}, MICROPHONE_PERMISSION_REQUEST);
                    } catch (Exception error) {
                        microphonePermissionPending = false;
                        failQuranAudio("permission-denied");
                    }
                }
                return;
            }
            beginQuranAudio(quranAudioGeneration);
        });
    }

    void stopQuranAudio() {
        activity.runOnUiThread(() -> stopQuranAudioOnMain(true));
    }

    private boolean isQuranAudioSessionActive(long generation) {
        return !destroyed && quranAudioShouldContinue && quranAudioGeneration == generation;
    }

    private void beginQuranAudio(long generation) {
        if (!isQuranAudioSessionActive(generation)) return;
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            failQuranAudio("permission-denied");
            return;
        }
        quranAudioCapture.start(generation);
    }

    private void stopQuranAudioOnMain(boolean notify) {
        boolean wasActive = quranAudioShouldContinue;
        if (notify && wasActive) emitQuranAudio(0, null, "stopped", "");
        quranAudioShouldContinue = false;
        awaitingQuranAudioPermission = false;
        ++quranAudioGeneration;
        quranAudioCapture.stop();
        quranAudioRequestId = null;
    }

    private void failQuranAudio(String error) {
        // Deliver the error for the original request before invalidating it.
        emitQuranAudio(0, null, "error", error);
        stopQuranAudioOnMain(false);
    }

    private void stopSpeechOnMain(boolean notify) {
        boolean wasActive = speechShouldContinue;
        String requestId = speechRequestId;
        long utteranceId = speechUtteranceId;
        speechShouldContinue = false;
        awaitingMicrophonePermission = false;
        ++speechGeneration;
        if (speechRestart != null) speechHandler.removeCallbacks(speechRestart);
        speechRestart = null;
        releaseSpeechRecognizer(true);
        if (notify && wasActive) emitSpeech(requestId, utteranceId, "", true, "", "stopped", null);
        speechRequestId = null;
    }

    private void clearSpeechTimeout() {
        if (speechTimeout != null) speechHandler.removeCallbacks(speechTimeout);
        speechTimeout = null;
    }

    private void armSpeechTimeout(SpeechCycle cycle, long delayMs) {
        clearSpeechTimeout();
        speechTimeout = () -> {
            speechTimeout = null;
            if (cycle.isCurrent()) failSpeech("recognition-timeout");
        };
        speechHandler.postDelayed(speechTimeout, delayMs);
    }

    private void releaseSpeechRecognizer(boolean cancel) {
        clearSpeechTimeout();
        if (speechCycle != null) speechCycle.closed = true;
        speechCycle = null;
        SpeechRecognizer previous = speechRecognizer;
        speechRecognizer = null;
        if (previous != null) {
            if (cancel) {
                try { previous.cancel(); } catch (Exception ignored) {}
            }
            try { previous.destroy(); } catch (Exception ignored) {}
        }
    }

    private void failSpeech(String error) {
        String requestId = speechRequestId;
        long utteranceId = speechUtteranceId;
        stopSpeechOnMain(false);
        emitSpeech(requestId, utteranceId, "", true, error, "error", null);
    }

    private void scheduleSpeechRestart(long generation, long delayMs) {
        if (!isSpeechSessionActive(generation)) return;
        if (speechRestart != null) speechHandler.removeCallbacks(speechRestart);
        speechRestart = () -> {
            speechRestart = null;
            if (isSpeechSessionActive(generation)) beginRecognition(generation);
        };
        emitSpeech(speechRequestId, speechUtteranceId, "", false, "", "restarting", null);
        speechHandler.postDelayed(speechRestart, delayMs);
    }

    void startCompass() {
        activity.runOnUiThread(() -> {
            if (sensorManager == null || rotationSensor == null) {
                emitHeading(0f, -1);
                return;
            }
            sensorManager.unregisterListener(this);
            sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_UI);
        });
    }

    void stopCompass() {
        activity.runOnUiThread(() -> { if (sensorManager != null) sensorManager.unregisterListener(this); });
    }

    void destroy() {
        activity.runOnUiThread(() -> {
            if (destroyed) return;
            stopSpeechOnMain(false);
            stopQuranAudioOnMain(false);
            if (sensorManager != null) sensorManager.unregisterListener(this);
            destroyed = true;
        });
    }

    private void emitSpeech(String requestId, long utteranceId, String text, boolean isFinal, String error, String state, Bundle results) {
        try {
            JSONObject detail = new JSONObject();
            detail.put("requestId", requestId == null ? "" : requestId);
            detail.put("utteranceId", utteranceId);
            detail.put("text", text == null ? "" : text);
            detail.put("isFinal", isFinal);
            detail.put("state", state);
            if (error != null && !error.isEmpty()) detail.put("error", error);
            if (results != null) {
                ArrayList<String> values = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                float[] confidence = results.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES);
                JSONArray alternatives = new JSONArray();
                if (values != null) {
                    for (int index = 0; index < values.size(); ++index) {
                        JSONObject alternative = new JSONObject();
                        alternative.put("text", values.get(index));
                        if (confidence != null && index < confidence.length && confidence[index] >= 0f && confidence[index] <= 1f) {
                            alternative.put("confidence", confidence[index]);
                            if (index == 0) detail.put("confidence", confidence[index]);
                        }
                        alternatives.put(alternative);
                    }
                }
                detail.put("alternatives", alternatives);
            }
            emitEvent("munibin-native-speech", detail);
        } catch (Exception ignored) {}
    }

    private void emitHeading(float heading, int accuracy) {
        try {
            JSONObject detail = new JSONObject();
            detail.put("heading", heading);
            detail.put("accuracy", accuracy);
            emitEvent("munibin-native-heading", detail);
        } catch (Exception ignored) {}
    }

    private void emitQuranAudio(long sequence, String pcmBase64, String state, String error) {
        try {
            JSONObject detail = new JSONObject();
            detail.put("requestId", quranAudioRequestId == null ? "" : quranAudioRequestId);
            detail.put("sequence", sequence);
            detail.put("sampleRate", NativeQuranAudioCapture.SAMPLE_RATE);
            if (pcmBase64 != null) detail.put("pcmBase64", pcmBase64);
            if (state != null && !state.isEmpty()) detail.put("state", state);
            if (error != null && !error.isEmpty()) detail.put("error", error);
            emitEvent("munibin-native-quran-audio", detail);
        } catch (Exception ignored) {}
    }

    private void emitEvent(String name, JSONObject detail) {
        final String js = "window.dispatchEvent(new CustomEvent(" + JSONObject.quote(name) + ", {detail:" + detail.toString() + "}));";
        activity.runOnUiThread(() -> {
            if (!destroyed && !activity.isFinishing()) webView.evaluateJavascript(js, null);
        });
    }

    private String firstResult(Bundle results) {
        if (results == null) return "";
        ArrayList<String> values = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return values == null || values.isEmpty() ? "" : values.get(0);
    }

    private final class SpeechCycle implements RecognitionListener {
        final long generation;
        final long utteranceId;
        final String requestId;
        boolean closed;

        SpeechCycle(long generation, long utteranceId, String requestId) {
            this.generation = generation;
            this.utteranceId = utteranceId;
            this.requestId = requestId;
        }

        boolean isCurrent() {
            return !closed && speechCycle == this && isSpeechSessionActive(generation);
        }

        @Override public void onReadyForSpeech(Bundle params) {
            if (!isCurrent()) return;
            clearSpeechTimeout();
            emitSpeech(requestId, utteranceId, "", false, "", "listening", null);
        }
        @Override public void onBeginningOfSpeech() {
            if (isCurrent()) emitSpeech(requestId, utteranceId, "", false, "", "listening", null);
        }
        @Override public void onRmsChanged(float rmsdB) {}
        @Override public void onBufferReceived(byte[] buffer) {}
        @Override public void onEvent(int eventType, Bundle params) {}
        @Override public void onEndOfSpeech() {
            if (!isCurrent()) return;
            emitSpeech(requestId, utteranceId, "", false, "", "processing", null);
            armSpeechTimeout(this, 15000L);
        }

        @Override public void onPartialResults(Bundle results) {
            if (!isCurrent()) return;
            String text = firstResult(results);
            if (!text.isEmpty()) emitSpeech(requestId, utteranceId, text, false, "", "listening", results);
        }

        @Override public void onResults(Bundle results) {
            if (!isCurrent()) return;
            String text = firstResult(results);
            speechRetryCount = 0;
            releaseSpeechRecognizer(false);
            // Always send the final boundary, including an empty result. Revised
            // partial transcripts share this numeric utteranceId in the web reader.
            emitSpeech(requestId, utteranceId, text, true, "", "processing", results);
            scheduleSpeechRestart(generation, 180L);
        }

        @Override public void onError(int error) {
            if (!isCurrent()) return;
            releaseSpeechRecognizer(false);
            if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                speechRetryCount = 0;
                emitSpeech(requestId, utteranceId, "", true, "no-match", "restarting", null);
                scheduleSpeechRestart(generation, 350L);
                return;
            }
            if ((error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT
                    || error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED) && speechRetryCount < 2) {
                ++speechRetryCount;
                emitSpeech(requestId, utteranceId, "", true, "", "restarting", null);
                scheduleSpeechRestart(generation, 600L * speechRetryCount);
                return;
            }
            String code;
            switch (error) {
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: code = "permission-denied"; break;
                case SpeechRecognizer.ERROR_NETWORK:
                case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: code = "network"; break;
                case SpeechRecognizer.ERROR_AUDIO: code = "audio-error"; break;
                case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED: code = "language-not-supported"; break;
                case SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE: code = "language-unavailable"; break;
                case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: code = "busy"; break;
                case SpeechRecognizer.ERROR_TOO_MANY_REQUESTS: code = "too-many-requests"; break;
                default: code = "service-error";
            }
            failSpeech(code);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ROTATION_VECTOR && event.sensor.getType() != Sensor.TYPE_GAME_ROTATION_VECTOR) return;
        float[] rotation = new float[9];
        float[] orientation = new float[3];
        SensorManager.getRotationMatrixFromVector(rotation, event.values);
        SensorManager.getOrientation(rotation, orientation);
        float heading = (float) Math.toDegrees(orientation[0]);
        if (heading < 0) heading += 360f;
        emitHeading(heading, event.accuracy);
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}
