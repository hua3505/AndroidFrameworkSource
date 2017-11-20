LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := tests

LOCAL_MODULE := CppDebugWaitAttach

LOCAL_SRC_FILES := \
	WaitAttach.cpp \
	simple.rs

LOCAL_STATIC_LIBRARIES := libRScpp_static
LOCAL_CFLAGS := --std=c++11

LOCAL_RENDERSCRIPT_FLAGS := -g -O0 -target-api 0

LOCAL_LDFLAGS := \
	-ldl \
	-llog

intermediates := $(call intermediates-dir-for,STATIC_LIBRARIES,libRS,TARGET,)

LOCAL_C_INCLUDES += frameworks/rs/cpp
LOCAL_C_INCLUDES += frameworks/rs
LOCAL_C_INCLUDES += $(intermediates)

include $(BUILD_EXECUTABLE)

include $(CLEAR_VARS)

LOCAL_MODULE := CppNoDebugWaitAttach

LOCAL_SRC_FILES := \
	WaitAttach.cpp \
	simple.rs

LOCAL_STATIC_LIBRARIES := libRScpp_static
LOCAL_CFLAGS := --std=c++11

LOCAL_RENDERSCRIPT_FLAGS := -O0 -target-api 0

LOCAL_LDFLAGS := \
	-ldl \
	-llog

intermediates := $(call intermediates-dir-for,STATIC_LIBRARIES,libRS,TARGET,)

LOCAL_C_INCLUDES += frameworks/rs/cpp
LOCAL_C_INCLUDES += frameworks/rs
LOCAL_C_INCLUDES += $(intermediates)

include $(BUILD_EXECUTABLE)

