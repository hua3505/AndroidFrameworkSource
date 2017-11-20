LOCAL_PATH:= $(call my-dir)

mcld_mips_target_SRC_FILES := \
  MipsAbiFlags.cpp \
  MipsDiagnostic.cpp  \
  MipsELFDynamic.cpp  \
  MipsEmulation.cpp \
  MipsGNUInfo.cpp \
  MipsGOT.cpp \
  MipsGOTPLT.cpp \
  MipsLA25Stub.cpp \
  MipsLDBackend.cpp \
  MipsPLT.cpp \
  MipsRelocator.cpp

# For the host
# =====================================================
include $(CLEAR_VARS)

LOCAL_SRC_FILES := $(mcld_mips_target_SRC_FILES)
LOCAL_MODULE:= libmcldMipsTarget

LOCAL_MODULE_TAGS := optional

include $(MCLD_HOST_BUILD_MK)
include $(BUILD_HOST_STATIC_LIBRARY)

# For the device
# =====================================================
include $(CLEAR_VARS)

LOCAL_SRC_FILES := $(mcld_mips_target_SRC_FILES)
LOCAL_MODULE:= libmcldMipsTarget

LOCAL_MODULE_TAGS := optional

include $(MCLD_DEVICE_BUILD_MK)
include $(BUILD_STATIC_LIBRARY)

