LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
    ICrypto.cpp \
    IDataSource.cpp \
    IDrm.cpp \
    IDrmClient.cpp \
    IHDCP.cpp \
    mediaplayer.cpp \
    IMediaCodecList.cpp \
    IMediaCodecService.cpp \
    IMediaDrmService.cpp \
    IMediaHTTPConnection.cpp \
    IMediaHTTPService.cpp \
    IMediaExtractor.cpp           \
    IMediaExtractorService.cpp \
    IMediaPlayerService.cpp \
    IMediaPlayerClient.cpp \
    IMediaRecorderClient.cpp \
    IMediaPlayer.cpp \
    IMediaRecorder.cpp \
    IMediaSource.cpp \
    IRemoteDisplay.cpp \
    IRemoteDisplayClient.cpp \
    IResourceManagerClient.cpp \
    IResourceManagerService.cpp \
    IStreamSource.cpp \
    MediaCodecInfo.cpp \
    MediaUtils.cpp \
    Metadata.cpp \
    mediarecorder.cpp \
    IMediaMetadataRetriever.cpp \
    mediametadataretriever.cpp \
    MidiIoWrapper.cpp \
    JetPlayer.cpp \
    IOMX.cpp \
    MediaScanner.cpp \
    MediaScannerClient.cpp \
    CharacterEncodingDetector.cpp \
    IMediaDeathNotifier.cpp \
    MediaProfiles.cpp \
    MediaResource.cpp \
    MediaResourcePolicy.cpp \
    Visualizer.cpp \
    StringArray.cpp \

LOCAL_SHARED_LIBRARIES := \
	libui liblog libcutils libutils libbinder libsonivox libicuuc libicui18n libexpat \
        libcamera_client libstagefright_foundation \
        libgui libdl libaudioutils libaudioclient

LOCAL_EXPORT_SHARED_LIBRARY_HEADERS := libbinder

LOCAL_WHOLE_STATIC_LIBRARIES := libmedia_helper

# for memory heap analysis
LOCAL_STATIC_LIBRARIES := libc_malloc_debug_backtrace libc_logging

LOCAL_MODULE:= libmedia

LOCAL_ADDITIONAL_DEPENDENCIES := $(LOCAL_PATH)/Android.mk

LOCAL_C_INCLUDES := \
    $(TOP)/frameworks/native/include/media/openmax \
    $(TOP)/frameworks/av/include/media/ \
    $(TOP)/frameworks/av/media/libstagefright \
    $(call include-path-for, audio-effects) \
    $(call include-path-for, audio-utils)

LOCAL_CFLAGS += -Werror -Wno-error=deprecated-declarations -Wall
LOCAL_CLANG := true
LOCAL_SANITIZE := unsigned-integer-overflow signed-integer-overflow

include $(BUILD_SHARED_LIBRARY)

