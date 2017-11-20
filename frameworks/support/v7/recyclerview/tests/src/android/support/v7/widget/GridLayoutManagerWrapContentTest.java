/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static android.support.v7.widget.BaseWrapContentWithAspectRatioTest.AspectRatioMeasureBehavior;
import static android.support.v7.widget.BaseWrapContentWithAspectRatioTest.MeasureBehavior;
import static android.support.v7.widget.BaseWrapContentWithAspectRatioTest.WrapContentAdapter;
import static android.support.v7.widget.LinearLayoutManager.HORIZONTAL;
import static android.support.v7.widget.LinearLayoutManager.VERTICAL;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.app.Activity;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;
import java.util.List;

@RunWith(Parameterized.class)
public class GridLayoutManagerWrapContentTest extends BaseWrapContentTest {
    private boolean mHorizontal = false;
    private int mSpanCount = 3;
    private RecyclerView.ItemDecoration mItemDecoration;
    public GridLayoutManagerWrapContentTest(Rect padding) {
        super(new WrapContentConfig(false, false, padding));
    }

    @Parameterized.Parameters(name = "paddingRect={0}")
    public static List<Rect> params() {
        return Arrays.asList(
                new Rect(0, 0, 0, 0),
                new Rect(5, 0, 0, 0),
                new Rect(0, 3, 0, 0),
                new Rect(0, 0, 2, 0),
                new Rect(0, 0, 0, 7),
                new Rect(3, 5, 7, 11)
        );
    }

    @Override
    RecyclerView.LayoutManager createLayoutManager() {
        GridLayoutManager lm = new GridLayoutManager(getActivity(), mSpanCount);
        lm.setOrientation(mHorizontal ? HORIZONTAL : VERTICAL);
        return lm;
    }

    @Override
    protected WrappedRecyclerView createRecyclerView(Activity activity) {
        WrappedRecyclerView recyclerView = super.createRecyclerView(activity);
        if (mItemDecoration != null) {
            recyclerView.addItemDecoration(mItemDecoration);
        }
        return recyclerView;
    }

    @Test
    public void testUnspecifiedWithHint() throws Throwable {
        unspecifiedWithHintTest(mHorizontal);
    }

    @Test
    public void testVerticalWithItemDecors() throws Throwable {
        mItemDecoration = new RecyclerView.ItemDecoration() {
            @Override
            public void getItemOffsets(Rect outRect, View view, RecyclerView parent,
                    RecyclerView.State state) {
                outRect.set(0, 5, 0, 10);
            }
        };
        TestedFrameLayout.FullControlLayoutParams lp =
                mWrapContentConfig.toLayoutParams(WRAP_CONTENT, WRAP_CONTENT);
        WrapContentAdapter adapter = new WrapContentAdapter(
                new MeasureBehavior(10, 10, WRAP_CONTENT, MATCH_PARENT)
        );
        Rect[] expected = new Rect[] {
                new Rect(0, 0, 10, 25)
        };
        layoutAndCheck(lp, adapter, expected, 30, 25);
    }

    @Test
    public void testHorizontalWithItemDecors() throws Throwable {
        mItemDecoration = new RecyclerView.ItemDecoration() {
            @Override
            public void getItemOffsets(Rect outRect, View view, RecyclerView parent,
                    RecyclerView.State state) {
                outRect.set(5, 0, 10, 0);
            }
        };
        TestedFrameLayout.FullControlLayoutParams lp =
                mWrapContentConfig.toLayoutParams(WRAP_CONTENT, WRAP_CONTENT);
        WrapContentAdapter adapter = new WrapContentAdapter(
                new MeasureBehavior(10, 10, MATCH_PARENT, WRAP_CONTENT)
        );
        Rect[] expected = new Rect[] {
                new Rect(0, 0, 25, 10)
        };
        layoutAndCheck(lp, adapter, expected, 75, 10);
    }

    @Test
    public void testHorizontal() throws Throwable {
        mHorizontal = true;
        mSpanCount = 2;
        TestedFrameLayout.FullControlLayoutParams lp =
                mWrapContentConfig.toLayoutParams(WRAP_CONTENT, WRAP_CONTENT);
        WrapContentAdapter adapter = new WrapContentAdapter(
                new MeasureBehavior(10, 10, WRAP_CONTENT, WRAP_CONTENT),
                new MeasureBehavior(10, 10, WRAP_CONTENT, WRAP_CONTENT),
                new MeasureBehavior(10, 10, WRAP_CONTENT, WRAP_CONTENT),
                new MeasureBehavior(20, 10, WRAP_CONTENT, WRAP_CONTENT)
        );
        Rect[] expected = new Rect[] {
                new Rect(0, 0, 10, 10),
                new Rect(0, 10, 10, 20),
                new Rect(10, 0, 30, 10),
                new Rect(10, 10, 30, 20)
        };
        layoutAndCheck(lp, adapter, expected, 30, 20);
    }

    @Test
    public void testHandleSecondLineChangingBorders() throws Throwable {
        TestedFrameLayout.FullControlLayoutParams lp =
                mWrapContentConfig.toLayoutParams(WRAP_CONTENT, WRAP_CONTENT);
        WrapContentAdapter adapter = new WrapContentAdapter(
                new MeasureBehavior(10, 10, WRAP_CONTENT, WRAP_CONTENT),
                new MeasureBehavior(10, 10, WRAP_CONTENT, WRAP_CONTENT),
                new MeasureBehavior(10, 10, WRAP_CONTENT, WRAP_CONTENT),
                new MeasureBehavior(20, 10, WRAP_CONTENT, WRAP_CONTENT)
        );
        Rect[] expected = new Rect[] {
                new Rect(0, 0, 10, 10),
                new Rect(20, 0, 30, 10),
                new Rect(40, 0, 50, 10),
                new Rect(0, 10, 20, 20)
        };
        layoutAndCheck(lp, adapter, expected, 60, 20);
    }

    @Test
    public void testSecondLineAffectingBordersWithAspectRatio() throws Throwable {
        TestedFrameLayout.FullControlLayoutParams lp =
                mWrapContentConfig.toLayoutParams(WRAP_CONTENT, WRAP_CONTENT);
        WrapContentAdapter adapter = new WrapContentAdapter(
                new AspectRatioMeasureBehavior(10, 5, MATCH_PARENT, WRAP_CONTENT)
                        .aspectRatio(HORIZONTAL, .5f),
                new MeasureBehavior(10, 5, WRAP_CONTENT, WRAP_CONTENT),
                new MeasureBehavior(10, 5, MATCH_PARENT, WRAP_CONTENT),
                new MeasureBehavior(20, 10, WRAP_CONTENT, WRAP_CONTENT)
        );
        Rect[] expected = new Rect[] {
                new Rect(0, 0, 20, 10),
                new Rect(20, 0, 30, 10),
                new Rect(40, 0, 60, 10),
                new Rect(0, 10, 20, 20)
        };
        layoutAndCheck(lp, adapter, expected, 60, 20);
    }

    @Override
    protected int getVerticalGravity(RecyclerView.LayoutManager layoutManager) {
        return Gravity.TOP;
    }

    @Override
    protected int getHorizontalGravity(RecyclerView.LayoutManager layoutManager) {
        return Gravity.LEFT;
    }
}
