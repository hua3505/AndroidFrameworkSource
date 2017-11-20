package android.support.v7.widget;

import android.graphics.Rect;
import android.support.annotation.Nullable;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static android.support.v7.widget.LayoutState.LAYOUT_END;
import static android.support.v7.widget.LayoutState.LAYOUT_START;
import static android.support.v7.widget.LinearLayoutManager.VERTICAL;
import static android.support.v7.widget.StaggeredGridLayoutManager.GAP_HANDLING_MOVE_ITEMS_BETWEEN_SPANS;
import static android.support.v7.widget.StaggeredGridLayoutManager.GAP_HANDLING_NONE;
import static android.support.v7.widget.StaggeredGridLayoutManager.HORIZONTAL;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import static java.util.concurrent.TimeUnit.SECONDS;

import org.hamcrest.CoreMatchers;
import org.hamcrest.MatcherAssert;

public class BaseStaggeredGridLayoutManagerTest extends BaseRecyclerViewInstrumentationTest {

    protected static final boolean DEBUG = false;
    protected static final int AVG_ITEM_PER_VIEW = 3;
    protected static final String TAG = "StaggeredGridLayoutManagerTest";
    volatile WrappedLayoutManager mLayoutManager;
    GridTestAdapter mAdapter;

    protected static List<Config> createBaseVariations() {
        List<Config> variations = new ArrayList<>();
        for (int orientation : new int[]{VERTICAL, HORIZONTAL}) {
            for (boolean reverseLayout : new boolean[]{false, true}) {
                for (int spanCount : new int[]{1, 3}) {
                    for (int gapStrategy : new int[]{GAP_HANDLING_NONE,
                            GAP_HANDLING_MOVE_ITEMS_BETWEEN_SPANS}) {
                        for (boolean wrap : new boolean[]{true, false}) {
                            variations.add(new Config(orientation, reverseLayout, spanCount,
                                    gapStrategy).wrap(wrap));
                        }

                    }
                }
            }
        }
        return variations;
    }

    protected static List<Config> addConfigVariation(List<Config> base, String fieldName,
            Object... variations)
            throws CloneNotSupportedException, NoSuchFieldException, IllegalAccessException {
        List<Config> newConfigs = new ArrayList<Config>();
        Field field = Config.class.getDeclaredField(fieldName);
        for (Config config : base) {
            for (Object variation : variations) {
                Config newConfig = (Config) config.clone();
                field.set(newConfig, variation);
                newConfigs.add(newConfig);
            }
        }
        return newConfigs;
    }

    void setupByConfig(Config config) throws Throwable {
        setupByConfig(config, new GridTestAdapter(config.mItemCount, config.mOrientation));
    }

    void setupByConfig(Config config, GridTestAdapter adapter) throws Throwable {
        mAdapter = adapter;
        mRecyclerView = new RecyclerView(getActivity());
        mRecyclerView.setAdapter(mAdapter);
        mRecyclerView.setHasFixedSize(true);
        mLayoutManager = new WrappedLayoutManager(config.mSpanCount,
                config.mOrientation);
        mLayoutManager.setGapStrategy(config.mGapStrategy);
        mLayoutManager.setReverseLayout(config.mReverseLayout);
        mRecyclerView.setLayoutManager(mLayoutManager);
        mRecyclerView.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override
            public void getItemOffsets(Rect outRect, View view, RecyclerView parent,
                    RecyclerView.State state) {
                try {
                    StaggeredGridLayoutManager.LayoutParams
                            lp = (StaggeredGridLayoutManager.LayoutParams) view.getLayoutParams();
                    assertNotNull("view should have layout params assigned", lp);
                    assertNotNull("when item offsets are requested, view should have a valid span",
                            lp.mSpan);
                } catch (Throwable t) {
                    postExceptionToInstrumentation(t);
                }
            }
        });
    }

    StaggeredGridLayoutManager.LayoutParams getLp(View view) {
        return (StaggeredGridLayoutManager.LayoutParams) view.getLayoutParams();
    }

    void waitFirstLayout() throws Throwable {
        mLayoutManager.expectLayouts(1);
        setRecyclerView(mRecyclerView);
        mLayoutManager.waitForLayout(3);
        getInstrumentation().waitForIdleSync();
    }

    /**
     * enqueues an empty runnable to main thread so that we can be assured it did run
     *
     * @param count Number of times to run
     */
    protected void waitForMainThread(int count) throws Throwable {
        final AtomicInteger i = new AtomicInteger(count);
        while (i.get() > 0) {
            runTestOnUiThread(new Runnable() {
                @Override
                public void run() {
                    i.decrementAndGet();
                }
            });
        }
    }

    public void assertRectSetsNotEqual(String message, Map<Item, Rect> before,
            Map<Item, Rect> after) {
        Throwable throwable = null;
        try {
            assertRectSetsEqual("NOT " + message, before, after);
        } catch (Throwable t) {
            throwable = t;
        }
        assertNotNull(message + " two layout should be different", throwable);
    }

    public void assertRectSetsEqual(String message, Map<Item, Rect> before, Map<Item, Rect> after) {
        assertRectSetsEqual(message, before, after, true);
    }

    public void assertRectSetsEqual(String message, Map<Item, Rect> before, Map<Item, Rect> after,
            boolean strictItemEquality) {
        StringBuilder log = new StringBuilder();
        if (DEBUG) {
            log.append("checking rectangle equality.\n");
            log.append("total space:" + mLayoutManager.mPrimaryOrientation.getTotalSpace());
            log.append("before:");
            for (Map.Entry<Item, Rect> entry : before.entrySet()) {
                log.append("\n").append(entry.getKey().mAdapterIndex).append(":")
                        .append(entry.getValue());
            }
            log.append("\nafter:");
            for (Map.Entry<Item, Rect> entry : after.entrySet()) {
                log.append("\n").append(entry.getKey().mAdapterIndex).append(":")
                        .append(entry.getValue());
            }
            message += "\n\n" + log.toString();
        }
        assertEquals(message + ": item counts should be equal", before.size()
                , after.size());
        for (Map.Entry<Item, Rect> entry : before.entrySet()) {
            final Item beforeItem = entry.getKey();
            Rect afterRect = null;
            if (strictItemEquality) {
                afterRect = after.get(beforeItem);
                assertNotNull(message + ": Same item should be visible after simple re-layout",
                        afterRect);
            } else {
                for (Map.Entry<Item, Rect> afterEntry : after.entrySet()) {
                    final Item afterItem = afterEntry.getKey();
                    if (afterItem.mAdapterIndex == beforeItem.mAdapterIndex) {
                        afterRect = afterEntry.getValue();
                        break;
                    }
                }
                assertNotNull(message + ": Item with same adapter index should be visible " +
                                "after simple re-layout",
                        afterRect);
            }
            assertEquals(message + ": Item should be laid out at the same coordinates",
                    entry.getValue(),
                    afterRect);
        }
    }

    protected void assertViewPositions(Config config) {
        ArrayList<ArrayList<View>> viewsBySpan = mLayoutManager.collectChildrenBySpan();
        OrientationHelper orientationHelper = OrientationHelper
                .createOrientationHelper(mLayoutManager, config.mOrientation);
        for (ArrayList<View> span : viewsBySpan) {
            // validate all children's order. first child should have min start mPosition
            final int count = span.size();
            for (int i = 0, j = 1; j < count; i++, j++) {
                View prev = span.get(i);
                View next = span.get(j);
                assertTrue(config + " prev item should be above next item",
                        orientationHelper.getDecoratedEnd(prev) <= orientationHelper
                                .getDecoratedStart(next)
                );

            }
        }
    }

    protected TargetTuple findInvisibleTarget(Config config) {
        int minPosition = Integer.MAX_VALUE, maxPosition = Integer.MIN_VALUE;
        for (int i = 0; i < mLayoutManager.getChildCount(); i++) {
            View child = mLayoutManager.getChildAt(i);
            int position = mRecyclerView.getChildLayoutPosition(child);
            if (position < minPosition) {
                minPosition = position;
            }
            if (position > maxPosition) {
                maxPosition = position;
            }
        }
        final int tailTarget = maxPosition + (mAdapter.getItemCount() - maxPosition) / 2;
        final int headTarget = minPosition / 2;
        final int target;
        // where will the child come from ?
        final int itemLayoutDirection;
        if (Math.abs(tailTarget - maxPosition) > Math.abs(headTarget - minPosition)) {
            target = tailTarget;
            itemLayoutDirection = config.mReverseLayout ? LAYOUT_START : LAYOUT_END;
        } else {
            target = headTarget;
            itemLayoutDirection = config.mReverseLayout ? LAYOUT_END : LAYOUT_START;
        }
        if (DEBUG) {
            Log.d(TAG,
                    config + " target:" + target + " min:" + minPosition + ", max:" + maxPosition);
        }
        return new TargetTuple(target, itemLayoutDirection);
    }

    protected void scrollToPositionWithOffset(final int position, final int offset)
            throws Throwable {
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                mLayoutManager.scrollToPositionWithOffset(position, offset);
            }
        });
    }

    static class OnLayoutListener {

        void before(RecyclerView.Recycler recycler, RecyclerView.State state) {
        }

        void after(RecyclerView.Recycler recycler, RecyclerView.State state) {
        }
    }

    static class VisibleChildren {

        int[] firstVisiblePositions;

        int[] firstFullyVisiblePositions;

        int[] lastVisiblePositions;

        int[] lastFullyVisiblePositions;

        View findFirstPartialVisibleClosestToStart;
        View findFirstPartialVisibleClosestToEnd;

        VisibleChildren(int spanCount) {
            firstFullyVisiblePositions = new int[spanCount];
            firstVisiblePositions = new int[spanCount];
            lastVisiblePositions = new int[spanCount];
            lastFullyVisiblePositions = new int[spanCount];
            for (int i = 0; i < spanCount; i++) {
                firstFullyVisiblePositions[i] = RecyclerView.NO_POSITION;
                firstVisiblePositions[i] = RecyclerView.NO_POSITION;
                lastVisiblePositions[i] = RecyclerView.NO_POSITION;
                lastFullyVisiblePositions[i] = RecyclerView.NO_POSITION;
            }
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            VisibleChildren that = (VisibleChildren) o;

            if (!Arrays.equals(firstFullyVisiblePositions, that.firstFullyVisiblePositions)) {
                return false;
            }
            if (findFirstPartialVisibleClosestToStart
                    != null ? !findFirstPartialVisibleClosestToStart
                    .equals(that.findFirstPartialVisibleClosestToStart)
                    : that.findFirstPartialVisibleClosestToStart != null) {
                return false;
            }
            if (!Arrays.equals(firstVisiblePositions, that.firstVisiblePositions)) {
                return false;
            }
            if (!Arrays.equals(lastFullyVisiblePositions, that.lastFullyVisiblePositions)) {
                return false;
            }
            if (findFirstPartialVisibleClosestToEnd != null ? !findFirstPartialVisibleClosestToEnd
                    .equals(that.findFirstPartialVisibleClosestToEnd)
                    : that.findFirstPartialVisibleClosestToEnd
                            != null) {
                return false;
            }
            if (!Arrays.equals(lastVisiblePositions, that.lastVisiblePositions)) {
                return false;
            }

            return true;
        }

        @Override
        public int hashCode() {
            int result = Arrays.hashCode(firstVisiblePositions);
            result = 31 * result + Arrays.hashCode(firstFullyVisiblePositions);
            result = 31 * result + Arrays.hashCode(lastVisiblePositions);
            result = 31 * result + Arrays.hashCode(lastFullyVisiblePositions);
            result = 31 * result + (findFirstPartialVisibleClosestToStart != null
                    ? findFirstPartialVisibleClosestToStart
                    .hashCode() : 0);
            result = 31 * result + (findFirstPartialVisibleClosestToEnd != null
                    ? findFirstPartialVisibleClosestToEnd
                    .hashCode()
                    : 0);
            return result;
        }

        @Override
        public String toString() {
            return "VisibleChildren{" +
                    "firstVisiblePositions=" + Arrays.toString(firstVisiblePositions) +
                    ", firstFullyVisiblePositions=" + Arrays.toString(firstFullyVisiblePositions) +
                    ", lastVisiblePositions=" + Arrays.toString(lastVisiblePositions) +
                    ", lastFullyVisiblePositions=" + Arrays.toString(lastFullyVisiblePositions) +
                    ", findFirstPartialVisibleClosestToStart=" +
                    viewToString(findFirstPartialVisibleClosestToStart) +
                    ", findFirstPartialVisibleClosestToEnd=" +
                    viewToString(findFirstPartialVisibleClosestToEnd) +
                    '}';
        }

        private String viewToString(View view) {
            if (view == null) {
                return null;
            }
            ViewGroup.LayoutParams lp = view.getLayoutParams();
            if (lp instanceof RecyclerView.LayoutParams == false) {
                return System.identityHashCode(view) + "(?)";
            }
            RecyclerView.LayoutParams rvlp = (RecyclerView.LayoutParams) lp;
            return System.identityHashCode(view) + "(" + rvlp.getViewAdapterPosition() + ")";
        }
    }

    abstract static class OnBindCallback {

        abstract void onBoundItem(TestViewHolder vh, int position);

        boolean assignRandomSize() {
            return true;
        }

        void onCreatedViewHolder(TestViewHolder vh) {
        }
    }

    static class Config implements Cloneable {

        static final int DEFAULT_ITEM_COUNT = 300;

        int mOrientation = OrientationHelper.VERTICAL;

        boolean mReverseLayout = false;

        int mSpanCount = 3;

        int mGapStrategy = GAP_HANDLING_MOVE_ITEMS_BETWEEN_SPANS;

        int mItemCount = DEFAULT_ITEM_COUNT;

        boolean mWrap = false;

        Config(int orientation, boolean reverseLayout, int spanCount, int gapStrategy) {
            mOrientation = orientation;
            mReverseLayout = reverseLayout;
            mSpanCount = spanCount;
            mGapStrategy = gapStrategy;
        }

        public Config() {

        }

        Config orientation(int orientation) {
            mOrientation = orientation;
            return this;
        }

        Config reverseLayout(boolean reverseLayout) {
            mReverseLayout = reverseLayout;
            return this;
        }

        Config spanCount(int spanCount) {
            mSpanCount = spanCount;
            return this;
        }

        Config gapStrategy(int gapStrategy) {
            mGapStrategy = gapStrategy;
            return this;
        }

        public Config itemCount(int itemCount) {
            mItemCount = itemCount;
            return this;
        }

        public Config wrap(boolean wrap) {
            mWrap = wrap;
            return this;
        }

        @Override
        public String toString() {
            return "[CONFIG:" +
                    " span:" + mSpanCount + "," +
                    " orientation:" + (mOrientation == HORIZONTAL ? "horz," : "vert,") +
                    " reverse:" + (mReverseLayout ? "T" : "F") +
                    " itemCount:" + mItemCount +
                    " wrapContent:" + mWrap +
                    " gap strategy: " + gapStrategyName(mGapStrategy);
        }

        protected static String gapStrategyName(int gapStrategy) {
            switch (gapStrategy) {
                case GAP_HANDLING_NONE:
                    return "none";
                case GAP_HANDLING_MOVE_ITEMS_BETWEEN_SPANS:
                    return "move spans";
            }
            return "gap strategy: unknown";
        }

        @Override
        public Object clone() throws CloneNotSupportedException {
            return super.clone();
        }
    }

    class WrappedLayoutManager extends StaggeredGridLayoutManager {

        CountDownLatch layoutLatch;
        OnLayoutListener mOnLayoutListener;
        // gradle does not yet let us customize manifest for tests which is necessary to test RTL.
        // until bug is fixed, we'll fake it.
        // public issue id: 57819
        Boolean mFakeRTL;

        @Override
        boolean isLayoutRTL() {
            return mFakeRTL == null ? super.isLayoutRTL() : mFakeRTL;
        }

        public void expectLayouts(int count) {
            layoutLatch = new CountDownLatch(count);
        }

        public void waitForLayout(int seconds) throws Throwable {
            layoutLatch.await(seconds * (DEBUG ? 100 : 1), SECONDS);
            checkForMainThreadException();
            MatcherAssert.assertThat("all layouts should complete on time",
                    layoutLatch.getCount(), CoreMatchers.is(0L));
            // use a runnable to ensure RV layout is finished
            getInstrumentation().runOnMainSync(new Runnable() {
                @Override
                public void run() {
                }
            });
        }

        public void assertNoLayout(String msg, long timeout) throws Throwable {
            layoutLatch.await(timeout, TimeUnit.SECONDS);
            assertFalse(msg, layoutLatch.getCount() == 0);
        }

        @Override
        public void onLayoutChildren(RecyclerView.Recycler recycler, RecyclerView.State state) {
            String before;
            if (DEBUG) {
                before = layoutToString("before");
            } else {
                before = "enable DEBUG";
            }
            try {
                if (mOnLayoutListener != null) {
                    mOnLayoutListener.before(recycler, state);
                }
                super.onLayoutChildren(recycler, state);
                if (mOnLayoutListener != null) {
                    mOnLayoutListener.after(recycler, state);
                }
                validateChildren(before);
            } catch (Throwable t) {
                postExceptionToInstrumentation(t);
            }

            layoutLatch.countDown();
        }

        @Override
        int scrollBy(int dt, RecyclerView.Recycler recycler, RecyclerView.State state) {
            try {
                int result = super.scrollBy(dt, recycler, state);
                validateChildren();
                return result;
            } catch (Throwable t) {
                postExceptionToInstrumentation(t);
            }

            return 0;
        }

        public WrappedLayoutManager(int spanCount, int orientation) {
            super(spanCount, orientation);
        }

        ArrayList<ArrayList<View>> collectChildrenBySpan() {
            ArrayList<ArrayList<View>> viewsBySpan = new ArrayList<ArrayList<View>>();
            for (int i = 0; i < getSpanCount(); i++) {
                viewsBySpan.add(new ArrayList<View>());
            }
            for (int i = 0; i < getChildCount(); i++) {
                View view = getChildAt(i);
                LayoutParams lp
                        = (LayoutParams) view
                        .getLayoutParams();
                viewsBySpan.get(lp.mSpan.mIndex).add(view);
            }
            return viewsBySpan;
        }

        @Nullable
        @Override
        public View onFocusSearchFailed(View focused, int direction, RecyclerView.Recycler recycler,
                RecyclerView.State state) {
            View result = null;
            try {
                result = super.onFocusSearchFailed(focused, direction, recycler, state);
                validateChildren();
            } catch (Throwable t) {
                postExceptionToInstrumentation(t);
            }
            return result;
        }

        Rect getViewBounds(View view) {
            if (getOrientation() == HORIZONTAL) {
                return new Rect(
                        mPrimaryOrientation.getDecoratedStart(view),
                        mSecondaryOrientation.getDecoratedStart(view),
                        mPrimaryOrientation.getDecoratedEnd(view),
                        mSecondaryOrientation.getDecoratedEnd(view));
            } else {
                return new Rect(
                        mSecondaryOrientation.getDecoratedStart(view),
                        mPrimaryOrientation.getDecoratedStart(view),
                        mSecondaryOrientation.getDecoratedEnd(view),
                        mPrimaryOrientation.getDecoratedEnd(view));
            }
        }

        public String getBoundsLog() {
            StringBuilder sb = new StringBuilder();
            sb.append("view bounds:[start:").append(mPrimaryOrientation.getStartAfterPadding())
                    .append(",").append(" end").append(mPrimaryOrientation.getEndAfterPadding());
            sb.append("\nchildren bounds\n");
            final int childCount = getChildCount();
            for (int i = 0; i < childCount; i++) {
                View child = getChildAt(i);
                sb.append("child (ind:").append(i).append(", pos:").append(getPosition(child))
                        .append("[").append("start:").append(
                        mPrimaryOrientation.getDecoratedStart(child)).append(", end:")
                        .append(mPrimaryOrientation.getDecoratedEnd(child)).append("]\n");
            }
            return sb.toString();
        }

        public VisibleChildren traverseAndFindVisibleChildren() {
            int childCount = getChildCount();
            final VisibleChildren visibleChildren = new VisibleChildren(getSpanCount());
            final int start = mPrimaryOrientation.getStartAfterPadding();
            final int end = mPrimaryOrientation.getEndAfterPadding();
            for (int i = 0; i < childCount; i++) {
                View child = getChildAt(i);
                final int childStart = mPrimaryOrientation.getDecoratedStart(child);
                final int childEnd = mPrimaryOrientation.getDecoratedEnd(child);
                final boolean fullyVisible = childStart >= start && childEnd <= end;
                final boolean hidden = childEnd <= start || childStart >= end;
                if (hidden) {
                    continue;
                }
                final int position = getPosition(child);
                final int span = getLp(child).getSpanIndex();
                if (fullyVisible) {
                    if (position < visibleChildren.firstFullyVisiblePositions[span] ||
                            visibleChildren.firstFullyVisiblePositions[span]
                                    == RecyclerView.NO_POSITION) {
                        visibleChildren.firstFullyVisiblePositions[span] = position;
                    }

                    if (position > visibleChildren.lastFullyVisiblePositions[span]) {
                        visibleChildren.lastFullyVisiblePositions[span] = position;
                    }
                }

                if (position < visibleChildren.firstVisiblePositions[span] ||
                        visibleChildren.firstVisiblePositions[span] == RecyclerView.NO_POSITION) {
                    visibleChildren.firstVisiblePositions[span] = position;
                }

                if (position > visibleChildren.lastVisiblePositions[span]) {
                    visibleChildren.lastVisiblePositions[span] = position;
                }
                if (visibleChildren.findFirstPartialVisibleClosestToStart == null) {
                    visibleChildren.findFirstPartialVisibleClosestToStart = child;
                }
                visibleChildren.findFirstPartialVisibleClosestToEnd = child;
            }
            return visibleChildren;
        }

        Map<Item, Rect> collectChildCoordinates() throws Throwable {
            final Map<Item, Rect> items = new LinkedHashMap<Item, Rect>();
            runTestOnUiThread(new Runnable() {
                @Override
                public void run() {
                    final int childCount = getChildCount();
                    for (int i = 0; i < childCount; i++) {
                        View child = getChildAt(i);
                        // do it if and only if child is visible
                        if (child.getRight() < 0 || child.getBottom() < 0 ||
                                child.getLeft() >= getWidth() || child.getTop() >= getHeight()) {
                            // invisible children may be drawn in cases like scrolling so we should
                            // ignore them
                            continue;
                        }
                        LayoutParams lp = (LayoutParams) child
                                .getLayoutParams();
                        TestViewHolder vh = (TestViewHolder) lp.mViewHolder;
                        items.put(vh.mBoundItem, getViewBounds(child));
                    }
                }
            });
            return items;
        }


        public void setFakeRtl(Boolean fakeRtl) {
            mFakeRTL = fakeRtl;
            try {
                requestLayoutOnUIThread(mRecyclerView);
            } catch (Throwable throwable) {
                postExceptionToInstrumentation(throwable);
            }
        }

        String layoutToString(String hint) {
            StringBuilder sb = new StringBuilder();
            sb.append("LAYOUT POSITIONS AND INDICES ").append(hint).append("\n");
            for (int i = 0; i < getChildCount(); i++) {
                final View view = getChildAt(i);
                final LayoutParams layoutParams = (LayoutParams) view.getLayoutParams();
                sb.append(String.format("index: %d pos: %d top: %d bottom: %d span: %d isFull:%s",
                        i, getPosition(view),
                        mPrimaryOrientation.getDecoratedStart(view),
                        mPrimaryOrientation.getDecoratedEnd(view),
                        layoutParams.getSpanIndex(), layoutParams.isFullSpan())).append("\n");
            }
            return sb.toString();
        }

        protected void validateChildren() {
            validateChildren(null);
        }

        private void validateChildren(String msg) {
            if (getChildCount() == 0 || mRecyclerView.mState.isPreLayout()) {
                return;
            }
            final int dir = mShouldReverseLayout ? -1 : 1;
            int i = 0;
            int pos = -1;
            while (i < getChildCount()) {
                LayoutParams lp = (LayoutParams) getChildAt(i).getLayoutParams();
                if (lp.isItemRemoved()) {
                    i++;
                    continue;
                }
                pos = getPosition(getChildAt(i));
                break;
            }
            if (pos == -1) {
                return;
            }
            while (++i < getChildCount()) {
                LayoutParams lp = (LayoutParams) getChildAt(i).getLayoutParams();
                if (lp.isItemRemoved()) {
                    continue;
                }
                pos += dir;
                if (getPosition(getChildAt(i)) != pos) {
                    throw new RuntimeException("INVALID POSITION FOR CHILD " + i + "\n" +
                            layoutToString("ERROR") + "\n msg:" + msg);
                }
            }
        }
    }

    class GridTestAdapter extends TestAdapter {

        int mOrientation;
        int mRecyclerViewWidth;
        int mRecyclerViewHeight;
        Integer mSizeReference = null;

        // original ids of items that should be full span
        HashSet<Integer> mFullSpanItems = new HashSet<Integer>();

        protected boolean mViewsHaveEqualSize = false; // size in the scrollable direction

        protected OnBindCallback mOnBindCallback;

        GridTestAdapter(int count, int orientation) {
            super(count);
            mOrientation = orientation;
        }

        @Override
        public TestViewHolder onCreateViewHolder(ViewGroup parent,
                int viewType) {
            mRecyclerViewWidth = parent.getWidth();
            mRecyclerViewHeight = parent.getHeight();
            TestViewHolder vh = super.onCreateViewHolder(parent, viewType);
            if (mOnBindCallback != null) {
                mOnBindCallback.onCreatedViewHolder(vh);
            }
            return vh;
        }

        @Override
        public void offsetOriginalIndices(int start, int offset) {
            if (mFullSpanItems.size() > 0) {
                HashSet<Integer> old = mFullSpanItems;
                mFullSpanItems = new HashSet<Integer>();
                for (Integer i : old) {
                    if (i < start) {
                        mFullSpanItems.add(i);
                    } else if (offset > 0 || (start + Math.abs(offset)) <= i) {
                        mFullSpanItems.add(i + offset);
                    } else if (DEBUG) {
                        Log.d(TAG, "removed full span item " + i);
                    }
                }
            }
            super.offsetOriginalIndices(start, offset);
        }

        @Override
        protected void moveInUIThread(int from, int to) {
            boolean setAsFullSpanAgain = mFullSpanItems.contains(from);
            super.moveInUIThread(from, to);
            if (setAsFullSpanAgain) {
                mFullSpanItems.add(to);
            }
        }

        @Override
        public void onBindViewHolder(TestViewHolder holder,
                int position) {
            if (mSizeReference == null) {
                mSizeReference = mOrientation == OrientationHelper.HORIZONTAL ? mRecyclerViewWidth
                        / AVG_ITEM_PER_VIEW : mRecyclerViewHeight / AVG_ITEM_PER_VIEW;
            }
            super.onBindViewHolder(holder, position);
            Item item = mItems.get(position);
            RecyclerView.LayoutParams lp = (RecyclerView.LayoutParams) holder.itemView
                    .getLayoutParams();
            if (lp instanceof StaggeredGridLayoutManager.LayoutParams) {
                ((StaggeredGridLayoutManager.LayoutParams) lp)
                        .setFullSpan(mFullSpanItems.contains(item.mAdapterIndex));
            } else {
                StaggeredGridLayoutManager.LayoutParams slp
                        = (StaggeredGridLayoutManager.LayoutParams) mLayoutManager
                        .generateDefaultLayoutParams();
                holder.itemView.setLayoutParams(slp);
                slp.setFullSpan(mFullSpanItems.contains(item.mAdapterIndex));
                lp = slp;
            }

            if (mOnBindCallback == null || mOnBindCallback.assignRandomSize()) {
                final int minSize = mViewsHaveEqualSize ? mSizeReference :
                        mSizeReference + 20 * (item.mId % 10);
                if (mOrientation == OrientationHelper.HORIZONTAL) {
                    holder.itemView.setMinimumWidth(minSize);
                } else {
                    holder.itemView.setMinimumHeight(minSize);
                }
                lp.topMargin = 3;
                lp.leftMargin = 5;
                lp.rightMargin = 7;
                lp.bottomMargin = 9;
            }

            if (mOnBindCallback != null) {
                mOnBindCallback.onBoundItem(holder, position);
            }
        }
    }
}
