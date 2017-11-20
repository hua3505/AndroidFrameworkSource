LOCAL_PATH := $(call my-dir)

# Component build
#######################################################################

include $(CLEAR_VARS)

LOCAL_SRC_FILES := \
    src/Engine.cpp \
    src/EngineInstance.cpp \

audio_policy_engine_includes_common := \
    $(LOCAL_PATH)/include \
    $(TOPDIR)frameworks/av/services/audiopolicy/engine/interface

LOCAL_CFLAGS += \
    -Wall \
    -Werror \
    -Wextra \

LOCAL_EXPORT_C_INCLUDE_DIRS := \
    $(audio_policy_engine_includes_common)

LOCAL_C_INCLUDES := \
    $(audio_policy_engine_includes_common) \
    $(TARGET_OUT_HEADERS)/hw \
    $(call include-path-for, frameworks-av) \
    $(call include-path-for, audio-utils) \
    $(call include-path-for, bionic) \
    $(TOPDIR)frameworks/av/services/audiopolicy/common/include

LOCAL_MULTILIB := $(AUDIOSERVER_MULTILIB)

LOCAL_MODULE := libaudiopolicyenginedefault
LOCAL_MODULE_TAGS := optional

LOCAL_STATIC_LIBRARIES := \
    libmedia_helper \
    libaudiopolicycomponents \
    libxml2

LOCAL_SHARED_LIBRARIES += \
    liblog \
    libcutils \
    libutils \

include $(BUILD_SHARED_LIBRARY)
