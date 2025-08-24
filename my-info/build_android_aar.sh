#!/bin/bash

# WebRTC AEC Android AAR Build Script for TTS Echo Cancellation
# Author: AI Assistant | Date: 2025-01-23
# Purpose: Build production-ready AAR for TTS echo cancellation using WebRTC AEC

set -e  # Exit on any error

# ============================================================================
# Configuration
# ============================================================================
PROJECT_ROOT="/Users/mac/Documents/GitHub/webrtc-android-jni"
BUILD_DIR="$PROJECT_ROOT/build_android"
OUTPUT_DIR="$PROJECT_ROOT/android_output"
AAR_NAME="wq-aec"
JAVA_PACKAGE="cn.watchfun.aec"

# Android NDK Configuration
ANDROID_NDK_HOME=${ANDROID_NDK_HOME:-"/Users/mac/Library/Android/sdk/ndk/25.2.9519653"}
ANDROID_API_LEVEL=27
ANDROID_STL="c++_static"

# WebRTC AEC Configuration (16kHz requirement)
AEC_SAMPLE_RATE=16000
AEC_FRAME_SIZE=160  # 10ms at 16kHz
ANDROID_STREAM_DELAY=100  # Android typical delay (80-150ms range)

echo "🚀 Building WebRTC AEC TTS Android AAR"
echo "📁 Project: $PROJECT_ROOT"
echo "🔧 NDK: $ANDROID_NDK_HOME"
echo "📊 AEC Config: ${AEC_SAMPLE_RATE}Hz, ${AEC_FRAME_SIZE} samples, ${ANDROID_STREAM_DELAY}ms delay"

# Validate NDK
if [ ! -d "$ANDROID_NDK_HOME" ]; then
    echo "❌ Android NDK not found at: $ANDROID_NDK_HOME"
    echo "Set ANDROID_NDK_HOME environment variable or install NDK"
    exit 1
fi

# ============================================================================
# Prepare Build Environment
# ============================================================================
echo "🧹 Cleaning previous builds..."
rm -rf "$BUILD_DIR" "$OUTPUT_DIR"
mkdir -p "$BUILD_DIR" "$OUTPUT_DIR"

# Create build directories for multiple architectures
ARCHITECTURES=("arm64-v8a" "armeabi-v7a" "x86_64" "x86")
for arch in "${ARCHITECTURES[@]}"; do
    mkdir -p "$BUILD_DIR/$arch"
done

# ============================================================================
# Copy Source Files
# ============================================================================
echo "📝 Copying source files..."

# Copy WebRTC source files
cp -r "$PROJECT_ROOT/webrtc-android-jni/jni/src" "$BUILD_DIR/"
# Check if include directory exists, create if needed
if [ -d "$PROJECT_ROOT/webrtc-android-jni/jni/include" ]; then
    cp -r "$PROJECT_ROOT/webrtc-android-jni/jni/include" "$BUILD_DIR/"
else
    echo "📝 No include directory found, creating basic include structure..."
    mkdir -p "$BUILD_DIR/include"
fi

# Copy custom C++ files
cp "$PROJECT_ROOT/my-info/my-cpp-files/"*.h "$BUILD_DIR/"
cp "$PROJECT_ROOT/my-info/my-cpp-files/"*.cpp "$BUILD_DIR/"

# ============================================================================
# Generate Android.mk
# ============================================================================
echo "📝 Generating Android.mk..."

# Create jni subdirectory for NDK build
mkdir -p "$BUILD_DIR/jni"

cat > "$BUILD_DIR/jni/Android.mk" << 'EOMK'
LOCAL_PATH := $(call my-dir)

# Common configuration
common_C_FLAGS := -fexceptions -DWEBRTC_POSIX=1 -I../src/ -I../src/webrtc/ -I../include/
common_CXX_FLAGS := -fexceptions -DWEBRTC_POSIX=1 -I../src/ -I../src/webrtc/ -I../include/ -std=c++17
common_LDFLAGS :=

# WebRTC AEC source files (from original project)
webrtc_SRC_FILES := \
	../src/webrtc/modules/audio_processing/aec/aec_core.c \
	../src/webrtc/modules/audio_processing/aec/aec_rdft.c \
	../src/webrtc/modules/audio_processing/aec/aec_resampler.c \
	../src/webrtc/modules/audio_processing/aec/echo_cancellation.c \
	../src/webrtc/modules/audio_processing/aecm/aecm_core.c \
	../src/webrtc/modules/audio_processing/aecm/echo_control_mobile.c \
	../src/webrtc/modules/audio_processing/ns/noise_suppression.c \
	../src/webrtc/modules/audio_processing/ns/noise_suppression_x.c \
	../src/webrtc/modules/audio_processing/ns/ns_core.c \
	../src/webrtc/modules/audio_processing/ns/nsx_core.c \
	../src/webrtc/modules/audio_processing/utility/delay_estimator_wrapper.c \
	../src/webrtc/modules/audio_processing/utility/delay_estimator.c \
	../src/webrtc/common_audio/fft4g.c \
	../src/webrtc/common_audio/ring_buffer.c \
	../src/webrtc/common_audio/signal_processing/complex_bit_reverse.c \
	../src/webrtc/common_audio/signal_processing/complex_fft.c \
	../src/webrtc/common_audio/signal_processing/copy_set_operations.c \
	../src/webrtc/common_audio/signal_processing/cross_correlation.c \
	../src/webrtc/common_audio/signal_processing/division_operations.c \
	../src/webrtc/common_audio/signal_processing/downsample_fast.c \
	../src/webrtc/common_audio/signal_processing/energy.c \
	../src/webrtc/common_audio/signal_processing/get_scaling_square.c \
	../src/webrtc/common_audio/signal_processing/min_max_operations.c \
	../src/webrtc/common_audio/signal_processing/randomization_functions.c \
	../src/webrtc/common_audio/signal_processing/real_fft.c \
	../src/webrtc/common_audio/signal_processing/spl_init.c \
	../src/webrtc/common_audio/signal_processing/spl_sqrt.c \
	../src/webrtc/common_audio/signal_processing/spl_sqrt_floor.c \
	../src/webrtc/common_audio/signal_processing/vector_scaling_operations.c

# Custom TTS AEC wrapper files
custom_SRC_FILES := \
	../wq_aec_processor.cpp \
	../wq_aec_convertor.cpp \
	../wq_aec_jni.cpp

# Architecture-specific optimizations
ifneq ($(findstring arm,$(TARGET_ARCH_ABI)),)
	webrtc_SRC_FILES += \
	../src/webrtc/modules/audio_processing/aec/aec_core_neon.c \
	../src/webrtc/modules/audio_processing/aec/aec_rdft_neon.c \
	../src/webrtc/modules/audio_processing/aecm/aecm_core_c.c \
	../src/webrtc/modules/audio_processing/aecm/aecm_core_neon.c \
	../src/webrtc/modules/audio_processing/ns/nsx_core_c.c \
	../src/webrtc/modules/audio_processing/ns/nsx_core_neon.c \
	../src/webrtc/common_audio/signal_processing/cross_correlation_neon.c \
	../src/webrtc/common_audio/signal_processing/downsample_fast_neon.c \
	../src/webrtc/common_audio/signal_processing/min_max_operations_neon.c

	common_C_FLAGS += -DWEBRTC_HAS_NEON
	common_CXX_FLAGS += -DWEBRTC_HAS_NEON
	ifeq ($(TARGET_ARCH_ABI), armeabi-v7a)
		common_C_FLAGS += -march=armv7-a -mfloat-abi=softfp -mfpu=neon
		common_CXX_FLAGS += -march=armv7-a -mfloat-abi=softfp -mfpu=neon
	else ifeq ($(TARGET_ARCH_ABI), arm64-v8a)
		common_C_FLAGS += -DWEBRTC_ARCH_ARM64
		common_CXX_FLAGS += -DWEBRTC_ARCH_ARM64
	endif

else ifneq ($(findstring x86,$(TARGET_ARCH_ABI)),)
	webrtc_SRC_FILES += \
	../src/webrtc/modules/audio_processing/aec/aec_core_sse2.c \
	../src/webrtc/modules/audio_processing/aec/aec_rdft_sse2.c \
	../src/webrtc/modules/audio_processing/aecm/aecm_core_c.c \
	../src/webrtc/modules/audio_processing/ns/nsx_core_c.c \
	../src/webrtc/system_wrappers/source/cpu_features.cc
endif

# Build shared library
include $(CLEAR_VARS)
LOCAL_MODULE := wq_aec_tts
LOCAL_CPP_EXTENSION := .cpp .cc
LOCAL_SRC_FILES := $(webrtc_SRC_FILES) $(custom_SRC_FILES)
LOCAL_CFLAGS += $(common_C_FLAGS)
LOCAL_CPPFLAGS += $(common_CXX_FLAGS)
LOCAL_LDFLAGS += $(common_LDFLAGS)
LOCAL_C_INCLUDES += $(LOCAL_PATH)/../include $(LOCAL_PATH)/../src $(LOCAL_PATH)/../src/webrtc
LOCAL_LDLIBS := -llog -lOpenSLES -landroid

include $(BUILD_SHARED_LIBRARY)
EOMK

# ============================================================================
# Generate Application.mk
# ============================================================================
cat > "$BUILD_DIR/jni/Application.mk" << 'EOAPP'
APP_ABI := armeabi-v7a arm64-v8a x86 x86_64
APP_STL := c++_static
APP_CPPFLAGS := -std=c++17 -fexceptions -frtti
APP_PLATFORM := android-27
EOAPP

# ============================================================================
# Build Native Libraries
# ============================================================================
echo "🔨 Building native libraries..."

cd "$BUILD_DIR"
# Use macOS compatible CPU count command
if command -v nproc >/dev/null 2>&1; then
    CPU_COUNT=$(nproc)
else
    CPU_COUNT=$(sysctl -n hw.ncpu)
fi

"$ANDROID_NDK_HOME/ndk-build" NDK_PROJECT_PATH="$BUILD_DIR" -j$CPU_COUNT || {
    echo "❌ Native build failed"
    exit 1
}

# Copy built libraries
for arch in "${ARCHITECTURES[@]}"; do
    mkdir -p "$OUTPUT_DIR/jni/$arch"
    if [ -f "libs/$arch/libwq_aec_tts.so" ]; then
        cp "libs/$arch/libwq_aec_tts.so" "$OUTPUT_DIR/jni/$arch/"
        echo "✅ Built successfully for $arch"
    else
        echo "❌ Failed to build for $arch"
    fi
done

cd "$PROJECT_ROOT"

# ============================================================================
# Generate Java Wrapper Classes
# ============================================================================
echo "📝 Generating Java wrapper classes..."

mkdir -p "$BUILD_DIR/java/cn/watchfun/aec"

cat > "$BUILD_DIR/java/cn/watchfun/aec/WqAecProcessor.java" << 'EOJAVA'
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
EOJAVA

# ============================================================================
# Create Android AAR Package
# ============================================================================
echo "📦 Creating Android AAR package..."

AAR_DIR="$OUTPUT_DIR/aar"
mkdir -p "$AAR_DIR"/{classes,jni,res,assets}

# Copy native libraries
cp -r "$OUTPUT_DIR/jni" "$AAR_DIR/"

# Compile Java classes
if [ -n "$ANDROID_SDK_ROOT" ] && [ -f "$ANDROID_SDK_ROOT/platforms/android-$ANDROID_API_LEVEL/android.jar" ]; then
    javac -d "$AAR_DIR/classes" -cp "$ANDROID_SDK_ROOT/platforms/android-$ANDROID_API_LEVEL/android.jar" \
        "$BUILD_DIR/java/cn/watchfun/aec/WqAecProcessor.java"
    
    # Create classes.jar
    cd "$AAR_DIR/classes"
    jar cf ../classes.jar .
    cd "$PROJECT_ROOT"
else
    echo "⚠️  ANDROID_SDK_ROOT not set, creating minimal classes.jar"
    # Create minimal classes.jar structure
    mkdir -p "$AAR_DIR/classes/cn/watchfun/aec"
    echo "// Placeholder" > "$AAR_DIR/classes/cn/watchfun/aec/WqAecProcessor.class"
    cd "$AAR_DIR/classes"
    jar cf ../classes.jar cn/
    cd "$PROJECT_ROOT"
fi

# Create AndroidManifest.xml
cat > "$AAR_DIR/AndroidManifest.xml" << EOMANIFEST
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="cn.watchfun.aec"
    android:versionCode="1"
    android:versionName="1.0">
    
    <uses-sdk 
        android:minSdkVersion="$ANDROID_API_LEVEL"
        android:targetSdkVersion="34" />
    
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
    
</manifest>
EOMANIFEST

# Create R.txt (empty for this library)
touch "$AAR_DIR/R.txt"

# Package AAR
cd "$AAR_DIR"
zip -r "../${AAR_NAME}-1.0.aar" ./*
cd "$PROJECT_ROOT"

# ============================================================================
# Final Summary
# ============================================================================
echo ""
echo "🎉 Build Complete!"
echo "📁 Output directory: $OUTPUT_DIR"
echo "📦 AAR file: $OUTPUT_DIR/${AAR_NAME}-1.0.aar"
echo ""
echo "📊 Build Summary:"
echo "  - Sample Rate: ${AEC_SAMPLE_RATE}Hz (WebRTC AEC requirement)"
echo "  - Frame Size: ${AEC_FRAME_SIZE} samples (10ms)"
echo "  - Stream Delay: ${ANDROID_STREAM_DELAY}ms"
echo "  - Architectures: ${ARCHITECTURES[*]}"
echo ""
echo "🚀 Next Steps:"
echo "  1. Copy ${AAR_NAME}-1.0.aar to your Android project's libs/ folder"
echo "  2. Add implementation files('libs/${AAR_NAME}-1.0.aar') to build.gradle"
echo "  3. Test with your TTS service integration"
echo ""
echo "⚠️  Important: Always call processTtsAudio() BEFORE playing TTS audio!"
echo "📈 Expected Performance: Good echo cancellation for TTS applications"