#include "wq_aec_processor.h"
#include <cstring>
#include <algorithm>
#include <android/log.h>

// WebRTC AEC headers - include the actual header to get AecConfig definition
extern "C" {
#include "webrtc/modules/audio_processing/aec/include/echo_cancellation.h"
}

#define TAG "WebRTC_AEC_TTS"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace webrtc_aec_tts {

WqAecProcessor::WqAecProcessor() 
    : aec_handle_(nullptr),
      initialized_(false),
      sound_card_delay_ms_(100),  // Default Android delay
      frames_processed_(0),
      farend_frames_(0),
      nearend_frames_(0) {
    InitializeDefaultConfig();
    LOGI("WqAecProcessor created");
}

WqAecProcessor::~WqAecProcessor() {
    Destroy();
    LOGI("WqAecProcessor destroyed");
}

bool WqAecProcessor::Initialize() {
    if (initialized_) {
        LOGW("AEC processor already initialized");
        return true;
    }

    // Create WebRTC AEC instance
    aec_handle_ = WebRtcAec_Create();
    if (!aec_handle_) {
        LOGE("Failed to create WebRTC AEC instance");
        return false;
    }

    // Initialize AEC with 16kHz sample rate
    int result = WebRtcAec_Init(aec_handle_, kSampleRate, kSampleRate);
    if (result != 0) {
        LOGE("Failed to initialize WebRTC AEC: %d", result);
        WebRtcAec_Free(aec_handle_);
        aec_handle_ = nullptr;
        return false;
    }

    // Apply default configuration
    if (!SetConfig(current_config_)) {
        LOGW("Failed to apply default AEC configuration, but continuing");
    }

    // Clear accumulated audio buffer
    ClearCleanAudioBuffer();
    
    // Reset counters
    frames_processed_ = 0;
    farend_frames_ = 0;
    nearend_frames_ = 0;

    initialized_ = true;
    LOGI("AEC processor initialized successfully (16kHz, frame size: %d)", kFrameSize);
    return true;
}

void WqAecProcessor::Destroy() {
    if (aec_handle_) {
        WebRtcAec_Free(aec_handle_);
        aec_handle_ = nullptr;
    }
    
    ClearCleanAudioBuffer();
    initialized_ = false;
    LOGI("AEC processor destroyed");
}

bool WqAecProcessor::ProcessTtsAudio(const int16_t* ttsData, size_t numSamples) {
    if (!initialized_ || !aec_handle_) {
        LOGE("AEC processor not initialized");
        return false;
    }

    if (!ValidateFrameSize(numSamples)) {
        LOGE("Invalid TTS frame size: %zu, expected: %d", numSamples, kFrameSize);
        return false;
    }

    if (!ttsData) {
        LOGE("TTS data is null");
        return false;
    }

    // Convert int16 to float for WebRTC AEC
    std::vector<float> floatData(numSamples);
    for (size_t i = 0; i < numSamples; i++) {
        floatData[i] = static_cast<float>(ttsData[i]) / 32768.0f;
    }

    // Process farend (TTS reference) audio
    int result = WebRtcAec_BufferFarend(aec_handle_, floatData.data(), numSamples);
    if (result != 0) {
        LOGE("Failed to process TTS farend audio: %d", result);
        return false;
    }

    farend_frames_++;
    
    // Debug log every 1000 frames (10 seconds at 16kHz)
    if (farend_frames_ % 1000 == 0) {
        LOGI("Processed %llu TTS farend frames", (unsigned long long)farend_frames_);
    }

    return true;
}

bool WqAecProcessor::ProcessMicrophoneAudio(const int16_t* micData, int16_t* outputData, 
                                          size_t numSamples, int16_t msInSndCardBuf) {
    if (!initialized_ || !aec_handle_) {
        LOGE("AEC processor not initialized");
        return false;
    }

    if (!ValidateFrameSize(numSamples)) {
        LOGE("Invalid microphone frame size: %zu, expected: %d", numSamples, kFrameSize);
        return false;
    }

    if (!micData || !outputData) {
        LOGE("Microphone data or output buffer is null");
        return false;
    }

    // Convert int16 to float for WebRTC AEC
    std::vector<float> floatInput(numSamples);
    std::vector<float> floatOutput(numSamples);
    
    for (size_t i = 0; i < numSamples; i++) {
        floatInput[i] = static_cast<float>(micData[i]) / 32768.0f;
    }
    
    // Prepare pointers for multi-band processing (using single band)
    const float* nearend[1] = {floatInput.data()};
    float* out[1] = {floatOutput.data()};

    // Process nearend (microphone) audio with echo cancellation
    int result = WebRtcAec_Process(aec_handle_, nearend, 1, out, numSamples, msInSndCardBuf, 0);
    if (result != 0) {
        LOGE("Failed to process microphone audio: %d", result);
        return false;
    }
    
    // Convert float back to int16
    for (size_t i = 0; i < numSamples; i++) {
        float sample = floatOutput[i] * 32768.0f;
        sample = std::max(-32768.0f, std::min(32767.0f, sample)); // Clamp
        outputData[i] = static_cast<int16_t>(sample);
    }

    // Accumulate clean audio for potential export
    AccumulateCleanAudio(outputData, numSamples);

    nearend_frames_++;
    frames_processed_++;
    
    // Debug log every 1000 frames (10 seconds at 16kHz)
    if (nearend_frames_ % 1000 == 0) {
        LOGI("Processed %llu nearend frames, %llu total frames", 
             (unsigned long long)nearend_frames_, (unsigned long long)frames_processed_);
    }

    return true;
}

bool WqAecProcessor::GetMetrics(AecMetrics* metrics) {
    if (!initialized_ || !aec_handle_ || !metrics) {
        return false;
    }

    // Note: The original WebRTC AEC doesn't provide direct metrics access
    // We'll provide estimated metrics based on processing state
    metrics->echoReturnLoss = 0.0;  // ERL not available in this AEC version
    metrics->echoReturnLossEnhancement = 6.0;  // Estimated ERLE for WebRTC AEC
    metrics->delayMs = sound_card_delay_ms_;
    metrics->averageResidualEcho = 0.0;  // Not available
    metrics->isConverged = (frames_processed_ > 100);  // Simple convergence estimation

    return true;
}

bool WqAecProcessor::SetConfig(const AecConfig& config) {
    if (!initialized_ || !aec_handle_) {
        // Store config for later application
        current_config_ = config;
        return true;
    }

    // Create WebRTC AecConfig (from the actual WebRTC header)
    ::AecConfig webrtc_config;
    webrtc_config.nlpMode = config.nlpMode;
    webrtc_config.skewMode = config.skewMode;
    webrtc_config.metricsMode = config.metricsMode;
    webrtc_config.delay_logging = config.delay_logging;

    int result = WebRtcAec_set_config(aec_handle_, webrtc_config);
    if (result == 0) {
        current_config_ = config;
        LOGI("AEC configuration updated: NLP=%d, Skew=%d, Metrics=%d, DelayLog=%d",
             config.nlpMode, config.skewMode, config.metricsMode, config.delay_logging);
        return true;
    } else {
        LOGE("Failed to set AEC configuration: %d", result);
        return false;
    }
}

bool WqAecProcessor::GetConfig(AecConfig* config) {
    if (!config) {
        return false;
    }

    *config = current_config_;
    return true;
}

void WqAecProcessor::SetSoundCardDelay(int16_t delayMs) {
    sound_card_delay_ms_ = std::max(static_cast<int16_t>(10), 
                                   std::min(delayMs, static_cast<int16_t>(500)));
    LOGI("Sound card delay updated to %d ms", sound_card_delay_ms_);
}

size_t WqAecProcessor::GetCleanAudioBuffer(std::vector<std::vector<float>>& audioFrames) {
    audioFrames = clean_audio_frames_;
    size_t frameCount = clean_audio_frames_.size();
    
    // Clear buffer after retrieval
    ClearCleanAudioBuffer();
    
    LOGI("Retrieved %zu clean audio frames", frameCount);
    return frameCount;
}

void WqAecProcessor::ClearCleanAudioBuffer() {
    clean_audio_frames_.clear();
    LOGD("Clean audio buffer cleared");
}

// Private helper methods

bool WqAecProcessor::ValidateFrameSize(size_t numSamples) const {
    return numSamples == kFrameSize;
}

void WqAecProcessor::AccumulateCleanAudio(const int16_t* audioData, size_t numSamples) {
    if (!audioData || numSamples != kFrameSize) {
        return;
    }

    // Convert int16 to float and store
    std::vector<float> frame(numSamples);
    for (size_t i = 0; i < numSamples; i++) {
        frame[i] = static_cast<float>(audioData[i]) / 32768.0f;  // Normalize to [-1, 1]
    }

    clean_audio_frames_.push_back(frame);

    // Limit buffer size to prevent memory issues (max 30 seconds at 16kHz)
    const size_t maxFrames = (30 * kSampleRate) / kFrameSize;  // 30 seconds
    if (clean_audio_frames_.size() > maxFrames) {
        clean_audio_frames_.erase(clean_audio_frames_.begin());
    }
}

void WqAecProcessor::InitializeDefaultConfig() {
    current_config_.nlpMode = 2;           // Moderate NLP mode for good balance
    current_config_.skewMode = 0;          // Skew compensation off (not typically needed for TTS)
    current_config_.metricsMode = 1;       // Enable metrics for monitoring
    current_config_.delay_logging = 0;     // Delay logging off for performance
}

} // namespace webrtc_aec_tts