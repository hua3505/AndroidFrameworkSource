LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := tests

LOCAL_MODULE := libjniallocations

LOCAL_SRC_FILES := jniallocations.cpp allocs.rs

LOCAL_C_INCLUDES += $(JNI_H_INCLUDE)
LOCAL_C_INCLUDES += frameworks/rs/cpp
LOCAL_C_INCLUDES += frameworks/rs

LOCAL_CFLAGS := --std=c++11
LOCAL_RENDERSCRIPT_FLAGS := -g -O0 -target-api 0

LOCAL_CPP_FEATURES += exceptions

LOCAL_SHARED_LIBRARIES := libdl liblog
LOCAL_STATIC_LIBRARIES := libRScpp_static libcompiler_rt

LOCAL_SDK_VERSION := 23
LOCAL_NDK_STL_VARIANT := c++_static

include $(BUILD_SHARED_LIBRARY)

