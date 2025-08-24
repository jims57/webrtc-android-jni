#include <jni.h>
#include <memory>
#include <android/log.h>
#include "wq_aec_processor.h"
#include "wq_aec_convertor.h"

// WebRTC AEC TTS echo cancellation JNI implementation
// This file provides the JNI bridge between Java and C++ for the TTS AEC processor

#define TAG "WebRTC_AEC_TTS_JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Global processor instance
static std::unique_ptr<webrtc_aec_tts::WqAecProcessor> g_processor;

extern "C" {

// ========== Core AEC JNI Methods ==========

/**
 * Initialize AEC processor
 * @return true if initialization successful
 */
JNIEXPORT jboolean JNICALL
Java_cn_watchfun_aec_WqAecProcessor_nativeInitialize(JNIEnv *env, jobject thiz) {
    g_processor = std::make_unique<webrtc_aec_tts::WqAecProcessor>();
    return g_processor->Initialize() ? JNI_TRUE : JNI_FALSE;
}

/**
 * Clean up and destroy AEC processor
 */
JNIEXPORT void JNICALL
Java_cn_watchfun_aec_WqAecProcessor_nativeDestroy(JNIEnv *env, jobject thiz) {
    g_processor.reset();
}

/**
 * Process TTS audio (reference signal)
 * @param tts_data TTS audio samples (must be 160 samples for 16kHz)
 * @return true if processing successful
 */
JNIEXPORT jboolean JNICALL
Java_cn_watchfun_aec_WqAecProcessor_nativeProcessTtsAudio(JNIEnv *env, jobject thiz, jshortArray tts_data) {
    if (!g_processor) return JNI_FALSE;
    
    jsize length = env->GetArrayLength(tts_data);
    if (length != webrtc_aec_tts::WqAecProcessor::kFrameSize) return JNI_FALSE;
    
    jshort* data = env->GetShortArrayElements(tts_data, nullptr);
    bool result = g_processor->ProcessTtsAudio(
        reinterpret_cast<const int16_t*>(data), length);
    env->ReleaseShortArrayElements(tts_data, data, JNI_ABORT);
    
    return result ? JNI_TRUE : JNI_FALSE;
}

/**
 * Process microphone audio and remove echo
 * @param mic_data Microphone input samples (must be 160 samples for 16kHz)
 * @param output_data Output buffer for processed audio (must be 160 samples)
 * @param sound_card_delay Sound card buffer delay in ms (default: 100ms)
 * @return true if processing successful
 */
JNIEXPORT jboolean JNICALL
Java_cn_watchfun_aec_WqAecProcessor_nativeProcessMicrophoneAudio(JNIEnv *env, jobject thiz, 
                                                          jshortArray mic_data, jshortArray output_data, 
                                                          jint sound_card_delay) {
    if (!g_processor) return JNI_FALSE;
    
    jsize length = env->GetArrayLength(mic_data);
    if (length != webrtc_aec_tts::WqAecProcessor::kFrameSize) return JNI_FALSE;
    
    jshort* input = env->GetShortArrayElements(mic_data, nullptr);
    jshort* output = env->GetShortArrayElements(output_data, nullptr);
    
    bool result = g_processor->ProcessMicrophoneAudio(
        reinterpret_cast<const int16_t*>(input), 
        reinterpret_cast<int16_t*>(output), length, 
        static_cast<int16_t>(sound_card_delay));
    
    env->ReleaseShortArrayElements(mic_data, input, JNI_ABORT);
    env->ReleaseShortArrayElements(output_data, output, 0);
    
    return result ? JNI_TRUE : JNI_FALSE;
}

/**
 * Get current AEC performance metrics
 * @return double array: [echo_return_loss, echo_return_loss_enhancement, delay_ms, is_converged]
 */
JNIEXPORT jdoubleArray JNICALL
Java_cn_watchfun_aec_WqAecProcessor_nativeGetMetrics(JNIEnv *env, jobject thiz) {
    if (!g_processor) return nullptr;
    
    webrtc_aec_tts::WqAecProcessor::AecMetrics metrics;
    if (!g_processor->GetMetrics(&metrics)) {
        return nullptr;
    }
    
    jdoubleArray result = env->NewDoubleArray(4);
    double metricsArray[] = {
        metrics.echoReturnLoss, 
        metrics.echoReturnLossEnhancement, 
        static_cast<double>(metrics.delayMs),
        metrics.isConverged ? 1.0 : 0.0
    };
    env->SetDoubleArrayRegion(result, 0, 4, metricsArray);
    return result;
}

/**
 * Set AEC configuration parameters
 * @param nlp_mode NLP mode (0=off, 1=mild, 2=moderate, 3=aggressive)
 * @param skew_mode Skew compensation (0=off, 1=on)  
 * @param metrics_mode Metrics collection (0=off, 1=on)
 * @param delay_logging Delay logging (0=off, 1=on)
 * @return true if configuration applied successfully
 */
JNIEXPORT jboolean JNICALL
Java_cn_watchfun_aec_WqAecProcessor_nativeSetConfig(JNIEnv *env, jobject thiz, 
                                            jint nlp_mode, jint skew_mode, 
                                            jint metrics_mode, jint delay_logging) {
    if (!g_processor) return JNI_FALSE;
    
    AecConfig config;
    config.nlpMode = static_cast<int16_t>(nlp_mode);
    config.skewMode = static_cast<int16_t>(skew_mode);
    config.metricsMode = static_cast<int16_t>(metrics_mode);
    config.delay_logging = static_cast<int16_t>(delay_logging);
    
    return g_processor->SetConfig(config) ? JNI_TRUE : JNI_FALSE;
}

/**
 * Get current AEC configuration
 * @return int array: [nlp_mode, skew_mode, metrics_mode, delay_logging]
 */
JNIEXPORT jintArray JNICALL
Java_cn_watchfun_aec_WqAecProcessor_nativeGetConfig(JNIEnv *env, jobject thiz) {
    if (!g_processor) return nullptr;
    
    AecConfig config;
    if (!g_processor->GetConfig(&config)) {
        return nullptr;
    }
    
    jintArray result = env->NewIntArray(4);
    int configArray[] = {
        config.nlpMode, 
        config.skewMode, 
        config.metricsMode,
        config.delay_logging
    };
    env->SetIntArrayRegion(result, 0, 4, configArray);
    return result;
}

/**
 * Update sound card buffer delay for optimal performance
 * @param delay_ms Delay in milliseconds (typically 80-150ms for Android)
 */
JNIEXPORT void JNICALL
Java_cn_watchfun_aec_WqAecProcessor_nativeSetSoundCardDelay(JNIEnv *env, jobject thiz, jint delay_ms) {
    if (g_processor) {
        g_processor->SetSoundCardDelay(static_cast<int16_t>(delay_ms));
    }
}

// ========== Clean Audio Conversion JNI Methods ==========

/**
 * Get clean audio buffer and convert to WAV format
 * @param outputSampleRate Desired output sample rate (default: 44100)
 * @return byte array containing WAV data, or null if error
 */
JNIEXPORT jbyteArray JNICALL
Java_cn_watchfun_aec_WqAecProcessor_nativeGetCleanAudioAsWAV(JNIEnv *env, jobject thiz, jint outputSampleRate) {
    if (!g_processor) return nullptr;
    
    // Get clean audio frames from processor buffer (buffer will be cleared)
    std::vector<std::vector<float>> audioFrames;
    size_t frameCount = g_processor->GetCleanAudioBuffer(audioFrames);
    
    if (frameCount == 0) {
        return nullptr; // No audio frames available
    }
    
    // Convert to WAV format
    uint8_t* wavData = nullptr;
    size_t wavSize = 0;
    int result = webrtc_aec_tts::WqAecConvertor::convertCleanAudioToWAV(
        audioFrames, webrtc_aec_tts::WqAecProcessor::kSampleRate, &wavData, &wavSize, outputSampleRate);
    
    if (result != 0 || !wavData || wavSize == 0) {
        if (wavData) free(wavData);
        return nullptr;
    }
    
    // Create Java byte array
    jbyteArray wavArray = env->NewByteArray(static_cast<jsize>(wavSize));
    if (!wavArray) {
        free(wavData);
        return nullptr;
    }
    
    env->SetByteArrayRegion(wavArray, 0, static_cast<jsize>(wavSize), 
                           reinterpret_cast<const jbyte*>(wavData));
    
    free(wavData);
    return wavArray;
}

/**
 * Get clean audio buffer and convert to PCM format
 * @param outputSampleRate Desired output sample rate (default: 44100)
 * @return byte array containing PCM data, or null if error
 */
JNIEXPORT jbyteArray JNICALL
Java_cn_watchfun_aec_WqAecProcessor_nativeGetCleanAudioAsPCM(JNIEnv *env, jobject thiz, jint outputSampleRate) {
    if (!g_processor) {
        LOGE("PCM: g_processor is null");
        return nullptr;
    }
    
    // Get clean audio frames from processor buffer
    std::vector<std::vector<float>> audioFrames;
    size_t frameCount = g_processor->GetCleanAudioBuffer(audioFrames);
    
    LOGI("PCM: Retrieved %zu frames from buffer", frameCount);
    
    if (frameCount == 0) {
        LOGI("PCM: No audio frames available");
        return nullptr; // No audio frames available
    }
    
    // Debug: Check first frame content
    if (!audioFrames.empty() && !audioFrames[0].empty()) {
        float firstSample = audioFrames[0][0];
        float lastSample = audioFrames[0][audioFrames[0].size()-1];
        LOGI("PCM: First frame samples: first=%.6f, last=%.6f, size=%zu", 
             firstSample, lastSample, audioFrames[0].size());
    }
    
    // Convert to PCM format
    uint8_t* pcmData = nullptr;
    size_t pcmSize = 0;
    int result = webrtc_aec_tts::WqAecConvertor::convertCleanAudioToPCM(
        audioFrames, webrtc_aec_tts::WqAecProcessor::kSampleRate, &pcmData, &pcmSize, outputSampleRate);
    
    LOGI("PCM: Conversion result=%d, pcmData=%p, pcmSize=%zu", result, pcmData, pcmSize);
    
    if (result != 0 || !pcmData || pcmSize == 0) {
        LOGE("PCM: Conversion failed - result=%d, data=%p, size=%zu", result, pcmData, pcmSize);
        if (pcmData) free(pcmData);
        return nullptr;
    }
    
    // Debug: Check first few PCM bytes
    int16_t* pcmSamples = reinterpret_cast<int16_t*>(pcmData);
    LOGI("PCM: First few samples: [%d, %d, %d, %d]", 
         pcmSamples[0], pcmSamples[1], pcmSamples[2], pcmSamples[3]);
    
    // Create Java byte array
    jbyteArray pcmArray = env->NewByteArray(static_cast<jsize>(pcmSize));
    if (!pcmArray) {
        LOGE("PCM: Failed to create Java byte array");
        free(pcmData);
        return nullptr;
    }
    
    env->SetByteArrayRegion(pcmArray, 0, static_cast<jsize>(pcmSize), 
                           reinterpret_cast<const jbyte*>(pcmData));
    
    LOGI("PCM: Successfully created byte array of size %zu", pcmSize);
    
    free(pcmData);
    return pcmArray;
}

/**
 * Clear the clean audio buffer without retrieving data
 */
JNIEXPORT void JNICALL
Java_cn_watchfun_aec_WqAecProcessor_nativeClearCleanAudioBuffer(JNIEnv *env, jobject thiz) {
    if (g_processor) {
        g_processor->ClearCleanAudioBuffer();
    }
}

} // extern "C"