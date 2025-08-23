#include "wq_aec_convertor.h"
#include <cstring>
#include <algorithm>
#include <cmath>
#include <android/log.h>

#define TAG "WebRTC_AEC_Convertor"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace webrtc_aec_tts {

int WqAecConvertor::convertCleanAudioToWAV(const std::vector<std::vector<float>>& audioFrames,
                                          int inputSampleRate,
                                          uint8_t** outputData,
                                          size_t* outputSize,
                                          int outputSampleRate) {
    if (audioFrames.empty() || !outputData || !outputSize) {
        LOGE("Invalid parameters for WAV conversion");
        return -1;
    }

    // Flatten audio frames into a single vector
    std::vector<float> allAudio;
    for (const auto& frame : audioFrames) {
        allAudio.insert(allAudio.end(), frame.begin(), frame.end());
    }

    if (allAudio.empty()) {
        LOGE("No audio data to convert");
        return -1;
    }

    LOGI("Converting %zu samples from %dHz to %dHz WAV format", 
         allAudio.size(), inputSampleRate, outputSampleRate);

    // Resample if necessary
    std::vector<float> resampledAudio;
    if (inputSampleRate != outputSampleRate) {
        resampledAudio = resampleAudio(allAudio, inputSampleRate, outputSampleRate);
    } else {
        resampledAudio = allAudio;
    }

    // Convert to 16-bit PCM
    std::vector<int16_t> pcmData = floatToPcm16(resampledAudio);

    // Calculate sizes
    uint32_t dataSize = pcmData.size() * sizeof(int16_t);
    uint32_t totalSize = sizeof(WAVHeader) + dataSize;

    // Allocate output buffer
    uint8_t* buffer = static_cast<uint8_t*>(malloc(totalSize));
    if (!buffer) {
        LOGE("Failed to allocate WAV buffer");
        return -1;
    }

    // Create WAV header
    WAVHeader header = createWAVHeader(outputSampleRate, 1, 16, dataSize);

    // Copy header and data
    memcpy(buffer, &header, sizeof(WAVHeader));
    memcpy(buffer + sizeof(WAVHeader), pcmData.data(), dataSize);

    *outputData = buffer;
    *outputSize = totalSize;

    LOGI("WAV conversion completed: %zu total bytes, %zu audio samples at %dHz", 
         static_cast<size_t>(totalSize), pcmData.size(), outputSampleRate);
    return 0;
}

int WqAecConvertor::convertCleanAudioToPCM(const std::vector<std::vector<float>>& audioFrames,
                                          int inputSampleRate,
                                          uint8_t** outputData,
                                          size_t* outputSize,
                                          int outputSampleRate) {
    if (audioFrames.empty() || !outputData || !outputSize) {
        LOGE("Invalid parameters for PCM conversion");
        return -1;
    }

    // Flatten audio frames into a single vector
    std::vector<float> allAudio;
    for (const auto& frame : audioFrames) {
        allAudio.insert(allAudio.end(), frame.begin(), frame.end());
    }

    if (allAudio.empty()) {
        LOGE("No audio data to convert");
        return -1;
    }

    LOGI("Converting %zu samples from %dHz to %dHz PCM format", 
         allAudio.size(), inputSampleRate, outputSampleRate);

    // Resample if necessary
    std::vector<float> resampledAudio;
    if (inputSampleRate != outputSampleRate) {
        resampledAudio = resampleAudio(allAudio, inputSampleRate, outputSampleRate);
    } else {
        resampledAudio = allAudio;
    }

    // Convert to 16-bit PCM
    std::vector<int16_t> pcmData = floatToPcm16(resampledAudio);

    // Calculate size
    size_t dataSize = pcmData.size() * sizeof(int16_t);

    // Allocate output buffer
    uint8_t* buffer = static_cast<uint8_t*>(malloc(dataSize));
    if (!buffer) {
        LOGE("Failed to allocate PCM buffer");
        return -1;
    }

    // Copy PCM data
    memcpy(buffer, pcmData.data(), dataSize);

    *outputData = buffer;
    *outputSize = dataSize;

    LOGI("PCM conversion completed: %zu bytes, %zu samples at %dHz", 
         dataSize, pcmData.size(), outputSampleRate);
    return 0;
}

std::vector<float> WqAecConvertor::resampleAudio(const std::vector<float>& inputAudio,
                                                int inputSampleRate,
                                                int outputSampleRate) {
    if (inputAudio.empty() || inputSampleRate <= 0 || outputSampleRate <= 0) {
        return std::vector<float>();
    }

    if (inputSampleRate == outputSampleRate) {
        return inputAudio;  // No resampling needed
    }

    double ratio = static_cast<double>(outputSampleRate) / inputSampleRate;
    size_t outputLength = static_cast<size_t>(inputAudio.size() * ratio);
    std::vector<float> outputAudio(outputLength);

    // Linear interpolation resampling
    for (size_t i = 0; i < outputLength; i++) {
        double sourceIndex = i / ratio;
        size_t index1 = static_cast<size_t>(sourceIndex);
        size_t index2 = std::min(index1 + 1, inputAudio.size() - 1);
        double fraction = sourceIndex - index1;

        if (index1 < inputAudio.size()) {
            outputAudio[i] = static_cast<float>(
                inputAudio[index1] * (1.0 - fraction) + 
                inputAudio[index2] * fraction
            );
        }
    }

    return outputAudio;
}

std::vector<int16_t> WqAecConvertor::floatToPcm16(const std::vector<float>& floatSamples) {
    std::vector<int16_t> pcmSamples(floatSamples.size());

    for (size_t i = 0; i < floatSamples.size(); i++) {
        // Clamp and convert float [-1, 1] to int16 [-32768, 32767]
        float clampedValue = clamp(floatSamples[i], -1.0f, 1.0f);
        pcmSamples[i] = static_cast<int16_t>(clampedValue * 32767.0f);
    }

    return pcmSamples;
}

std::vector<float> WqAecConvertor::pcm16ToFloat(const std::vector<int16_t>& pcmSamples) {
    std::vector<float> floatSamples(pcmSamples.size());

    for (size_t i = 0; i < pcmSamples.size(); i++) {
        // Convert int16 [-32768, 32767] to float [-1, 1]
        floatSamples[i] = static_cast<float>(pcmSamples[i]) / 32767.0f;
    }

    return floatSamples;
}

WqAecConvertor::WAVHeader WqAecConvertor::createWAVHeader(uint32_t sampleRate, 
                                                         uint16_t numChannels,
                                                         uint16_t bitsPerSample, 
                                                         uint32_t dataSize) {
    WAVHeader header;

    // RIFF header
    memcpy(header.riff, "RIFF", 4);
    header.fileSize = sizeof(WAVHeader) - 8 + dataSize;
    memcpy(header.wave, "WAVE", 4);

    // Format chunk
    memcpy(header.fmt, "fmt ", 4);
    header.fmtSize = 16;
    header.audioFormat = 1;  // PCM
    header.numChannels = numChannels;
    header.sampleRate = sampleRate;
    header.byteRate = sampleRate * numChannels * bitsPerSample / 8;
    header.blockAlign = numChannels * bitsPerSample / 8;
    header.bitsPerSample = bitsPerSample;

    // Data chunk
    memcpy(header.data, "data", 4);
    header.dataSize = dataSize;

    return header;
}

float WqAecConvertor::clamp(float value, float min, float max) {
    return std::max(min, std::min(value, max));
}

} // namespace webrtc_aec_tts