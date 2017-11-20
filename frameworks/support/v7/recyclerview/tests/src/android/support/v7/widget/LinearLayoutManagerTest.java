/*
 * Copyright (C) 2014 The Android Open Source Project
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

import org.junit.Test;

import android.content.Context;
import android.graphics.Rect;
import android.os.Parcel;
import android.os.Parcelable;
import android.support.v4.view.AccessibilityDelegateCompat;
import android.support.v4.view.accessibility.AccessibilityEventCompat;
import android.support.v4.view.accessibility.AccessibilityRecordCompat;
import android.test.suitebuilder.annotation.MediumTest;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.widget.FrameLayout;

import static android.support.v7.widget.LayoutState.LAYOUT_END;
import static android.support.v7.widget.LayoutState.LAYOUT_START;
import static android.support.v7.widget.LinearLayoutManager.HORIZONTAL;
import static android.support.v7.widget.LinearLayoutManager.VERTICAL;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/**
 * Includes tests for {@link LinearLayoutManager}.
 * <p>
 * Since most UI tests are not practical, these tests are focused on internal data representation
 * and stability of LinearLayoutManager in response to different events (state change, scrolling
 * etc) where it is very hard to do manual testing.
 */
@MediumTest
public class LinearLayoutManagerTest extends BaseLinearLayoutManagerTest {

    @Test
    public void removeAnchorItem() throws Throwable {
        removeAnchorItemTest(
                new Config().orientation(VERTICAL).stackFromBottom(false).reverseLayout(
                        false), 100, 0);
    }

    @Test
    public void removeAnchorItemReverse() throws Throwable {
        removeAnchorItemTest(
                new Config().orientation(VERTICAL).stackFromBottom(false).reverseLayout(true), 100,
                0);
    }

    @Test
    public void removeAnchorItemStackFromEnd() throws Throwable {
        removeAnchorItemTest(
                new Config().orientation(VERTICAL).stackFromBottom(true).reverseLayout(false), 100,
                99);
    }

    @Test
    public void removeAnchorItemStackFromEndAndReverse() throws Throwable {
        removeAnchorItemTest(
                new Config().orientation(VERTICAL).stackFromBottom(true).reverseLayout(true), 100,
                99);
    }

    @Test
    public void removeAnchorItemHorizontal() throws Throwable {
        removeAnchorItemTest(
                new Config().orientation(HORIZONTAL).stackFromBottom(false).reverseLayout(
                        false), 100, 0);
    }

    @Test
    public void removeAnchorItemReverseHorizontal() throws Throwable {
        removeAnchorItemTest(
                new Config().orientation(HORIZONTAL).stackFromBottom(false).reverseLayout(true),
                100, 0);
    }

    @Test
    public void removeAnchorItemStackFromEndHorizontal() throws Throwable {
        removeAnchorItemTest(
                new Config().orientation(HORIZONTAL).stackFromBottom(true).reverseLayout(false),
                100, 99);
    }

    @Test
    public void removeAnchorItemStackFromEndAndReverseHorizontal() throws Throwable {
        removeAnchorItemTest(
                new Config().orientation(HORIZONTAL).stackFromBottom(true).reverseLayout(true), 100,
                99);
    }

    /**
     * This tests a regression where predictive animations were not working as expected when the
     * first item is removed and there aren't any more items to add from that direction.
     * First item refers to the default anchor item.
     */
    public void removeAnchorItemTest(final Config config, int adapterSize,
            final int removePos) throws Throwable {
        config.adapter(new TestAdapter(adapterSize) {
            @Override
            public void onBindViewHolder(TestViewHolder holder,
                    int position) {
                super.onBindViewHolder(holder, position);
                ViewGroup.LayoutParams lp = holder.itemView.getLayoutParams();
                if (!(lp instanceof ViewGroup.MarginLayoutParams)) {
                    lp = new ViewGroup.MarginLayoutParams(0, 0);
                    holder.itemView.setLayoutParams(lp);
                }
                ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
                final int maxSize;
                if (config.mOrientation == HORIZONTAL) {
                    maxSize = mRecyclerView.getWidth();
                    mlp.height = ViewGroup.MarginLayoutParams.FILL_PARENT;
                } else {
                    maxSize = mRecyclerView.getHeight();
                    mlp.width = ViewGroup.MarginLayoutParams.FILL_PARENT;
                }

                final int desiredSize;
                if (position == removePos) {
                    // make it large
                    desiredSize = maxSize / 4;
                } else {
                    // make it small
                    desiredSize = maxSize / 8;
                }
                if (config.mOrientation == HORIZONTAL) {
                    mlp.width = desiredSize;
                } else {
                    mlp.height = desiredSize;
                }
            }
        });
        setupByConfig(config, true);
        final int childCount = mLayoutManager.getChildCount();
        RecyclerView.ViewHolder toBeRemoved = null;
        List<RecyclerView.ViewHolder> toBeMoved = new ArrayList<RecyclerView.ViewHolder>();
        for (int i = 0; i < childCount; i++) {
            View child = mLayoutManager.getChildAt(i);
            RecyclerView.ViewHolder holder = mRecyclerView.getChildViewHolder(child);
            if (holder.getAdapterPosition() == removePos) {
                toBeRemoved = holder;
            } else {
                toBeMoved.add(holder);
            }
        }
        assertNotNull("test sanity", toBeRemoved);
        assertEquals("test sanity", childCount - 1, toBeMoved.size());
        LoggingItemAnimator loggingItemAnimator = new LoggingItemAnimator();
        mRecyclerView.setItemAnimator(loggingItemAnimator);
        loggingItemAnimator.reset();
        loggingItemAnimator.expectRunPendingAnimationsCall(1);
        mLayoutManager.expectLayouts(2);
        mTestAdapter.deleteAndNotify(removePos, 1);
        mLayoutManager.waitForLayout(1);
        loggingItemAnimator.waitForPendingAnimationsCall(2);
        assertTrue("removed child should receive remove animation",
                loggingItemAnimator.mRemoveVHs.contains(toBeRemoved));
        for (RecyclerView.ViewHolder vh : toBeMoved) {
            assertTrue("view holder should be in moved list",
                    loggingItemAnimator.mMoveVHs.contains(vh));
        }
        List<RecyclerView.ViewHolder> newHolders = new ArrayList<RecyclerView.ViewHolder>();
        for (int i = 0; i < mLayoutManager.getChildCount(); i++) {
            View child = mLayoutManager.getChildAt(i);
            RecyclerView.ViewHolder holder = mRecyclerView.getChildViewHolder(child);
            if (toBeRemoved != holder && !toBeMoved.contains(holder)) {
                newHolders.add(holder);
            }
        }
        assertTrue("some new children should show up for the new space", newHolders.size() > 0);
        assertEquals("no items should receive animate add since they are not new", 0,
                loggingItemAnimator.mAddVHs.size());
        for (RecyclerView.ViewHolder holder : newHolders) {
            assertTrue("new holder should receive a move animation",
                    loggingItemAnimator.mMoveVHs.contains(holder));
        }
        assertTrue("control against adding too many children due to bad layout state preparation."
                        + " initial:" + childCount + ", current:" + mRecyclerView.getChildCount(),
                mRecyclerView.getChildCount() <= childCount + 3 /*1 for removed view, 2 for its size*/);
    }

    @Test
    public void keepFocusOnRelayout() throws Throwable {
        setupByConfig(new Config(VERTICAL, false, false).itemCount(500), true);
        int center = (mLayoutManager.findLastVisibleItemPosition()
                - mLayoutManager.findFirstVisibleItemPosition()) / 2;
        final RecyclerView.ViewHolder vh = mRecyclerView.findViewHolderForLayoutPosition(center);
        final int top = mLayoutManager.mOrientationHelper.getDecoratedStart(vh.itemView);
        requestFocus(vh.itemView, true);
        assertTrue("view should have the focus", vh.itemView.hasFocus());
        // add a bunch of items right before that view, make sure it keeps its position
        mLayoutManager.expectLayouts(2);
        final int childCountToAdd = mRecyclerView.getChildCount() * 2;
        mTestAdapter.addAndNotify(center, childCountToAdd);
        center += childCountToAdd; // offset item
        mLayoutManager.waitForLayout(2);
        mLayoutManager.waitForAnimationsToEnd(20);
        final RecyclerView.ViewHolder postVH = mRecyclerView.findViewHolderForLayoutPosition(center);
        assertNotNull("focused child should stay in layout", postVH);
        assertSame("same view holder should be kept for unchanged child", vh, postVH);
        assertEquals("focused child's screen position should stay unchanged", top,
                mLayoutManager.mOrientationHelper.getDecoratedStart(postVH.itemView));
    }

    @Test
    public void keepFullFocusOnResize() throws Throwable {
        keepFocusOnResizeTest(new Config(VERTICAL, false, false).itemCount(500), true);
    }

    @Test
    public void keepPartialFocusOnResize() throws Throwable {
        keepFocusOnResizeTest(new Config(VERTICAL, false, false).itemCount(500), false);
    }

    @Test
    public void keepReverseFullFocusOnResize() throws Throwable {
        keepFocusOnResizeTest(new Config(VERTICAL, true, false).itemCount(500), true);
    }

    @Test
    public void keepReversePartialFocusOnResize() throws Throwable {
        keepFocusOnResizeTest(new Config(VERTICAL, true, false).itemCount(500), false);
    }

    @Test
    public void keepStackFromEndFullFocusOnResize() throws Throwable {
        keepFocusOnResizeTest(new Config(VERTICAL, false, true).itemCount(500), true);
    }

    @Test
    public void keepStackFromEndPartialFocusOnResize() throws Throwable {
        keepFocusOnResizeTest(new Config(VERTICAL, false, true).itemCount(500), false);
    }

    public void keepFocusOnResizeTest(final Config config, boolean fullyVisible) throws Throwable {
        setupByConfig(config, true);
        final int targetPosition;
        if (config.mStackFromEnd) {
            targetPosition = mLayoutManager.findFirstVisibleItemPosition();
        } else {
            targetPosition = mLayoutManager.findLastVisibleItemPosition();
        }
        final OrientationHelper helper = mLayoutManager.mOrientationHelper;
        final RecyclerView.ViewHolder vh = mRecyclerView
                .findViewHolderForLayoutPosition(targetPosition);

        // scroll enough to offset the child
        int startMargin = helper.getDecoratedStart(vh.itemView) -
                helper.getStartAfterPadding();
        int endMargin = helper.getEndAfterPadding() -
                helper.getDecoratedEnd(vh.itemView);
        Log.d(TAG, "initial start margin " + startMargin + " , end margin:" + endMargin);
        requestFocus(vh.itemView, true);
        assertTrue("view should gain the focus", vh.itemView.hasFocus());
        // scroll enough to offset the child
        startMargin = helper.getDecoratedStart(vh.itemView) -
                helper.getStartAfterPadding();
        endMargin = helper.getEndAfterPadding() -
                helper.getDecoratedEnd(vh.itemView);

        Log.d(TAG, "start margin " + startMargin + " , end margin:" + endMargin);
        assertTrue("View should become fully visible", startMargin >= 0 && endMargin >= 0);

        int expectedOffset = 0;
        boolean offsetAtStart = false;
        if (!fullyVisible) {
            // move it a bit such that it is no more fully visible
            final int childSize = helper
                    .getDecoratedMeasurement(vh.itemView);
            expectedOffset = childSize / 3;
            if (startMargin < endMargin) {
                scrollBy(expectedOffset);
                offsetAtStart = true;
            } else {
                scrollBy(-expectedOffset);
                offsetAtStart = false;
            }
            startMargin = helper.getDecoratedStart(vh.itemView) -
                    helper.getStartAfterPadding();
            endMargin = helper.getEndAfterPadding() -
                    helper.getDecoratedEnd(vh.itemView);
            assertTrue("test sanity, view should not be fully visible", startMargin < 0
                    || endMargin < 0);
        }

        mLayoutManager.expectLayouts(1);
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                final ViewGroup.LayoutParams layoutParams = mRecyclerView.getLayoutParams();
                if (config.mOrientation == HORIZONTAL) {
                    layoutParams.width = mRecyclerView.getWidth() / 2;
                } else {
                    layoutParams.height = mRecyclerView.getHeight() / 2;
                }
                mRecyclerView.setLayoutParams(layoutParams);
            }
        });
        Thread.sleep(100);
        // add a bunch of items right before that view, make sure it keeps its position
        mLayoutManager.waitForLayout(2);
        mLayoutManager.waitForAnimationsToEnd(20);
        assertTrue("view should preserve the focus", vh.itemView.hasFocus());
        final RecyclerView.ViewHolder postVH = mRecyclerView
                .findViewHolderForLayoutPosition(targetPosition);
        assertNotNull("focused child should stay in layout", postVH);
        assertSame("same view holder should be kept for unchanged child", vh, postVH);
        View focused = postVH.itemView;

        startMargin = helper.getDecoratedStart(focused) - helper.getStartAfterPadding();
        endMargin = helper.getEndAfterPadding() - helper.getDecoratedEnd(focused);

        assertTrue("focused child should be somewhat visible",
                helper.getDecoratedStart(focused) < helper.getEndAfterPadding()
                        && helper.getDecoratedEnd(focused) > helper.getStartAfterPadding());
        if (fullyVisible) {
            assertTrue("focused child end should stay fully visible",
                    endMargin >= 0);
            assertTrue("focused child start should stay fully visible",
                    startMargin >= 0);
        } else {
            if (offsetAtStart) {
                assertTrue("start should preserve its offset", startMargin < 0);
                assertTrue("end should be visible", endMargin >= 0);
            } else {
                assertTrue("end should preserve its offset", endMargin < 0);
                assertTrue("start should be visible", startMargin >= 0);
            }
        }
    }

    @Test
    public void scrollToPositionWithPredictive() throws Throwable {
        scrollToPositionWithPredictive(0, LinearLayoutManager.INVALID_OFFSET);
        removeRecyclerView();
        scrollToPositionWithPredictive(3, 20);
        removeRecyclerView();
        scrollToPositionWithPredictive(Config.DEFAULT_ITEM_COUNT / 2,
                LinearLayoutManager.INVALID_OFFSET);
        removeRecyclerView();
        scrollToPositionWithPredictive(Config.DEFAULT_ITEM_COUNT / 2, 10);
    }

    @Test
    public void recycleDuringAnimations() throws Throwable {
        final AtomicInteger childCount = new AtomicInteger(0);
        final TestAdapter adapter = new TestAdapter(300) {
            @Override
            public TestViewHolder onCreateViewHolder(ViewGroup parent,
                    int viewType) {
                final int cnt = childCount.incrementAndGet();
                final TestViewHolder testViewHolder = super.onCreateViewHolder(parent, viewType);
                if (DEBUG) {
                    Log.d(TAG, "CHILD_CNT(create):" + cnt + ", " + testViewHolder);
                }
                return testViewHolder;
            }
        };
        setupByConfig(new Config(VERTICAL, false, false).itemCount(300)
                .adapter(adapter), true);

        final RecyclerView.RecycledViewPool pool = new RecyclerView.RecycledViewPool() {
            @Override
            public void putRecycledView(RecyclerView.ViewHolder scrap) {
                super.putRecycledView(scrap);
                int cnt = childCount.decrementAndGet();
                if (DEBUG) {
                    Log.d(TAG, "CHILD_CNT(put):" + cnt + ", " + scrap);
                }
            }

            @Override
            public RecyclerView.ViewHolder getRecycledView(int viewType) {
                final RecyclerView.ViewHolder recycledView = super.getRecycledView(viewType);
                if (recycledView != null) {
                    final int cnt = childCount.incrementAndGet();
                    if (DEBUG) {
                        Log.d(TAG, "CHILD_CNT(get):" + cnt + ", " + recycledView);
                    }
                }
                return recycledView;
            }
        };
        pool.setMaxRecycledViews(mTestAdapter.getItemViewType(0), 500);
        mRecyclerView.setRecycledViewPool(pool);


        // now keep adding children to trigger more children being created etc.
        for (int i = 0; i < 100; i ++) {
            adapter.addAndNotify(15, 1);
            Thread.sleep(15);
        }
        getInstrumentation().waitForIdleSync();
        waitForAnimations(2);
        assertEquals("Children count should add up", childCount.get(),
                mRecyclerView.getChildCount() + mRecyclerView.mRecycler.mCachedViews.size());

        // now trigger lots of add again, followed by a scroll to position
        for (int i = 0; i < 100; i ++) {
            adapter.addAndNotify(5 + (i % 3) * 3, 1);
            Thread.sleep(25);
        }
        smoothScrollToPosition(mLayoutManager.findLastVisibleItemPosition() + 20);
        waitForAnimations(2);
        getInstrumentation().waitForIdleSync();
        assertEquals("Children count should add up", childCount.get(),
                mRecyclerView.getChildCount() + mRecyclerView.mRecycler.mCachedViews.size());
    }


    @Test
    public void dontRecycleChildrenOnDetach() throws Throwable {
        setupByConfig(new Config().recycleChildrenOnDetach(false), true);
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                int recyclerSize = mRecyclerView.mRecycler.getRecycledViewPool().size();
                mRecyclerView.setLayoutManager(new TestLayoutManager());
                assertEquals("No views are recycled", recyclerSize,
                        mRecyclerView.mRecycler.getRecycledViewPool().size());
            }
        });
    }

    @Test
    public void recycleChildrenOnDetach() throws Throwable {
        setupByConfig(new Config().recycleChildrenOnDetach(true), true);
        final int childCount = mLayoutManager.getChildCount();
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                int recyclerSize = mRecyclerView.mRecycler.getRecycledViewPool().size();
                mRecyclerView.mRecycler.getRecycledViewPool().setMaxRecycledViews(
                        mTestAdapter.getItemViewType(0), recyclerSize + childCount);
                mRecyclerView.setLayoutManager(new TestLayoutManager());
                assertEquals("All children should be recycled", childCount + recyclerSize,
                        mRecyclerView.mRecycler.getRecycledViewPool().size());
            }
        });
    }

    @Test
    public void scrollAndClear() throws Throwable {
        setupByConfig(new Config(), true);

        assertTrue("Children not laid out", mLayoutManager.collectChildCoordinates().size() > 0);

        mLayoutManager.expectLayouts(1);
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                mLayoutManager.scrollToPositionWithOffset(1, 0);
                mTestAdapter.clearOnUIThread();
            }
        });
        mLayoutManager.waitForLayout(2);

        assertEquals("Remaining children", 0, mLayoutManager.collectChildCoordinates().size());
    }


    @Test
    public void accessibilityPositions() throws Throwable {
        setupByConfig(new Config(VERTICAL, false, false), true);
        final AccessibilityDelegateCompat delegateCompat = mRecyclerView
                .getCompatAccessibilityDelegate();
        final AccessibilityEvent event = AccessibilityEvent.obtain();
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                delegateCompat.onInitializeAccessibilityEvent(mRecyclerView, event);
            }
        });
        final AccessibilityRecordCompat record = AccessibilityEventCompat
                .asRecord(event);
        assertEquals("result should have first position",
                record.getFromIndex(),
                mLayoutManager.findFirstVisibleItemPosition());
        assertEquals("result should have last position",
                record.getToIndex(),
                mLayoutManager.findLastVisibleItemPosition());
    }
}
