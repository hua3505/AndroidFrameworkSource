/*
 * Copyright 2016, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <gui/Surface.h>

#include <media/ICrypto.h>
#include <media/stagefright/foundation/ABuffer.h>
#include <media/stagefright/foundation/ALooper.h>
#include <media/stagefright/foundation/AMessage.h>
#include <media/stagefright/foundation/AUtils.h>
#include <media/stagefright/MediaBuffer.h>
#include <media/stagefright/MediaCodecList.h>
#include <media/stagefright/MediaCodec.h>
#include <media/stagefright/MetaData.h>
#include <media/stagefright/SimpleDecodingSource.h>
#include <media/stagefright/Utils.h>

using namespace android;

const int64_t kTimeoutWaitForOutputUs = 500000; // 0.5 seconds
const int64_t kTimeoutWaitForInputUs = 5000; // 5 milliseconds

//static
sp<SimpleDecodingSource> SimpleDecodingSource::Create(
        const sp<IMediaSource> &source, uint32_t flags, const sp<ANativeWindow> &nativeWindow,
        const char *desiredCodec) {
    sp<Surface> surface = static_cast<Surface*>(nativeWindow.get());
    const char *mime = NULL;
    sp<MetaData> meta = source->getFormat();
    CHECK(meta->findCString(kKeyMIMEType, &mime));

    sp<AMessage> format = new AMessage;
    convertMetaDataToMessage(source->getFormat(), &format);

    Vector<AString> matchingCodecs;
    MediaCodecList::findMatchingCodecs(
            mime, false /* encoder */, flags, &matchingCodecs);

    sp<ALooper> looper = new ALooper;
    looper->setName("stagefright");
    looper->start();

    sp<MediaCodec> codec;

    for (size_t i = 0; i < matchingCodecs.size(); ++i) {
        const AString &componentName = matchingCodecs[i];
        if (desiredCodec != NULL && componentName.compare(desiredCodec)) {
            continue;
        }

        ALOGV("Attempting to allocate codec '%s'", componentName.c_str());

        codec = MediaCodec::CreateByComponentName(looper, componentName);
        if (codec != NULL) {
            ALOGI("Successfully allocated codec '%s'", componentName.c_str());

            status_t err = codec->configure(format, surface, NULL /* crypto */, 0 /* flags */);
            if (err == OK) {
                err = codec->getOutputFormat(&format);
            }
            if (err == OK) {
                return new SimpleDecodingSource(codec, source, looper, surface != NULL, format);
            }

            ALOGD("Failed to configure codec '%s'", componentName.c_str());
            codec->release();
            codec = NULL;
        }
    }

    looper->stop();
    ALOGE("No matching decoder! (mime: %s)", mime);
    return NULL;
}

SimpleDecodingSource::SimpleDecodingSource(
        const sp<MediaCodec> &codec, const sp<IMediaSource> &source, const sp<ALooper> &looper,
        bool usingSurface, const sp<AMessage> &format)
    : mCodec(codec),
      mSource(source),
      mLooper(looper),
      mUsingSurface(usingSurface),
      mProtectedState(format) {
    mCodec->getName(&mComponentName);
}

SimpleDecodingSource::~SimpleDecodingSource() {
    mCodec->release();
    mLooper->stop();
}

status_t SimpleDecodingSource::start(MetaData *params) {
    (void)params;
    Mutexed<ProtectedState>::Locked me(mProtectedState);
    if (me->mState != INIT) {
        return -EINVAL;
    }
    status_t res = mCodec->start();
    if (res == OK) {
        res = mSource->start();
    }

    if (res == OK) {
        me->mState = STARTED;
        me->mQueuedInputEOS = false;
        me->mGotOutputEOS = false;
    } else {
        me->mState = ERROR;
    }

    return res;
}

status_t SimpleDecodingSource::stop() {
    Mutexed<ProtectedState>::Locked me(mProtectedState);
    if (me->mState != STARTED) {
        return -EINVAL;
    }

    // wait for any pending reads to complete
    me->mState = STOPPING;
    while (me->mReading) {
        me.waitForCondition(me->mReadCondition);
    }

    status_t res1 = mCodec->stop();
    if (res1 != OK) {
        mCodec->release();
    }
    status_t res2 = mSource->stop();
    if (res1 == OK && res2 == OK) {
        me->mState = STOPPED;
    } else {
        me->mState = ERROR;
    }
    return res1 != OK ? res1 : res2;
}

sp<MetaData> SimpleDecodingSource::getFormat() {
    Mutexed<ProtectedState>::Locked me(mProtectedState);
    if (me->mState == STARTED || me->mState == INIT) {
        sp<MetaData> meta = new MetaData();
        convertMessageToMetaData(me->mFormat, meta);
        return meta;
    }
    return NULL;
}

SimpleDecodingSource::ProtectedState::ProtectedState(const sp<AMessage> &format)
    : mReading(false),
      mFormat(format),
      mState(INIT),
      mQueuedInputEOS(false),
      mGotOutputEOS(false) {
}

status_t SimpleDecodingSource::read(
        MediaBuffer **buffer, const ReadOptions *options) {
    *buffer = NULL;

    Mutexed<ProtectedState>::Locked me(mProtectedState);
    if (me->mState != STARTED) {
        return ERROR_END_OF_STREAM;
    }
    me->mReading = true;

    status_t res = doRead(me, buffer, options);

    me.lock();
    me->mReading = false;
    if (me->mState != STARTED) {
        me->mReadCondition.signal();
    }

    return res;
}

status_t SimpleDecodingSource::doRead(
        Mutexed<ProtectedState>::Locked &me, MediaBuffer **buffer, const ReadOptions *options) {
    // |me| is always locked on entry, but is allowed to be unlocked on exit
    CHECK_EQ(me->mState, STARTED);

    size_t out_ix, in_ix, out_offset, out_size;
    int64_t out_pts;
    uint32_t out_flags;
    status_t res;

    // flush codec on seek
    IMediaSource::ReadOptions::SeekMode mode;
    if (options != NULL && options->getSeekTo(&out_pts, &mode)) {
        me->mQueuedInputEOS = false;
        me->mGotOutputEOS = false;
        mCodec->flush();
    }

    if (me->mGotOutputEOS) {
        return ERROR_END_OF_STREAM;
    }

    for (int retries = 0; ++retries; ) {
        // If we fill all available input buffers, we should expect that
        // the codec produces at least one output buffer. Also, the codec
        // should produce an output buffer in at most 1 seconds. Retry a
        // few times nonetheless.
        while (!me->mQueuedInputEOS) {
            // allow some time to get input buffer after flush
            res = mCodec->dequeueInputBuffer(&in_ix, kTimeoutWaitForInputUs);
            if (res == -EAGAIN) {
                // no available input buffers
                break;
            }

            sp<ABuffer> in_buffer;
            if (res == OK) {
                res = mCodec->getInputBuffer(in_ix, &in_buffer);
            }

            if (res != OK || in_buffer == NULL) {
                ALOGW("[%s] could not get input buffer #%zu",
                        mComponentName.c_str(), in_ix);
                me->mState = ERROR;
                return UNKNOWN_ERROR;
            }

            MediaBuffer *in_buf;
            while (true) {
                in_buf = NULL;
                me.unlock();
                res = mSource->read(&in_buf, options);
                me.lock();
                if (res != OK || me->mState != STARTED) {
                    if (in_buf != NULL) {
                        in_buf->release();
                        in_buf = NULL;
                    }

                    // queue EOS
                    me->mQueuedInputEOS = true;
                    if (mCodec->queueInputBuffer(
                                 in_ix, 0 /* offset */, 0 /* size */,
                                 0 /* pts */, MediaCodec::BUFFER_FLAG_EOS) != OK) {
                        ALOGI("[%s] failed to queue input EOS", mComponentName.c_str());
                        me->mState = ERROR;
                        return UNKNOWN_ERROR;
                    }

                    // don't stop on EOS, but report error or EOS on stop
                    if (res != ERROR_END_OF_STREAM) {
                        me->mState = ERROR;
                        return res;
                    }
                    if (me->mState != STARTED) {
                        return ERROR_END_OF_STREAM;
                    }
                    break;
                }
                if (in_buf == NULL) { // should not happen
                    continue;
                } else if (in_buf->range_length() != 0) {
                    break;
                }
                in_buf->release();
            }

            if (in_buf != NULL) {
                int64_t timestampUs = 0;
                CHECK(in_buf->meta_data()->findInt64(kKeyTime, &timestampUs));
                if (in_buf->range_length() > in_buffer->capacity()) {
                    ALOGW("'%s' received %zu input bytes for buffer of size %zu",
                            mComponentName.c_str(),
                            in_buf->range_length(), in_buffer->capacity());
                }
                memcpy(in_buffer->base(), (uint8_t *)in_buf->data() + in_buf->range_offset(),
                       min(in_buf->range_length(), in_buffer->capacity()));

                res = mCodec->queueInputBuffer(
                        in_ix, 0 /* offset */, in_buf->range_length(),
                        timestampUs, 0 /* flags */);
                if (res != OK) {
                    ALOGI("[%s] failed to queue input buffer #%zu", mComponentName.c_str(), in_ix);
                    me->mState = ERROR;
                }
                in_buf->release();
            }
        }

        me.unlock();
        res = mCodec->dequeueOutputBuffer(
                &out_ix, &out_offset, &out_size, &out_pts,
                &out_flags, kTimeoutWaitForOutputUs /* timeoutUs */);
        me.lock();
        // abort read on stop
        if (me->mState != STARTED) {
            if (res == OK) {
                mCodec->releaseOutputBuffer(out_ix);
            }
            return ERROR_END_OF_STREAM;
        }

        if (res == -EAGAIN) {
            ALOGD("[%s] did not produce an output buffer. retry count: %d",
                  mComponentName.c_str(), retries);
            continue;
        } else if (res == INFO_FORMAT_CHANGED) {
            if (mCodec->getOutputFormat(&me->mFormat) != OK) {
                me->mState = ERROR;
                res = UNKNOWN_ERROR;
            }
            return res;
        } else if (res == INFO_OUTPUT_BUFFERS_CHANGED) {
            ALOGV("output buffers changed");
            continue;
        } else if (res != OK) {
            me->mState = ERROR;
            return res;
        }

        sp<ABuffer> out_buffer;
        res = mCodec->getOutputBuffer(out_ix, &out_buffer);
        if (res != OK) {
            ALOGW("[%s] could not get output buffer #%zu",
                    mComponentName.c_str(), out_ix);
            me->mState = ERROR;
            return UNKNOWN_ERROR;
        }
        if (out_flags & MediaCodec::BUFFER_FLAG_EOS) {
            me->mGotOutputEOS = true;
            // return EOS immediately if last buffer is empty
            if (out_size == 0) {
                mCodec->releaseOutputBuffer(out_ix);
                return ERROR_END_OF_STREAM;
            }
        }

        if (mUsingSurface && out_size > 0) {
            *buffer = new MediaBuffer(0);
            mCodec->renderOutputBufferAndRelease(out_ix);
        } else {
            *buffer = new MediaBuffer(out_size);
            CHECK_LE(out_buffer->size(), (*buffer)->size());
            memcpy((*buffer)->data(), out_buffer->data(), out_buffer->size());
            (*buffer)->meta_data()->setInt64(kKeyTime, out_pts);
            mCodec->releaseOutputBuffer(out_ix);
        }
        return OK;
    }

    return TIMED_OUT;
}
