#pragma once

#include <stdint.h>
#include <vector>
#include <memory>

// Forward declarations for WebRTC AEC (old API that's actually available)
extern "C" {
    // AEC functions - AecConfig will be included from WebRTC header
    void* WebRtcAec_Create();
    void WebRtcAec_Free(void* aecInst);
    int32_t WebRtcAec_Init(void* aecInst, int32_t sampFreq, int32_t scSampFreq);
    int32_t WebRtcAec_BufferFarend(void* aecInst, const float* farend, size_t nrOfSamples);
    int32_t WebRtcAec_Process(void* aecInst, const float* const* nearend, size_t num_bands,
                             float* const* out, size_t nrOfSamples, int16_t msInSndCardBuf, int32_t skew);
}



namespace webrtc_aec_tts {

/**
 * WebRTC AEC Processor for TTS Echo Cancellation
 * 
 * This class provides a simplified interface to WebRTC's Acoustic Echo Cancellation
 * specifically optimized for TTS (Text-to-Speech) applications.
 * 
 * Key Features:
 * - Real-time echo cancellation for TTS audio playback
 * - Optimized for mobile devices (Android/iOS)
 * - Simple frame-based processing (10ms frames)
 * - Automatic delay estimation and compensation
 * 
 * Usage:
 * 1. Initialize the processor with Initialize()
 * 2. For each TTS audio frame: call ProcessTtsAudio() BEFORE playing it
 * 3. For each microphone frame: call ProcessMicrophoneAudio() to get clean audio
 * 4. Monitor performance with GetMetrics()
 * 
 * Audio Format Requirements:
 * - Sample Rate: 16kHz (WebRTC AEC requirement)
 * - Frame Size: 160 samples (10ms at 16kHz)
 * - Channels: 1 (mono)
 * - Sample Format: 16-bit signed integer
 */
class WqAecProcessor {
public:
    // Audio configuration constants
    static const int kSampleRate = 16000;     // 16kHz (WebRTC AEC requirement)
    static const int kFrameSize = 160;        // 160 samples (10ms at 16kHz)
    static const int kChannels = 1;           // Mono
    static const int kBitsPerSample = 16;     // 16-bit samples

    // AEC configuration structure - define our own to avoid conflicts
    struct AecConfig {
        int16_t nlpMode;              // NLP mode: 0=off, 1=mild, 2=moderate, 3=aggressive
        int16_t skewMode;             // Skew mode: 0=off, 1=on
        int16_t metricsMode;          // Metrics mode: 0=off, 1=on
        int delay_logging;            // Delay logging: 0=off, 1=on
    };

    // Performance metrics structure
    struct AecMetrics {
        double echoReturnLoss;           // ERL in dB
        double echoReturnLossEnhancement; // ERLE in dB
        int delayMs;                     // Estimated delay in milliseconds
        double averageResidualEcho;      // Average residual echo level
        bool isConverged;                // Whether AEC has converged
    };

    WqAecProcessor();
    ~WqAecProcessor();

    /**
     * Initialize the AEC processor
     * @return true if initialization successful
     */
    bool Initialize();

    /**
     * Clean up and release resources
     */
    void Destroy();

    /**
     * Process TTS audio (reference/farend signal)
     * Call this BEFORE playing the TTS audio through speakers
     * 
     * @param ttsData TTS audio samples (must be exactly 160 samples)
     * @param numSamples Number of samples (should be 160)
     * @return true if processing successful
     */
    bool ProcessTtsAudio(const int16_t* ttsData, size_t numSamples);

    /**
     * Process microphone audio and remove echo (nearend processing)
     * 
     * @param micData Microphone audio samples (must be exactly 160 samples)
     * @param outputData Output buffer for processed audio (must be at least 160 samples)
     * @param numSamples Number of samples (should be 160)
     * @param msInSndCardBuf Sound card buffer delay in milliseconds (typically 80-150ms for Android)
     * @return true if processing successful
     */
    bool ProcessMicrophoneAudio(const int16_t* micData, int16_t* outputData, 
                               size_t numSamples, int16_t msInSndCardBuf = 100);

    /**
     * Get current AEC performance metrics
     * @param metrics Output metrics structure
     * @return true if metrics available
     */
    bool GetMetrics(AecMetrics* metrics);

    /**
     * Set AEC configuration parameters
     * @param config Configuration parameters
     * @return true if configuration applied successfully
     */
    bool SetConfig(const AecConfig& config);

    /**
     * Get current AEC configuration
     * @param config Output configuration structure
     * @return true if configuration retrieved successfully
     */
    bool GetConfig(AecConfig* config);

    /**
     * Update sound card buffer delay for optimal performance
     * @param delayMs Delay in milliseconds (typically 80-150ms for Android)
     */
    void SetSoundCardDelay(int16_t delayMs);

    /**
     * Check if AEC processor is initialized
     * @return true if initialized
     */
    bool IsInitialized() const { return initialized_; }

    /**
     * Get accumulated clean audio frames for WAV export
     * @param audioFrames Output vector to store audio frames
     * @return Number of frames retrieved
     */
    size_t GetCleanAudioBuffer(std::vector<std::vector<float>>& audioFrames);

    /**
     * Clear the accumulated clean audio buffer
     */
    void ClearCleanAudioBuffer();

    /**
     * Enable or disable frame-level synchronization between TTS and microphone processing
     * @param enabled true to enable synchronization (recommended for echo cancellation)
     */
    void SetFrameSynchronization(bool enabled) { sync_enabled_ = enabled; }

    /**
     * Get current TTS frame buffer size (for debugging)
     * @return number of buffered TTS frames
     */
    size_t GetTtsBufferSize() const { return tts_frame_buffer_.size(); }

    /**
     * Clear TTS frame buffer (useful when stopping/starting recording)
     */
    void ClearTtsBuffer() { tts_frame_buffer_.clear(); }

private:
    void* aec_handle_;              // WebRTC AEC instance handle
    bool initialized_;              // Initialization state
    int16_t sound_card_delay_ms_;   // Sound card buffer delay
    
    // TTS frame synchronization for proper echo cancellation
    std::vector<std::vector<float>> tts_frame_buffer_;  // Buffered TTS frames for synchronization
    bool sync_enabled_;             // Frame synchronization enabled flag
    
    // Clean audio accumulation for export
    std::vector<std::vector<float>> clean_audio_frames_;
    
    // Internal configuration
    AecConfig current_config_;
    
    // Performance tracking
    uint64_t frames_processed_;
    uint64_t farend_frames_;
    uint64_t nearend_frames_;

    // Helper methods
    bool ValidateFrameSize(size_t numSamples) const;
    void AccumulateCleanAudio(const int16_t* audioData, size_t numSamples);
    void InitializeDefaultConfig();
};

} // namespace webrtc_aec_tts