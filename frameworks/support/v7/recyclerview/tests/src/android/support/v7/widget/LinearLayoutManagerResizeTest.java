/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.support.v7.widget;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import android.support.test.InstrumentationRegistry;
import android.test.suitebuilder.annotation.MediumTest;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;

import static android.support.v7.widget.LinearLayoutManager.HORIZONTAL;
import static org.junit.Assert.*;

@RunWith(Parameterized.class)
public class LinearLayoutManagerResizeTest extends BaseLinearLayoutManagerTest {

    final Config mConfig;

    public LinearLayoutManagerResizeTest(Config config) {
        mConfig = config;
    }

    @Parameterized.Parameters(name = "{0}")
    public static List<Config> testResize() throws Throwable {
        List<Config> configs = new ArrayList<>();
        for (Config config : addConfigVariation(createBaseVariations(), "mItemCount", 5
                , Config.DEFAULT_ITEM_COUNT)) {
            configs.add(config);
        }
        return configs;
    }

    @MediumTest
    @Test
    public void resize() throws Throwable {
        final Config config = (Config) mConfig.clone();
        final FrameLayout container = getRecyclerViewContainer();
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                container.setPadding(0, 0, 0, 0);
            }
        });

        setupByConfig(config, true);
        int lastVisibleItemPosition = mLayoutManager.findLastVisibleItemPosition();
        int firstVisibleItemPosition = mLayoutManager.findFirstVisibleItemPosition();
        int lastCompletelyVisibleItemPosition = mLayoutManager
                .findLastCompletelyVisibleItemPosition();
        int firstCompletelyVisibleItemPosition = mLayoutManager
                .findFirstCompletelyVisibleItemPosition();
        mLayoutManager.expectLayouts(1);
        // resize the recycler view to half
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (config.mOrientation == HORIZONTAL) {
                    container.setPadding(0, 0, container.getWidth() / 2, 0);
                } else {
                    container.setPadding(0, 0, 0, container.getWidth() / 2);
                }
            }
        });
        mLayoutManager.waitForLayout(1);
        if (config.mStackFromEnd) {
            assertEquals("[" + config + "]: last visible position should not change.",
                    lastVisibleItemPosition, mLayoutManager.findLastVisibleItemPosition());
            assertEquals("[" + config + "]: last completely visible position should not change",
                    lastCompletelyVisibleItemPosition,
                    mLayoutManager.findLastCompletelyVisibleItemPosition());
        } else {
            assertEquals("[" + config + "]: first visible position should not change.",
                    firstVisibleItemPosition, mLayoutManager.findFirstVisibleItemPosition());
            assertEquals("[" + config + "]: last completely visible position should not change",
                    firstCompletelyVisibleItemPosition,
                    mLayoutManager.findFirstCompletelyVisibleItemPosition());
        }
    }
}
