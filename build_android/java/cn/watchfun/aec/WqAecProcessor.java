package cn.watchfun.aec;

/**
 * WebRTC AEC wrapper for TTS echo cancellation
 * 
 * This class provides a simple interface to WebRTC's Acoustic Echo Cancellation
 * specifically optimized for TTS (Text-to-Speech) applications.
 * 
 * Usage:
 * 1. Initialize the AEC processor
 * 2. For each TTS audio chunk: call processTtsAudio() BEFORE playing it
 * 3. For each microphone chunk: call processMicrophoneAudio() to get clean audio
 * 4. Monitor performance with getMetrics()
 * 
 * Important: All audio must be 16kHz, 16-bit PCM, mono, 160 samples (10ms chunks)
 */
public class WqAecProcessor {
    static {
        System.loadLibrary("wq_aec_tts");
    }

    // Audio configuration constants
    public static final int SAMPLE_RATE = 16000;
    public static final int FRAME_SIZE = 160;  // 10ms at 16kHz
    public static final int CHANNELS = 1;      // Mono
    public static final int BITS_PER_SAMPLE = 16;

    // Native methods
    public native boolean nativeInitialize();
    public native void nativeDestroy();
    public native boolean nativeProcessTtsAudio(short[] ttsData);
    public native boolean nativeProcessMicrophoneAudio(short[] micData, short[] outputData, int soundCardDelay);
    public native double[] nativeGetMetrics();
    public native boolean nativeSetConfig(int nlpMode, int skewMode, int metricsMode, int delayLogging);
    public native int[] nativeGetConfig();
    public native void nativeSetSoundCardDelay(int delayMs);
    public native byte[] nativeGetCleanAudioAsWAV(int outputSampleRate);
    public native byte[] nativeGetCleanAudioAsPCM(int outputSampleRate);
    public native void nativeClearCleanAudioBuffer();

    // High-level Java API
    private boolean initialized = false;

    public boolean initialize() {
        if (!initialized) {
            initialized = nativeInitialize();
        }
        return initialized;
    }

    public void destroy() {
        if (initialized) {
            nativeDestroy();
            initialized = false;
        }
    }

    public boolean processTtsAudio(short[] ttsData) {
        if (!initialized || ttsData.length != FRAME_SIZE) {
            return false;
        }
        return nativeProcessTtsAudio(ttsData);
    }

    public short[] processMicrophoneAudio(short[] micData) {
        return processMicrophoneAudio(micData, 100); // Default 100ms delay
    }

    public short[] processMicrophoneAudio(short[] micData, int soundCardDelay) {
        if (!initialized || micData.length != FRAME_SIZE) {
            return null;
        }
        
        short[] output = new short[FRAME_SIZE];
        if (nativeProcessMicrophoneAudio(micData, output, soundCardDelay)) {
            return output;
        }
        return null;
    }

    public AecMetrics getMetrics() {
        if (!initialized) return null;
        
        double[] metrics = nativeGetMetrics();
        if (metrics != null && metrics.length == 4) {
            return new AecMetrics(metrics[0], metrics[1], (int)metrics[2], metrics[3] > 0.5);
        }
        return null;
    }

    public void setSoundCardDelay(int delayMs) {
        if (initialized) {
            nativeSetSoundCardDelay(delayMs);
        }
    }

    public byte[] getCleanAudioAsWAV() {
        return getCleanAudioAsWAV(44100);
    }

    public byte[] getCleanAudioAsWAV(int outputSampleRate) {
        if (!initialized) return null;
        return nativeGetCleanAudioAsWAV(outputSampleRate);
    }

    public byte[] getCleanAudioAsPCM() {
        return getCleanAudioAsPCM(44100);
    }

    public byte[] getCleanAudioAsPCM(int outputSampleRate) {
        if (!initialized) return null;
        return nativeGetCleanAudioAsPCM(outputSampleRate);
    }

    public void clearCleanAudioBuffer() {
        if (initialized) {
            nativeClearCleanAudioBuffer();
        }
    }

    // Configuration methods
    public void setConfig(int nlpMode, int skewMode, boolean metricsEnabled, boolean delayLogging) {
        if (initialized) {
            nativeSetConfig(nlpMode, skewMode, metricsEnabled ? 1 : 0, delayLogging ? 1 : 0);
        }
    }

    public static class AecMetrics {
        public final double echoReturnLoss;
        public final double echoReturnLossEnhancement;
        public final int delayMs;
        public final boolean isConverged;

        public AecMetrics(double erl, double erle, int delay, boolean converged) {
            this.echoReturnLoss = erl;
            this.echoReturnLossEnhancement = erle;
            this.delayMs = delay;
            this.isConverged = converged;
        }

        @Override
        public String toString() {
            return String.format("AEC Metrics: ERL=%.2fdB, ERLE=%.2fdB, Delay=%dms, Converged=%s", 
                               echoReturnLoss, echoReturnLossEnhancement, delayMs, isConverged);
        }
    }
}
