#pragma once

#include <stdint.h>
#include <vector>
#include <cstddef>

namespace webrtc_aec_tts {

/**
 * Audio format converter for WebRTC AEC processed audio
 * 
 * Provides utilities to convert between different audio formats
 * and export processed audio to standard formats like WAV and PCM.
 */
class WqAecConvertor {
public:
    /**
     * Convert clean audio frames to WAV format
     * 
     * @param audioFrames Vector of audio frames (normalized float [-1, 1])
     * @param inputSampleRate Input sample rate (16000 for WebRTC AEC)
     * @param outputData Pointer to output WAV data (caller must free)
     * @param outputSize Size of output WAV data in bytes
     * @param outputSampleRate Desired output sample rate (default: 44100)
     * @return 0 on success, error code on failure
     */
    static int convertCleanAudioToWAV(const std::vector<std::vector<float>>& audioFrames,
                                     int inputSampleRate,
                                     uint8_t** outputData,
                                     size_t* outputSize,
                                     int outputSampleRate = 44100);

    /**
     * Convert clean audio frames to PCM format
     * 
     * @param audioFrames Vector of audio frames (normalized float [-1, 1])
     * @param inputSampleRate Input sample rate (16000 for WebRTC AEC)
     * @param outputData Pointer to output PCM data (caller must free)
     * @param outputSize Size of output PCM data in bytes
     * @param outputSampleRate Desired output sample rate (default: 44100)
     * @return 0 on success, error code on failure
     */
    static int convertCleanAudioToPCM(const std::vector<std::vector<float>>& audioFrames,
                                     int inputSampleRate,
                                     uint8_t** outputData,
                                     size_t* outputSize,
                                     int outputSampleRate = 44100);

    /**
     * Resample audio data using linear interpolation
     * 
     * @param inputAudio Input audio samples (normalized float)
     * @param inputSampleRate Input sample rate
     * @param outputSampleRate Desired output sample rate
     * @return Resampled audio vector
     */
    static std::vector<float> resampleAudio(const std::vector<float>& inputAudio,
                                           int inputSampleRate,
                                           int outputSampleRate);

    /**
     * Convert float samples to 16-bit PCM
     * 
     * @param floatSamples Input float samples (normalized [-1, 1])
     * @return Vector of 16-bit PCM samples
     */
    static std::vector<int16_t> floatToPcm16(const std::vector<float>& floatSamples);

    /**
     * Convert 16-bit PCM to float samples
     * 
     * @param pcmSamples Input 16-bit PCM samples
     * @return Vector of normalized float samples [-1, 1]
     */
    static std::vector<float> pcm16ToFloat(const std::vector<int16_t>& pcmSamples);

private:
    // WAV file header structure
    struct WAVHeader {
        // RIFF header
        char riff[4];           // "RIFF"
        uint32_t fileSize;      // File size - 8
        char wave[4];           // "WAVE"
        
        // Format chunk
        char fmt[4];            // "fmt "
        uint32_t fmtSize;       // 16 for PCM
        uint16_t audioFormat;   // 1 for PCM
        uint16_t numChannels;   // 1 for mono
        uint32_t sampleRate;    // Sample rate
        uint32_t byteRate;      // Sample rate * channels * bits/8
        uint16_t blockAlign;    // Channels * bits/8
        uint16_t bitsPerSample; // 16 bits
        
        // Data chunk
        char data[4];           // "data"
        uint32_t dataSize;      // Data size in bytes
    };

    /**
     * Create WAV header for given audio parameters
     * 
     * @param sampleRate Sample rate in Hz
     * @param numChannels Number of channels (1 for mono)
     * @param bitsPerSample Bits per sample (16)
     * @param dataSize Size of audio data in bytes
     * @return WAV header structure
     */
    static WAVHeader createWAVHeader(uint32_t sampleRate, uint16_t numChannels, 
                                   uint16_t bitsPerSample, uint32_t dataSize);

    /**
     * Clamp float value to valid range
     * 
     * @param value Input float value
     * @param min Minimum allowed value
     * @param max Maximum allowed value
     * @return Clamped value
     */
    static float clamp(float value, float min, float max);
};

} // namespace webrtc_aec_tts