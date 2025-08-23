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
