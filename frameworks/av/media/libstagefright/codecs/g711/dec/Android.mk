LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES := \
        SoftG711.cpp

LOCAL_C_INCLUDES := \
        frameworks/av/media/libstagefright/include \
        frameworks/native/include/media/openmax

LOCAL_SHARED_LIBRARIES := \
        libstagefright libstagefright_omx libutils liblog

LOCAL_MODULE := libstagefright_soft_g711dec
LOCAL_MODULE_TAGS := optional

LOCAL_CFLAGS += -Werror
LOCAL_CLANG := true
LOCAL_SANITIZE := signed-integer-overflow unsigned-integer-overflow

include $(BUILD_SHARED_LIBRARY)
