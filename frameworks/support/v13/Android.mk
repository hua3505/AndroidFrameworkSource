# Copyright (C) 2011 The Android Open Source Project
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

# Note: the source code is in java/, not src/, because this code is also part of
# the framework library, and build/core/pathmap.mk expects a java/ subdirectory.

# A helper sub-library that makes direct use of Ice Cream Sandwich APIs.
include $(CLEAR_VARS)
LOCAL_MODULE := android-support-v13-ics
LOCAL_SDK_VERSION := 14
LOCAL_SRC_FILES := $(call all-java-files-under, ics)
LOCAL_JAVA_LANGUAGE_VERSION := 1.7
include $(BUILD_STATIC_JAVA_LIBRARY)

# A helper sub-library that makes direct use of Ice Cream Sandwich APIs.
include $(CLEAR_VARS)
LOCAL_MODULE := android-support-v13-ics-mr1
LOCAL_SDK_VERSION := 15
LOCAL_SRC_FILES := $(call all-java-files-under, ics-mr1)
LOCAL_STATIC_JAVA_LIBRARIES := android-support-v13-ics
LOCAL_JAVA_LANGUAGE_VERSION := 1.7
include $(BUILD_STATIC_JAVA_LIBRARY)

# A helper sub-library that makes direct use of MNC APIs.
include $(CLEAR_VARS)
LOCAL_MODULE := android-support-v13-mnc
LOCAL_SDK_VERSION := 23
LOCAL_SRC_FILES := $(call all-java-files-under, api23)
LOCAL_STATIC_JAVA_LIBRARIES := android-support-v13-ics-mr1
LOCAL_JAVA_LANGUAGE_VERSION := 1.7
include $(BUILD_STATIC_JAVA_LIBRARY)

# A helper sub-library that makes direct use of NYC APIs.
include $(CLEAR_VARS)
LOCAL_MODULE := android-support-v13-nyc
LOCAL_SDK_VERSION := current
LOCAL_SRC_FILES := $(call all-java-files-under, api24)
LOCAL_STATIC_JAVA_LIBRARIES := android-support-v13-mnc
LOCAL_JAVA_LANGUAGE_VERSION := 1.7
include $(BUILD_STATIC_JAVA_LIBRARY)

# -----------------------------------------------------------------------

include $(CLEAR_VARS)
LOCAL_MODULE := android-support-v13
LOCAL_SDK_VERSION := 13
LOCAL_SRC_FILES := $(call all-java-files-under, java)
LOCAL_STATIC_JAVA_LIBRARIES += android-support-v4 \
        android-support-v13-nyc
LOCAL_JAVA_LANGUAGE_VERSION := 1.7
include $(BUILD_STATIC_JAVA_LIBRARY)


# API Check
# ---------------------------------------------
support_module := $(LOCAL_MODULE)
support_module_api_dir := $(LOCAL_PATH)/api
support_module_src_files := $(LOCAL_SRC_FILES)
support_module_java_libraries := $(LOCAL_JAVA_LIBRARIES) android-support-v13
support_module_java_packages := android.support.v13.*
include $(SUPPORT_API_CHECK)
