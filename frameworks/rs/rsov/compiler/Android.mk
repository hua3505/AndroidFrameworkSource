#
# Copyright (C) 2016 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

LOCAL_PATH := $(call my-dir)
LLVM_ROOT_PATH := external/llvm
LIBBCC_ROOT_PATH := frameworks/compile/libbcc
LIBSPIRV_ROOT_PATH := external/spirv-llvm/lib/SPIRV

FORCE_RS2SPIRV_DEBUG_BUILD ?= false
RS2SPRIV_DEVICE_BUILD ?= true

RS2SPIRV_SOURCES := \
  rs2spirv.cpp \
  GlobalMergePass.cpp \
  InlinePreparationPass.cpp \
  LinkerModule.cpp \
  ReflectionPass.cpp \
  RSAllocationUtils.cpp \
  RSSPIRVWriter.cpp \
  unit_tests/LinkerModuleTests.cpp

RS2SPIRV_INCLUDES := \
  $(LIBSPIRV_ROOT_PATH) \
  $(LIBSPIRV_ROOT_PATH)/Mangler \
  $(LIBSPIRV_ROOT_PATH)/libSPIRV \
  $(LIBBCC_ROOT_PATH)/include \
  $(LLVM_ROOT_PATH)/include \
  $(LLVM_ROOT_PATH)/host/include

#=====================================================================
# Host Executable rs2spirv
#=====================================================================

# Don't build for unbundled branches
ifeq (,$(TARGET_BUILD_APPS))

include $(CLEAR_VARS)
include $(CLEAR_TBLGEN_VARS)

LOCAL_SRC_FILES := \
  $(RS2SPIRV_SOURCES)

LOCAL_C_INCLUDES := \
  $(RS2SPIRV_INCLUDES)

LOCAL_MODULE := rs2spirv
LOCAL_MODULE_CLASS := EXECUTABLES

# TODO: handle windows and darwin

LOCAL_MODULE_HOST_OS := linux
LOCAL_IS_HOST_MODULE := true

LOCAL_SHARED_LIBRARIES_linux += libLLVM libbcinfo libSPIRV

# TODO: fix the remaining warnings

LOCAL_CFLAGS += $(TOOL_CFLAGS) \
  -D_SPIRV_LLVM_API \
  -Wno-error=pessimizing-move \
  -Wno-error=unused-variable \
  -Wno-error=unused-private-field \
  -Wno-error=unused-function \
  -Wno-error=dangling-else \
  -Wno-error=ignored-qualifiers \
  -Wno-error=non-virtual-dtor

ifeq (true, $(FORCE_RS2SPIRV_DEBUG_BUILD))
  LOCAL_CFLAGS += -O0 -DRS2SPIRV_DEBUG=1
endif

include $(LLVM_ROOT_PATH)/llvm.mk
include $(LLVM_GEN_INTRINSICS_MK)
include $(LLVM_GEN_ATTRIBUTES_MK)
include $(LLVM_HOST_BUILD_MK)
include $(BUILD_HOST_EXECUTABLE)

endif # Don't build in unbundled branches

#=====================================================================
# Device Executable rs2spirv
#=====================================================================

ifneq (true,$(RS2SPRIV_DEVICE_BUILD)))

include $(CLEAR_VARS)
include $(CLEAR_TBLGEN_VARS)

LOCAL_SRC_FILES := \
  $(RS2SPIRV_SOURCES)

LOCAL_C_INCLUDES := \
  $(RS2SPIRV_INCLUDES)

LOCAL_MODULE := rs2spirv
LOCAL_MODULE_CLASS := EXECUTABLES

LOCAL_SHARED_LIBRARIES += libLLVM libbcinfo libSPIRV

LOCAL_CFLAGS += $(TOOL_CFLAGS) \
  -D_SPIRV_LLVM_API \
  -Wno-error=pessimizing-move \
  -Wno-error=unused-variable \
  -Wno-error=unused-private-field \
  -Wno-error=unused-function \
  -Wno-error=dangling-else \
  -Wno-error=ignored-qualifiers \
  -Wno-error=non-virtual-dtor \

ifeq (true, $(FORCE_RS2SPIRV_DEBUG_BUILD))
  LOCAL_CFLAGS += -O0 -DRS2SPIRV_DEBUG=1
endif

include $(LLVM_GEN_INTRINSICS_MK)
include $(LLVM_GEN_ATTRIBUTES_MK)
include $(LLVM_DEVICE_BUILD_MK)
include $(BUILD_EXECUTABLE)

endif # Don't build in unbundled branches

#=====================================================================
# Include Subdirectories
#=====================================================================

include $(call all-makefiles-under,$(LOCAL_PATH))

