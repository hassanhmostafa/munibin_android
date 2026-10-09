package com.motoonai.twa;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import java.util.concurrent.atomic.AtomicInteger;

/** Foreground PCM capture for the Quran recognizer. No platform speech recognition. */
final class NativeQuranAudioCapture {
    static final int SAMPLE_RATE = 16000;
    private static final int FRAME_SAMPLES = SAMPLE_RATE / 4;
    private static final int MAX_PENDING_FRAMES = 8;

    interface Listener {
        void onFrame(long generation, long sequence, String pcmBase64);
        void onState(long generation, String state, String error);
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private volatile Session current;

    NativeQuranAudioCapture(Listener listener) {
        this.listener = listener;
    }

    // Start and stop are called on the UI thread by NativeQuranInteractionBridge.
    void start(long generation) {
        stop();
        AudioRecord recorder = null;
        try {
            int minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) {
                listener.onState(generation, "error", "audio-unavailable");
                return;
            }
            recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(minimum * 2, FRAME_SAMPLES * 4));
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                recorder.release();
                listener.onState(generation, "error", "audio-unavailable");
                return;
            }
            recorder.startRecording();
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                recorder.release();
                listener.onState(generation, "error", "audio-start-failed");
                return;
            }
            Session session = new Session(generation, recorder);
            current = session;
            session.thread = new Thread(() -> readFrames(session), "MunibinQuranAudio");
            session.thread.setDaemon(true);
            session.thread.start();
            listener.onState(generation, "listening", "");
        } catch (SecurityException error) {
            releaseUnstarted(recorder);
            listener.onState(generation, "error", "permission-denied");
        } catch (Exception error) {
            releaseUnstarted(recorder);
            listener.onState(generation, "error", "audio-start-failed");
        }
    }

    void stop() {
        Session previous = current;
        current = null;
        if (previous == null) return;
        previous.stopped = true;
        // stop() unblocks a pending AudioRecord.read(). Its owning thread releases
        // the recorder in finally, so release never races with a native read.
        try { previous.recorder.stop(); } catch (Exception ignored) {}
    }

    private void releaseUnstarted(AudioRecord recorder) {
        if (recorder == null) return;
        try { recorder.stop(); } catch (Exception ignored) {}
        try { recorder.release(); } catch (Exception ignored) {}
    }

    private boolean isCurrent(Session session) {
        return current == session && !session.stopped;
    }

    private void readFrames(Session session) {
        short[] samples = new short[FRAME_SAMPLES];
        int filled = 0;
        try {
            while (isCurrent(session)) {
                int read = session.recorder.read(samples, filled, samples.length - filled);
                if (!isCurrent(session)) break;
                if (read <= 0) {
                    fail(session, read == AudioRecord.ERROR_DEAD_OBJECT
                            ? "audio-device-lost" : "audio-read-failed");
                    break;
                }
                filled += read;
                if (filled < samples.length) continue;
                byte[] bytes = new byte[FRAME_SAMPLES * 2];
                for (int index = 0; index < samples.length; ++index) {
                    bytes[index * 2] = (byte) (samples[index] & 0xff);
                    bytes[index * 2 + 1] = (byte) ((samples[index] >>> 8) & 0xff);
                }
                String base64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
                long sequence = ++session.sequence;
                if (session.pendingFrames.incrementAndGet() > MAX_PENDING_FRAMES) {
                    session.pendingFrames.decrementAndGet();
                    fail(session, "audio-backpressure");
                    break;
                }
                mainHandler.post(() -> {
                    try {
                        if (isCurrent(session)) listener.onFrame(session.generation, sequence, base64);
                    } finally {
                        session.pendingFrames.decrementAndGet();
                    }
                });
                filled = 0;
            }
        } catch (SecurityException error) {
            fail(session, "permission-denied");
        } catch (Exception error) {
            fail(session, "audio-read-failed");
        } finally {
            session.stopped = true;
            try { session.recorder.stop(); } catch (Exception ignored) {}
            try { session.recorder.release(); } catch (Exception ignored) {}
        }
    }

    private void fail(Session session, String error) {
        if (!isCurrent(session)) return;
        session.stopped = true;
        mainHandler.post(() -> {
            if (current == session) listener.onState(session.generation, "error", error);
        });
    }

    private static final class Session {
        final long generation;
        final AudioRecord recorder;
        final AtomicInteger pendingFrames = new AtomicInteger();
        volatile boolean stopped;
        long sequence;
        Thread thread;

        Session(long generation, AudioRecord recorder) {
            this.generation = generation;
            this.recorder = recorder;
        }
    }
}
