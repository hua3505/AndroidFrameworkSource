
LOCAL_PATH:=frameworks/rs
rs_base_CFLAGS := -Werror -Wall -Wno-unused-parameter -Wno-unused-variable \
		  -Wno-overloaded-virtual -DRS_COMPATIBILITY_LIB -std=c++11

ifeq ($(ARCH_ARM_HAVE_NEON),true)
rs_base_CFLAGS += -DARCH_ARM_HAVE_NEON
endif

ifeq ($(TARGET_BUILD_PDK), true)
  rs_base_CFLAGS += -D__RS_PDK__
endif

# Build rsg-generator ====================
include $(CLEAR_VARS)

LOCAL_MODULE := rsg-generator_support

# These symbols are normally defined by BUILD_XXX, but we need to define them
# here so that local-intermediates-dir works.

LOCAL_IS_HOST_MODULE := true
LOCAL_MODULE_CLASS := EXECUTABLES
intermediates := $(local-intermediates-dir)
LOCAL_MODULE_TAGS := optional

LOCAL_SRC_FILES:= \
    spec.l \
    rsg_generator.c

LOCAL_CXX_STL := none
LOCAL_SANITIZE := never

include $(BUILD_HOST_EXECUTABLE)

# TODO: This should go into build/core/config.mk
RSG_GENERATOR_SUPPORT:=$(LOCAL_BUILT_MODULE)

include $(CLEAR_VARS)
LOCAL_CLANG := true
LOCAL_MODULE := libRSSupport
LOCAL_SDK_VERSION := 9


LOCAL_MODULE_CLASS := SHARED_LIBRARIES
generated_sources_dir := $(call local-generated-sources-dir)

# Generate custom headers

GEN := $(addprefix $(generated_sources_dir)/, \
            rsgApiStructs.h \
            rsgApiFuncDecl.h \
        )

$(GEN) : PRIVATE_PATH := $(LOCAL_PATH)
$(GEN) : PRIVATE_CUSTOM_TOOL = cat $(PRIVATE_PATH)/rs.spec $(PRIVATE_PATH)/rs_compat.spec | $(RSG_GENERATOR_SUPPORT) $< $@
$(GEN) : $(RSG_GENERATOR_SUPPORT) $(LOCAL_PATH)/rs.spec $(LOCAL_PATH)/rs_compat.spec
$(GEN): $(generated_sources_dir)/%.h : $(LOCAL_PATH)/%.h.rsg
	$(transform-generated-source)

# used in jni/Android.mk
rs_generated_source += $(GEN)
LOCAL_GENERATED_SOURCES += $(GEN)

# Generate custom source files

GEN := $(addprefix $(generated_sources_dir)/, \
            rsgApi.cpp \
            rsgApiReplay.cpp \
        )

$(GEN) : PRIVATE_PATH := $(LOCAL_PATH)
$(GEN) : PRIVATE_CUSTOM_TOOL = cat $(PRIVATE_PATH)/rs.spec $(PRIVATE_PATH)/rs_compat.spec | $(RSG_GENERATOR_SUPPORT) $< $@
$(GEN) : $(RSG_GENERATOR_SUPPORT) $(LOCAL_PATH)/rs.spec $(LOCAL_PATH)/rs_compat.spec
$(GEN): $(generated_sources_dir)/%.cpp : $(LOCAL_PATH)/%.cpp.rsg
	$(transform-generated-source)

# used in jni/Android.mk
rs_generated_source += $(GEN)

LOCAL_GENERATED_SOURCES += $(GEN)

LOCAL_SRC_FILES:= \
	rsAllocation.cpp \
	rsApiAllocation.cpp \
	rsApiContext.cpp \
	rsApiDevice.cpp \
	rsApiElement.cpp \
	rsApiType.cpp \
	rsClosure.cpp \
	rsCompatibilityLib.cpp \
	rsComponent.cpp \
	rsContext.cpp \
	rsCppUtils.cpp \
	rsDevice.cpp \
	rsDriverLoader.cpp \
	rsElement.cpp \
	rsFifoSocket.cpp \
	rsObjectBase.cpp \
	rsMatrix2x2.cpp \
	rsMatrix3x3.cpp \
	rsMatrix4x4.cpp \
	rsMutex.cpp \
	rsSampler.cpp \
	rsScript.cpp \
	rsScriptC.cpp \
	rsScriptC_Lib.cpp \
	rsScriptGroup.cpp \
	rsScriptGroup2.cpp \
	rsScriptIntrinsic.cpp \
	rsSignal.cpp \
	rsStream.cpp \
	rsThreadIO.cpp \
	rsType.cpp \
	driver/rsdAllocation.cpp \
	driver/rsdBcc.cpp \
	driver/rsdCore.cpp \
	driver/rsdElement.cpp \
	driver/rsdRuntimeStubs.cpp \
	driver/rsdSampler.cpp \
	driver/rsdScriptGroup.cpp \
	driver/rsdType.cpp \
	cpu_ref/rsCpuCore.cpp \
	cpu_ref/rsCpuExecutable.cpp \
	cpu_ref/rsCpuScript.cpp \
	cpu_ref/rsCpuRuntimeMath.cpp \
	cpu_ref/rsCpuScriptGroup.cpp \
	cpu_ref/rsCpuScriptGroup2.cpp \
	cpu_ref/rsCpuIntrinsic.cpp \
	cpu_ref/rsCpuIntrinsic3DLUT.cpp \
	cpu_ref/rsCpuIntrinsicBlend.cpp \
	cpu_ref/rsCpuIntrinsicBlur.cpp \
	cpu_ref/rsCpuIntrinsicBLAS.cpp \
	cpu_ref/rsCpuIntrinsicColorMatrix.cpp \
	cpu_ref/rsCpuIntrinsicConvolve3x3.cpp \
	cpu_ref/rsCpuIntrinsicConvolve5x5.cpp \
	cpu_ref/rsCpuIntrinsicHistogram.cpp \
	cpu_ref/rsCpuIntrinsicLUT.cpp \
	cpu_ref/rsCpuIntrinsicResize.cpp \
	cpu_ref/rsCpuIntrinsicYuvToRGB.cpp

ifeq ($(ARCH_ARM_HAVE_ARMV7A),true)
LOCAL_CFLAGS_arm := -DARCH_ARM_HAVE_VFP -DARCH_ARM_USE_INTRINSICS
LOCAL_ASFLAGS_arm := -mfpu=neon
LOCAL_SRC_FILES_arm := \
    cpu_ref/rsCpuIntrinsics_neon_3DLUT.S \
    cpu_ref/rsCpuIntrinsics_neon_Blend.S \
    cpu_ref/rsCpuIntrinsics_neon_Blur.S \
    cpu_ref/rsCpuIntrinsics_neon_ColorMatrix.S \
    cpu_ref/rsCpuIntrinsics_neon_Convolve.S \
    cpu_ref/rsCpuIntrinsics_neon_Resize.S \
    cpu_ref/rsCpuIntrinsics_neon_YuvToRGB.S
endif

LOCAL_CFLAGS_arm64 += \
    -DARCH_ARM_USE_INTRINSICS \
    -DARCH_ARM64_USE_INTRINSICS \
    -DARCH_ARM64_HAVE_NEON
LOCAL_SRC_FILES_arm64 += \
    cpu_ref/rsCpuIntrinsics_advsimd_3DLUT.S \
    cpu_ref/rsCpuIntrinsics_advsimd_Blend.S \
    cpu_ref/rsCpuIntrinsics_advsimd_Blur.S \
    cpu_ref/rsCpuIntrinsics_advsimd_ColorMatrix.S \
    cpu_ref/rsCpuIntrinsics_advsimd_Convolve.S \
    cpu_ref/rsCpuIntrinsics_advsimd_Resize.S \
    cpu_ref/rsCpuIntrinsics_advsimd_YuvToRGB.S

LOCAL_CFLAGS_x86 += -DARCH_X86_HAVE_SSSE3
LOCAL_SRC_FILES_x86 += cpu_ref/rsCpuIntrinsics_x86.cpp
LOCAL_CFLAGS_x86_64 += -DARCH_X86_HAVE_SSSE3
LOCAL_SRC_FILES_x86_64 += cpu_ref/rsCpuIntrinsics_x86.cpp

LOCAL_REQUIRED_MODULES := libblasV8
LOCAL_STATIC_LIBRARIES := libbnnmlowpV8
LOCAL_LDFLAGS += -llog -ldl -Wl,--exclude-libs,libc++_static.a
LOCAL_NDK_STL_VARIANT := c++_static

LOCAL_C_INCLUDES += external/cblas/include
LOCAL_C_INCLUDES += external/gemmlowp/eight_bit_int_gemm

LOCAL_CFLAGS += $(rs_base_CFLAGS) -DGEMMLOWP_USE_STLPORT

LOCAL_MODULE:= libRSSupport
LOCAL_MODULE_TAGS := optional

# TODO: why isn't this picked up from the host GLOBAL_CFLAGS?
LOCAL_CFLAGS += -D__STDC_FORMAT_MACROS

include $(BUILD_SHARED_LIBRARY)
