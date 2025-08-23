# WebRTC AEC Android AAR - Usage Guide

## Overview

This document provides comprehensive instructions for integrating and using the WebRTC AEC (Acoustic Echo Cancellation) AAR in your Android TTS application.

## Build Output

✅ **Successfully Built:**
- **File:** `wq-aec-1.0.aar`
- **Location:** `/Users/mac/Documents/GitHub/webrtc-android-jni/android_output/`
- **Architectures:** arm64-v8a, armeabi-v7a, x86_64, x86
- **Size:** ~650KB
- **Native Libraries:** 4 architectures supported

## Integration Steps

### 1. Add AAR to Your Android Project

```bash
# Copy AAR to your project
cp /Users/mac/Documents/GitHub/webrtc-android-jni/android_output/wq-aec-1.0.aar \
   /path/to/your/android/project/app/libs/
```

### 2. Update build.gradle (Module: app)

```gradle
android {
    // ... existing configuration
    
    packagingOptions {
        pickFirst '**/libc++_shared.so'
        pickFirst '**/libwq_aec_tts.so'
    }
}

dependencies {
    implementation files('libs/wq-aec-1.0.aar')
    // ... other dependencies
}
```

### 3. Add Permissions (AndroidManifest.xml)

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
```

## Usage Examples

### Basic TTS Echo Cancellation

```java
import cn.watchfun.aec.WqAecProcessor;

public class TtsEchoCancellation {
    private WqAecProcessor aecProcessor;
    private static final int FRAME_SIZE = 160; // 10ms at 16kHz
    
    public void initializeAEC() {
        aecProcessor = new WqAecProcessor();
        if (!aecProcessor.initialize()) {
            Log.e(TAG, "Failed to initialize AEC processor");
            return;
        }
        Log.i(TAG, "AEC processor initialized successfully");
    }
    
    public void processTtsAndMicrophone(short[] ttsAudio, short[] micAudio) {
        // CRITICAL: Process TTS audio BEFORE playing it
        if (!aecProcessor.processTtsAudio(ttsAudio)) {
            Log.w(TAG, "Failed to process TTS audio");
        }
        
        // Process microphone audio to get clean output
        short[] cleanAudio = aecProcessor.processMicrophoneAudio(micAudio);
        if (cleanAudio != null) {
            // Use cleanAudio for ASR or further processing
            sendToASR(cleanAudio);
        }
    }
    
    public void cleanup() {
        if (aecProcessor != null) {
            aecProcessor.destroy();
        }
    }
}
```

### Advanced Configuration

```java
public class AdvancedAecUsage {
    private WqAecProcessor aecProcessor;
    
    public void setupAdvancedAEC() {
        aecProcessor = new WqAecProcessor();
        aecProcessor.initialize();
        
        // Configure AEC parameters
        // nlpMode: 0=mild, 1=moderate, 2=aggressive
        // skewMode: 0=disabled, 1=enabled
        aecProcessor.setConfig(1, 1, true, false);
        
        // Set Android audio system delay (80-150ms typical)
        aecProcessor.setSoundCardDelay(100);
    }
    
    public void monitorPerformance() {
        WqAecProcessor.AecMetrics metrics = aecProcessor.getMetrics();
        if (metrics != null) {
            Log.i(TAG, String.format(
                "AEC Performance - ERL: %.1fdB, ERLE: %.1fdB, Delay: %dms, Converged: %s",
                metrics.echoReturnLoss,
                metrics.echoReturnLossEnhancement,
                metrics.delayMs,
                metrics.isConverged
            ));
        }
    }
}
```

## Audio Requirements

### Critical Specifications
- **Sample Rate:** 16kHz (mandatory)
- **Frame Size:** 160 samples (10ms chunks)
- **Bit Depth:** 16-bit PCM
- **Channels:** Mono
- **Processing Order:** TTS audio MUST be processed before playback

### Audio Pipeline Integration

```java
public class AudioPipelineIntegration {
    private WqAecProcessor aecProcessor;
    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    
    public void setupAudioPipeline() {
        // Initialize AEC
        aecProcessor = new WqAecProcessor();
        aecProcessor.initialize();
        
        // Configure AudioRecord (microphone)
        int bufferSize = AudioRecord.getMinBufferSize(
            16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        audioRecord = new AudioRecord(
            MediaRecorder.AudioSource.MIC,
            16000, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, bufferSize);
        
        // Configure AudioTrack (speaker)
        audioTrack = new AudioTrack(
            AudioManager.STREAM_MUSIC, 16000,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize, AudioTrack.MODE_STREAM);
    }
    
    public void processAudioFrame(short[] ttsFrame) {
        // Step 1: Process TTS frame before playing
        aecProcessor.processTtsAudio(ttsFrame);
        
        // Step 2: Play TTS audio
        audioTrack.write(ttsFrame, 0, ttsFrame.length);
        
        // Step 3: Record and process microphone
        short[] micFrame = new short[160];
        audioRecord.read(micFrame, 0, 160);
        
        // Step 4: Get echo-canceled audio
        short[] cleanFrame = aecProcessor.processMicrophoneAudio(micFrame);
        
        // Step 5: Send to ASR or further processing
        if (cleanFrame != null) {
            processCleanAudio(cleanFrame);
        }
    }
}
```

## Performance Optimization

### Best Practices

1. **Timing is Critical**
   ```java
   // ✅ CORRECT: Process TTS before playback
   aecProcessor.processTtsAudio(ttsFrame);
   audioTrack.write(ttsFrame, 0, ttsFrame.length);
   
   // ❌ WRONG: Processing after playback reduces effectiveness
   audioTrack.write(ttsFrame, 0, ttsFrame.length);
   aecProcessor.processTtsAudio(ttsFrame); // Too late!
   ```

2. **Frame Size Management**
   ```java
   // Always use 160-sample frames
   if (audioData.length != 160) {
       Log.w(TAG, "Invalid frame size: " + audioData.length);
       return;
   }
   ```

3. **Monitor Convergence**
   ```java
   WqAecProcessor.AecMetrics metrics = aecProcessor.getMetrics();
   if (!metrics.isConverged) {
       Log.w(TAG, "AEC not yet converged, performance may be suboptimal");
   }
   ```

### Performance Metrics

- **ERL (Echo Return Loss):** 6-15dB (higher is better)
- **ERLE (Echo Return Loss Enhancement):** 10-20dB (higher is better)
- **Convergence Time:** 1-3 seconds typical
- **Delay Detection:** Should be 80-150ms for Android

## Troubleshooting

### Common Issues

1. **"AEC not effective"**
   - Ensure TTS audio is processed before playback
   - Check audio sample rate (must be 16kHz)
   - Verify frame size is exactly 160 samples

2. **"High CPU usage"**
   - Process audio in background thread
   - Use appropriate buffer sizes
   - Monitor memory allocation

3. **"Initialization failed"**
   - Check device architecture support
   - Verify AAR is properly integrated
   - Test with different ABI

### Debug Information

```java
public void debugAecStatus() {
    WqAecProcessor.AecMetrics metrics = aecProcessor.getMetrics();
    if (metrics != null) {
        Log.d(TAG, "AEC Debug Info:");
        Log.d(TAG, "  ERL: " + metrics.echoReturnLoss + "dB");
        Log.d(TAG, "  ERLE: " + metrics.echoReturnLossEnhancement + "dB");
        Log.d(TAG, "  Delay: " + metrics.delayMs + "ms");
        Log.d(TAG, "  Converged: " + metrics.isConverged);
    }
}
```

## Expected Performance

- **Echo Reduction:** 15-25dB typical
- **Latency:** <10ms processing delay
- **CPU Usage:** 5-10% on modern Android devices
- **Memory:** ~2MB runtime footprint

## Support

For issues or questions:
1. Check this documentation first
2. Verify audio pipeline configuration
3. Monitor AEC metrics for performance indicators
4. Test with known working audio samples

---

**Built on:** August 23, 2025  
**Version:** 1.0  
**WebRTC AEC:** Traditional AEC (optimized for TTS)  
**Architectures:** arm64-v8a, armeabi-v7a, x86_64, x86
