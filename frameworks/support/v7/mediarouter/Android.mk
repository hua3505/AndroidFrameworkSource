# Copyright (C) 2013 The Android Open Source Project
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

LOCAL_PATH := $(call my-dir)

# Build the resources using the latest applicable SDK version.
# We do this here because the final static library must be compiled with an older
# SDK version than the resources.
include $(CLEAR_VARS)
LOCAL_USE_AAPT2 := true
LOCAL_MODULE := android-support-v7-mediarouter-res
LOCAL_SDK_VERSION := $(SUPPORT_CURRENT_SDK_VERSION)
LOCAL_SRC_FILES := $(call all-java-files-under, dummy)
LOCAL_RESOURCE_DIR := $(LOCAL_PATH)/res
LOCAL_SHARED_ANDROID_LIBRARIES := android-support-v7-appcompat
LOCAL_JAR_EXCLUDE_FILES := none
LOCAL_JAVA_LANGUAGE_VERSION := 1.7
include $(BUILD_STATIC_JAVA_LIBRARY)

support_module_src_files := $(LOCAL_SRC_FILES)

# A helper sub-library that makes direct use of JellyBean APIs.
include $(CLEAR_VARS)
LOCAL_MODULE := android-support-v7-mediarouter-jellybean
LOCAL_SDK_VERSION := 16
LOCAL_SRC_FILES := $(call all-java-files-under, jellybean)
LOCAL_JAVA_LANGUAGE_VERSION := 1.7
include $(BUILD_STATIC_JAVA_LIBRARY)

support_module_src_files += $(LOCAL_SRC_FILES)

# A helper sub-library that makes direct use of JellyBean MR1 APIs.
include $(CLEAR_VARS)
LOCAL_MODULE := android-support-v7-mediarouter-jellybean-mr1
LOCAL_SDK_VERSION := 17
LOCAL_SRC_FILES := $(call all-java-files-under, jellybean-mr1)
LOCAL_STATIC_JAVA_LIBRARIES := android-support-v7-mediarouter-jellybean
LOCAL_JAVA_LANGUAGE_VERSION := 1.7
include $(BUILD_STATIC_JAVA_LIBRARY)

support_module_src_files += $(LOCAL_SRC_FILES)

# A helper sub-library that makes direct use of JellyBean MR2 APIs.
include $(CLEAR_VARS)
LOCAL_MODULE := android-support-v7-mediarouter-jellybean-mr2
LOCAL_SDK_VERSION := 18
LOCAL_SRC_FILES := $(call all-java-files-under, jellybean-mr2)
LOCAL_STATIC_JAVA_LIBRARIES := android-support-v7-mediarouter-jellybean-mr1
LOCAL_JAVA_LANGUAGE_VERSION := 1.7
include $(BUILD_STATIC_JAVA_LIBRARY)

support_module_src_files += $(LOCAL_SRC_FILES)

# A helper sub-library that makes direct use of V24 APIs.
include $(CLEAR_VARS)
LOCAL_MODULE := android-support-v7-mediarouter-api24
LOCAL_SDK_VERSION := $(SUPPORT_CURRENT_SDK_VERSION)
LOCAL_SRC_FILES := $(call all-java-files-under, api24)
LOCAL_STATIC_JAVA_LIBRARIES := android-support-v7-mediarouter-jellybean-mr2
LOCAL_JAVA_LANGUAGE_VERSION := 1.7
include $(BUILD_STATIC_JAVA_LIBRARY)

support_module_src_files += $(LOCAL_SRC_FILES)

# Here is the final static library that apps can link against.
# Applications that use this library must specify
#
#   LOCAL_STATIC_ANDROID_LIBRARIES := \
#       android-support-v7-mediarouter \
#       android-support-v7-appcompat \
#       android-support-v7-palette \
#       android-support-v4
#
# in their makefiles to include the resources and their dependencies in their package.
include $(CLEAR_VARS)
LOCAL_USE_AAPT2 := true
LOCAL_MODULE := android-support-v7-mediarouter
LOCAL_SDK_VERSION := 7
LOCAL_SDK_RES_VERSION := $(SUPPORT_CURRENT_SDK_VERSION)
LOCAL_SRC_FILES := $(call all-java-files-under,src)
LOCAL_STATIC_JAVA_LIBRARIES := android-support-v7-mediarouter-api24
LOCAL_STATIC_ANDROID_LIBRARIES := android-support-v7-mediarouter-res
LOCAL_SHARED_ANDROID_LIBRARIES := \
    android-support-v7-appcompat \
    android-support-v7-palette \
    android-support-v4
LOCAL_JAR_EXCLUDE_FILES := none
LOCAL_JAVA_LANGUAGE_VERSION := 1.7
LOCAL_AAPT_FLAGS := --add-javadoc-annotation doconly
include $(BUILD_STATIC_JAVA_LIBRARY)

support_module_src_files += $(LOCAL_SRC_FILES)

# API Check
# ---------------------------------------------
support_module := $(LOCAL_MODULE)
support_module_api_dir := $(LOCAL_PATH)/api
# We're asking doclava to generate stubs for android.support.v7.app in addition
# to mediarouter, so we'll have to point doclava at the sources for
# android.support.v7.app. Note that this API definition will overlap with that
# of the android.support.v7.app package.
support_module_src_files := $(LOCAL_SRC_FILES) \
  ../appcompat/src/android/support/v7/app/AppCompatDelegate.java \
  ../appcompat/src/android/support/v7/app/AppCompatCallback.java \
  ../appcompat/src/android/support/v7/app/AppCompatDialog.java \
  ../appcompat/src/android/support/v7/app/AlertDialog.java \
  ../appcompat/src/android/support/v7/app/ActionBar.java \
  ../appcompat/src/android/support/v7/app/ActionBarDrawerToggle.java \


support_module_java_libraries := $(LOCAL_JAVA_LIBRARIES)
support_module_java_packages := android.support.v7.app android.support.v7.media
include $(SUPPORT_API_CHECK)
