# WebRTC AEC TTS Usage Guide

## Overview

This library provides WebRTC-based Acoustic Echo Cancellation (AEC) specifically optimized for Text-to-Speech (TTS) applications on Android. It prevents the TTS audio played through speakers from being picked up by the microphone, eliminating echo loops in real-time conversation systems.

## Key Features

- **Real-time Echo Cancellation**: Uses WebRTC AEC for high-quality echo suppression
- **TTS Optimized**: Designed specifically for TTS echo cancellation scenarios  
- **Cross-platform Ready**: C++ core for future iOS xcframework support
- **Simple API**: Easy integration with frame-based processing
- **Audio Export**: Export processed clean audio as WAV or PCM

## Technical Specifications

- **Sample Rate**: 16kHz (WebRTC AEC requirement)
- **Frame Size**: 160 samples (10ms frames)
- **Audio Format**: 16-bit PCM, mono
- **Latency**: Low latency processing suitable for real-time applications
- **Android API**: Minimum API level 27

## Installation

### Step 1: Add AAR to Project

1. Copy `wq-aec-1.0.aar` to your Android project's `app/libs/` folder
2. Add to your `app/build.gradle`:

```kotlin
dependencies {
    implementation(files("libs/wq-aec-1.0.aar"))
}
```

### Step 2: Add Permissions

Add to your `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
```

## Basic Usage

### Java Integration

```java
import cn.watchfun.aec.WqAecProcessor;

public class TTSEchoCanceller {
    private WqAecProcessor aecProcessor;
    
    public void initialize() {
        aecProcessor = new WqAecProcessor();
        if (!aecProcessor.initialize()) {
            Log.e("AEC", "Failed to initialize AEC processor");
            return;
        }
        
        // Optional: Configure AEC settings
        aecProcessor.setSoundCardDelay(100); // 100ms typical for Android
        aecProcessor.setConfig(2, 0, true, false); // Moderate NLP, metrics on
    }
    
    public void processTTSAndMicrophone() {
        // 1. CRITICAL: Process TTS audio BEFORE playing it
        short[] ttsFrame = getTTSAudioFrame(); // 160 samples, 16kHz
        aecProcessor.processTtsAudio(ttsFrame);
        
        // 2. Play TTS audio through speakers
        playTTSAudio(ttsFrame);
        
        // 3. Process microphone input to remove echo
        short[] micFrame = getMicrophoneAudioFrame(); // 160 samples, 16kHz
        short[] cleanAudio = aecProcessor.processMicrophoneAudio(micFrame);
        
        // 4. Use clean audio for further processing (ASR, etc.)
        if (cleanAudio != null) {
            processCleanAudio(cleanAudio);
        }
    }
    
    public void monitorPerformance() {
        WqAecProcessor.AecMetrics metrics = aecProcessor.getMetrics();
        if (metrics != null) {
            Log.i("AEC", "ERLE: " + metrics.echoReturnLossEnhancement + " dB");
            Log.i("AEC", "Delay: " + metrics.delayMs + " ms");
            Log.i("AEC", "Converged: " + metrics.isConverged);
        }
    }
    
    public void cleanup() {
        if (aecProcessor != null) {
            aecProcessor.destroy();
        }
    }
}
```

### Audio Format Requirements

**Input Audio Format (Both TTS and Microphone):**
- Sample Rate: 16,000 Hz
- Channels: 1 (mono)
- Sample Format: 16-bit signed integer
- Frame Size: 160 samples (10ms)

**Frame Size Calculation:**
```
Frame Duration = 10ms
Sample Rate = 16,000 Hz
Frame Size = (10ms / 1000ms) * 16,000 Hz = 160 samples
```

## Advanced Configuration

### AEC Configuration Parameters

```java
// NLP Mode (Nonlinear Processing)
// 0 = Off, 1 = Mild, 2 = Moderate (recommended), 3 = Aggressive
int nlpMode = 2;

// Skew Mode (Clock drift compensation)  
// 0 = Off (recommended for TTS), 1 = On
int skewMode = 0;

// Metrics Mode
// true = Enable performance metrics, false = Disable
boolean metricsEnabled = true;

// Delay Logging
// true = Enable delay logging (debug), false = Disable (recommended)
boolean delayLogging = false;

aecProcessor.setConfig(nlpMode, skewMode, metricsEnabled, delayLogging);
```

### Sound Card Delay Tuning

The sound card delay is critical for optimal performance:

```java
// Android typical delays:
// - Low-end devices: 120-150ms
// - Mid-range devices: 80-120ms  
// - High-end devices: 60-100ms

int delayMs = 100; // Start with 100ms, tune based on testing
aecProcessor.setSoundCardDelay(delayMs);
```

## Audio Export Features

### Export Clean Audio as WAV

```java
// Export processed clean audio for analysis or storage
byte[] wavData = aecProcessor.getCleanAudioAsWAV(44100); // Resample to 44.1kHz
if (wavData != null) {
    saveToFile(wavData, "clean_audio.wav");
}
```

### Export Clean Audio as PCM

```java
// Export raw PCM data for further processing
byte[] pcmData = aecProcessor.getCleanAudioAsPCM(16000); // Keep at 16kHz
if (pcmData != null) {
    processRawAudio(pcmData);
}
```

## Performance Optimization

### Memory Management

```java
// Clear audio buffer periodically to prevent memory buildup
aecProcessor.clearCleanAudioBuffer();
```

### Threading Recommendations

```java
// Process audio on a dedicated background thread
HandlerThread audioThread = new HandlerThread("AECAudioProcessor");
audioThread.start();
Handler audioHandler = new Handler(audioThread.getLooper());

audioHandler.post(() -> {
    // All AEC processing should happen on this thread
    aecProcessor.processTtsAudio(ttsFrame);
    short[] cleanAudio = aecProcessor.processMicrophoneAudio(micFrame);
});
```

## Troubleshooting

### Common Issues

1. **Poor Echo Cancellation**
   - Verify TTS audio is processed BEFORE playback
   - Tune sound card delay (try 80-150ms range)
   - Check audio format (must be 16kHz, 160 samples)

2. **Audio Artifacts**
   - Reduce NLP mode aggressiveness
   - Ensure consistent frame timing
   - Check for proper frame size (160 samples)

3. **Performance Issues**
   - Process on background thread
   - Clear audio buffer regularly
   - Monitor metrics for convergence

### Debug Logging

Enable debug logging to troubleshoot:

```java
// Check AEC metrics regularly
WqAecProcessor.AecMetrics metrics = aecProcessor.getMetrics();
Log.d("AEC", "ERLE: " + metrics.echoReturnLossEnhancement + " dB");

// ERLE (Echo Return Loss Enhancement) values:
// > 10 dB = Good echo cancellation
// 5-10 dB = Fair echo cancellation  
// < 5 dB = Poor echo cancellation (needs tuning)
```

## Integration with TTS Services

### Typical TTS Integration Flow

```java
public class TTSService {
    private WqAecProcessor aecProcessor;
    
    public void playTTSWithEchoCancellation(String text) {
        // 1. Get TTS audio chunks from your TTS service
        List<byte[]> ttsChunks = ttsService.synthesize(text);
        
        for (byte[] chunk : ttsChunks) {
            // 2. Convert to required format (16kHz, 160 samples)
            short[] ttsFrame = convertToAECFormat(chunk);
            
            // 3. CRITICAL: Process with AEC BEFORE playing
            aecProcessor.processTtsAudio(ttsFrame);
            
            // 4. Play through speakers
            audioTrack.write(ttsFrame, 0, ttsFrame.length);
        }
    }
    
    private short[] convertToAECFormat(byte[] audioData) {
        // Convert your TTS audio format to 16kHz, 160 samples
        // Implementation depends on your TTS service's output format
        return resampleAndFrameAudio(audioData);
    }
}
```

## Performance Expectations

- **Latency**: < 20ms processing latency per frame
- **Echo Reduction**: 6-10 dB ERLE typical for WebRTC AEC
- **CPU Usage**: Low (optimized WebRTC implementation)
- **Memory Usage**: ~500KB for AEC processing buffers

## Future iOS Support

This library is designed with cross-platform support in mind. The C++ core can be packaged as an iOS xcframework using the same source files with platform-specific build configurations.

## Support

For technical support or questions:
1. Check this documentation for common solutions
2. Review debug logs and AEC metrics
3. Verify audio format requirements
4. Test with different sound card delay values

The library is optimized for TTS echo cancellation scenarios and should provide reliable performance when properly configured.