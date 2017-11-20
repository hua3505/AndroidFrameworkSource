/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#pragma once

#include <AudioGain.h>
#include <VolumeCurve.h>
#include <AudioPort.h>
#include <AudioPatch.h>
#include <DeviceDescriptor.h>
#include <IOProfile.h>
#include <HwModule.h>
#include <AudioInputDescriptor.h>
#include <AudioOutputDescriptor.h>
#include <AudioPolicyMix.h>
#include <EffectDescriptor.h>
#include <SoundTriggerSession.h>
#include <SessionRoute.h>

namespace android {

class AudioPolicyConfig
{
public:
    AudioPolicyConfig(HwModuleCollection &hwModules,
                      DeviceVector &availableOutputDevices,
                      DeviceVector &availableInputDevices,
                      sp<DeviceDescriptor> &defaultOutputDevices,
                      bool &isSpeakerDrcEnabled,
                      VolumeCurvesCollection *volumes = nullptr)
        : mHwModules(hwModules),
          mAvailableOutputDevices(availableOutputDevices),
          mAvailableInputDevices(availableInputDevices),
          mDefaultOutputDevices(defaultOutputDevices),
          mVolumeCurves(volumes),
          mIsSpeakerDrcEnabled(isSpeakerDrcEnabled)
    {}

    void setVolumes(const VolumeCurvesCollection &volumes)
    {
        if (mVolumeCurves != nullptr) {
            *mVolumeCurves = volumes;
        }
    }

    void setHwModules(const HwModuleCollection &hwModules)
    {
        mHwModules = hwModules;
    }

    void addAvailableDevice(const sp<DeviceDescriptor> &availableDevice)
    {
        if (audio_is_output_device(availableDevice->type())) {
            mAvailableOutputDevices.add(availableDevice);
        } else if (audio_is_input_device(availableDevice->type())) {
            mAvailableInputDevices.add(availableDevice);
        }
    }

    void addAvailableInputDevices(const DeviceVector &availableInputDevices)
    {
        mAvailableInputDevices.add(availableInputDevices);
    }

    void addAvailableOutputDevices(const DeviceVector &availableOutputDevices)
    {
        mAvailableOutputDevices.add(availableOutputDevices);
    }

    void setSpeakerDrcEnabled(bool isSpeakerDrcEnabled)
    {
        mIsSpeakerDrcEnabled = isSpeakerDrcEnabled;
    }

    const HwModuleCollection getHwModules() const { return mHwModules; }

    const DeviceVector &getAvailableInputDevices() const
    {
        return mAvailableInputDevices;
    }

    const DeviceVector &getAvailableOutputDevices() const
    {
        return mAvailableOutputDevices;
    }

    void setDefaultOutputDevice(const sp<DeviceDescriptor> &defaultDevice)
    {
        mDefaultOutputDevices = defaultDevice;
    }

    const sp<DeviceDescriptor> &getDefaultOutputDevice() const { return mDefaultOutputDevices; }

    void setDefault(void)
    {
        mDefaultOutputDevices = new DeviceDescriptor(AUDIO_DEVICE_OUT_SPEAKER);
        sp<HwModule> module;
        sp<DeviceDescriptor> defaultInputDevice = new DeviceDescriptor(AUDIO_DEVICE_IN_BUILTIN_MIC);
        mAvailableOutputDevices.add(mDefaultOutputDevices);
        mAvailableInputDevices.add(defaultInputDevice);

        module = new HwModule("primary");

        sp<OutputProfile> outProfile;
        outProfile = new OutputProfile(String8("primary"));
        outProfile->attach(module);
        outProfile->addAudioProfile(
                new AudioProfile(AUDIO_FORMAT_PCM_16_BIT, AUDIO_CHANNEL_OUT_STEREO, 44100));
        outProfile->addSupportedDevice(mDefaultOutputDevices);
        outProfile->setFlags(AUDIO_OUTPUT_FLAG_PRIMARY);
        module->mOutputProfiles.add(outProfile);

        sp<InputProfile> inProfile;
        inProfile = new InputProfile(String8("primary"));
        inProfile->attach(module);
        inProfile->addAudioProfile(
                new AudioProfile(AUDIO_FORMAT_PCM_16_BIT, AUDIO_CHANNEL_IN_MONO, 8000));
        inProfile->addSupportedDevice(defaultInputDevice);
        module->mInputProfiles.add(inProfile);

        mHwModules.add(module);
    }

private:
    HwModuleCollection &mHwModules; /**< Collection of Module, with Profiles, i.e. Mix Ports. */
    DeviceVector &mAvailableOutputDevices;
    DeviceVector &mAvailableInputDevices;
    sp<DeviceDescriptor> &mDefaultOutputDevices;
    VolumeCurvesCollection *mVolumeCurves;
    bool &mIsSpeakerDrcEnabled;
};

}; // namespace android
