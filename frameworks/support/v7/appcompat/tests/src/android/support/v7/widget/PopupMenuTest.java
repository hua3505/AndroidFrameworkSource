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

import android.support.test.InstrumentationRegistry;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
import org.hamcrest.TypeSafeMatcher;
import org.junit.Before;
import org.junit.Test;

import android.app.Instrumentation;
import android.content.res.Resources;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.support.test.espresso.Root;
import android.support.test.espresso.UiController;
import android.support.test.espresso.ViewAction;
import android.support.v7.app.BaseInstrumentationTestCase;
import android.support.v7.appcompat.test.R;
import android.test.suitebuilder.annotation.SmallTest;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import android.widget.Button;
import android.widget.FrameLayout;

import static android.support.test.espresso.Espresso.onView;
import static android.support.test.espresso.action.ViewActions.click;
import static android.support.test.espresso.assertion.ViewAssertions.doesNotExist;
import static android.support.test.espresso.assertion.ViewAssertions.matches;
import static android.support.test.espresso.matcher.RootMatchers.isPlatformPopup;
import static android.support.test.espresso.matcher.RootMatchers.withDecorView;
import static android.support.test.espresso.matcher.ViewMatchers.isDisplayed;
import static android.support.test.espresso.matcher.ViewMatchers.withClassName;
import static android.support.test.espresso.matcher.ViewMatchers.withId;
import static android.support.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNot.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.*;

public class PopupMenuTest extends BaseInstrumentationTestCase<PopupTestActivity> {
    // Since PopupMenu doesn't expose any access to the underlying
    // implementation (like ListPopupWindow.getListView), we're relying on the
    // class name of the list view from MenuPopupWindow that is being used
    // in PopupMenu. This is not the cleanest, but it's not making any assumptions
    // on the platform-specific details of the popup windows.
    private static final String DROP_DOWN_CLASS_NAME =
            "android.support.v7.widget.MenuPopupWindow$MenuDropDownListView";
    private FrameLayout mContainer;

    private Button mButton;

    private PopupMenu mPopupMenu;

    private Resources mResources;

    private View mMainDecorView;

    public PopupMenuTest() {
        super(PopupTestActivity.class);
    }

    @Before
    public void setUp() throws Exception {
        final PopupTestActivity activity = mActivityTestRule.getActivity();
        mContainer = (FrameLayout) activity.findViewById(R.id.container);
        mButton = (Button) mContainer.findViewById(R.id.test_button);
        mResources = mActivityTestRule.getActivity().getResources();
        mMainDecorView = mActivityTestRule.getActivity().getWindow().getDecorView();
    }

    @Test
    @SmallTest
    public void testBasicContent() {
        final Builder menuBuilder = new Builder();
        menuBuilder.wireToActionButton();

        onView(withId(R.id.test_button)).perform(click());
        assertNotNull("Popup menu created", mPopupMenu);
        // Unlike ListPopupWindow, PopupMenu doesn't have an API to check whether it is showing.
        // Use a custom matcher to check the visibility of the drop down list view instead.
        onView(withClassName(Matchers.is(DROP_DOWN_CLASS_NAME)))
                .inRoot(isPlatformPopup()).check(matches(isDisplayed()));

        // Note that MenuItem.isVisible() refers to the current "data" visibility state
        // and not the "on screen" visibility state. This is why we're testing the display
        // visibility of our main and sub menu items.

        onView(withText(mResources.getString(R.string.popup_menu_highlight)))
                .inRoot(withDecorView(not(is(mMainDecorView))))
                .check(matches(isDisplayed()));
        onView(withText(mResources.getString(R.string.popup_menu_edit)))
                .inRoot(withDecorView(not(is(mMainDecorView))))
                .check(matches(isDisplayed()));
        onView(withText(mResources.getString(R.string.popup_menu_delete)))
                .inRoot(withDecorView(not(is(mMainDecorView))))
                .check(matches(isDisplayed()));
        onView(withText(mResources.getString(R.string.popup_menu_ignore)))
                .inRoot(withDecorView(not(is(mMainDecorView))))
                .check(matches(isDisplayed()));
        onView(withText(mResources.getString(R.string.popup_menu_share)))
                .inRoot(withDecorView(not(is(mMainDecorView))))
                .check(matches(isDisplayed()));
        onView(withText(mResources.getString(R.string.popup_menu_print)))
                .inRoot(withDecorView(not(is(mMainDecorView))))
                .check(matches(isDisplayed()));

        // Share submenu items should not be visible
        onView(withText(mResources.getString(R.string.popup_menu_share_email)))
                .inRoot(withDecorView(not(is(mMainDecorView))))
                .check(doesNotExist());
        onView(withText(mResources.getString(R.string.popup_menu_share_circles)))
                .inRoot(withDecorView(not(is(mMainDecorView))))
                .check(doesNotExist());
    }

    /**
     * Returns the location of our popup menu in its window.
     */
    private int[] getPopupLocationInWindow() {
        final int[] location = new int[2];
        onView(withClassName(Matchers.is(DROP_DOWN_CLASS_NAME)))
                .inRoot(isPlatformPopup()).perform(new ViewAction() {
            @Override
            public Matcher<View> getConstraints() {
                return isDisplayed();
            }

            @Override
            public String getDescription() {
                return "Popup matcher";
            }

            @Override
            public void perform(UiController uiController, View view) {
                view.getLocationInWindow(location);
            }
        });
        return location;
    }

    /**
     * Returns the location of our popup menu on the screen.
     */
    private int[] getPopupLocationOnScreen() {
        final int[] location = new int[2];
        onView(withClassName(Matchers.is(DROP_DOWN_CLASS_NAME)))
                .inRoot(isPlatformPopup()).perform(new ViewAction() {
            @Override
            public Matcher<View> getConstraints() {
                return isDisplayed();
            }

            @Override
            public String getDescription() {
                return "Popup matcher";
            }

            @Override
            public void perform(UiController uiController, View view) {
                view.getLocationOnScreen(location);
            }
        });
        return location;
    }

    /**
     * Returns the combined padding around the content of our popup menu.
     */
    private Rect getPopupPadding() {
        final Rect result = new Rect();
        onView(withClassName(Matchers.is(DROP_DOWN_CLASS_NAME)))
                .inRoot(isPlatformPopup()).perform(new ViewAction() {
            @Override
            public Matcher<View> getConstraints() {
                return isDisplayed();
            }

            @Override
            public String getDescription() {
                return "Popup matcher";
            }

            @Override
            public void perform(UiController uiController, View view) {
                // Traverse the parent hierarchy and combine all their paddings
                result.setEmpty();
                final Rect current = new Rect();
                while (true) {
                    ViewParent parent = view.getParent();
                    if (parent == null || !(parent instanceof View)) {
                        return;
                    }

                    view = (View) parent;
                    Drawable currentBackground = view.getBackground();
                    if (currentBackground != null) {
                        currentBackground.getPadding(current);
                        result.left += current.left;
                        result.right += current.right;
                        result.top += current.top;
                        result.bottom += current.bottom;
                    }
                }
            }
        });
        return result;
    }

    /**
     * Returns a root matcher that matches roots that have window focus on their decor view.
     */
    private static Matcher<Root> hasWindowFocus() {
        return new TypeSafeMatcher<Root>() {
            @Override
            public void describeTo(Description description) {
                description.appendText("has window focus");
            }

            @Override
            public boolean matchesSafely(Root root) {
                View rootView = root.getDecorView();
                return rootView.hasWindowFocus();
            }
        };
    }

    @Test
    @SmallTest
    public void testAnchoring() {
        Builder menuBuilder = new Builder();
        menuBuilder.wireToActionButton();

        onView(withId(R.id.test_button)).perform(click());

        final int[] anchorOnScreenXY = new int[2];
        final int[] popupOnScreenXY = getPopupLocationOnScreen();
        final int[] popupInWindowXY = getPopupLocationInWindow();
        final Rect popupPadding = getPopupPadding();

        mButton.getLocationOnScreen(anchorOnScreenXY);

        // Allow for off-by-one mismatch in anchoring
        assertEquals("Anchoring X", anchorOnScreenXY[0] + popupInWindowXY[0],
                popupOnScreenXY[0], 1);
        assertEquals("Anchoring Y", anchorOnScreenXY[1] + popupInWindowXY[1] + mButton.getHeight(),
                popupOnScreenXY[1], 1);
    }

    @Test
    @SmallTest
    public void testDismissalViaAPI() {
        Builder menuBuilder = new Builder().withDismissListener();
        menuBuilder.wireToActionButton();

        onView(withId(R.id.test_button)).perform(click());

        // Since PopupMenu is not a View, we can't use Espresso's view actions to invoke
        // the dismiss() API
        InstrumentationRegistry.getInstrumentation().runOnMainSync(new Runnable() {
            @Override
            public void run() {
                mPopupMenu.dismiss();
            }
        });

        verify(menuBuilder.mOnDismissListener, times(1)).onDismiss(mPopupMenu);

        // Unlike ListPopupWindow, PopupMenu doesn't have an API to check whether it is showing.
        // Use a custom matcher to check the visibility of the drop down list view instead.
        onView(withClassName(Matchers.is(DROP_DOWN_CLASS_NAME))).check(doesNotExist());
    }

    @Test
    @SmallTest
    public void testDismissalViaTouch() throws Throwable {
        Builder menuBuilder = new Builder().withDismissListener();
        menuBuilder.wireToActionButton();

        onView(withId(R.id.test_button)).perform(click());

        // Determine the location of the popup on the screen so that we can emulate
        // a tap outside of its bounds to dismiss it
        final int[] popupOnScreenXY = getPopupLocationOnScreen();
        final Rect popupPadding = getPopupPadding();


        int emulatedTapX = popupOnScreenXY[0] - popupPadding.left - 20;
        int emulatedTapY = popupOnScreenXY[1] + popupPadding.top + 20;

        // The logic below uses Instrumentation to emulate a tap outside the bounds of the
        // displayed popup menu. This tap is then treated by the framework to be "split" as
        // the ACTION_OUTSIDE for the popup itself, as well as DOWN / MOVE / UP for the underlying
        // view root if the popup is not modal.
        // It is not correct to emulate these two sequences separately in the test, as it
        // wouldn't emulate the user-facing interaction for this test. Note that usage
        // of Instrumentation is necessary here since Espresso's actions operate at the level
        // of view or data. Also, we don't want to use View.dispatchTouchEvent directly as
        // that would require emulation of two separate sequences as well.

        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

        // Inject DOWN event
        long downTime = SystemClock.uptimeMillis();
        MotionEvent eventDown = MotionEvent.obtain(
                downTime, downTime, MotionEvent.ACTION_DOWN, emulatedTapX, emulatedTapY, 1);
        instrumentation.sendPointerSync(eventDown);

        // Inject MOVE event
        long moveTime = SystemClock.uptimeMillis();
        MotionEvent eventMove = MotionEvent.obtain(
                moveTime, moveTime, MotionEvent.ACTION_MOVE, emulatedTapX, emulatedTapY, 1);
        instrumentation.sendPointerSync(eventMove);

        // Inject UP event
        long upTime = SystemClock.uptimeMillis();
        MotionEvent eventUp = MotionEvent.obtain(
                upTime, upTime, MotionEvent.ACTION_UP, emulatedTapX, emulatedTapY, 1);
        instrumentation.sendPointerSync(eventUp);

        // Wait for the system to process all events in the queue
        instrumentation.waitForIdleSync();

        // At this point our popup should not be showing and should have notified its
        // dismiss listener
        verify(menuBuilder.mOnDismissListener, times(1)).onDismiss(mPopupMenu);
        onView(withClassName(Matchers.is(DROP_DOWN_CLASS_NAME))).check(doesNotExist());
    }

    @Test
    @SmallTest
    public void testSimpleMenuItemClickViaEvent() {
        Builder menuBuilder = new Builder().withMenuItemClickListener();
        menuBuilder.wireToActionButton();

        onView(withId(R.id.test_button)).perform(click());

        // Verify that our menu item click listener hasn't been called yet
        verify(menuBuilder.mOnMenuItemClickListener, never()).onMenuItemClick(any(MenuItem.class));

        onView(withText(mResources.getString(R.string.popup_menu_delete)))
                .inRoot(withDecorView(not(is(mMainDecorView))))
                .perform(click());

        // Verify that out menu item click listener has been called with the expected menu item
        verify(menuBuilder.mOnMenuItemClickListener, times(1)).onMenuItemClick(
                mPopupMenu.getMenu().findItem(R.id.action_delete));

        // Popup menu should be automatically dismissed on selecting an item
        onView(withClassName(Matchers.is(DROP_DOWN_CLASS_NAME))).check(doesNotExist());
    }

    @Test
    @SmallTest
    public void testSimpleMenuItemClickViaAPI() {
        Builder menuBuilder = new Builder().withMenuItemClickListener();
        menuBuilder.wireToActionButton();

        onView(withId(R.id.test_button)).perform(click());

        // Verify that our menu item click listener hasn't been called yet
        verify(menuBuilder.mOnMenuItemClickListener, never()).onMenuItemClick(any(MenuItem.class));

        InstrumentationRegistry.getInstrumentation().runOnMainSync(new Runnable() {
            @Override
            public void run() {
                mPopupMenu.getMenu().performIdentifierAction(R.id.action_highlight, 0);
            }
        });

        // Verify that out menu item click listener has been called with the expected menu item
        verify(menuBuilder.mOnMenuItemClickListener, times(1)).onMenuItemClick(
                mPopupMenu.getMenu().findItem(R.id.action_highlight));

        // Popup menu should be automatically dismissed on selecting an item
        onView(withClassName(Matchers.is(DROP_DOWN_CLASS_NAME))).check(doesNotExist());
    }

    @Test
    @SmallTest
    public void testSubMenuClicksViaEvent() throws Throwable {
        Builder menuBuilder = new Builder().withMenuItemClickListener();
        menuBuilder.wireToActionButton();

        onView(withId(R.id.test_button)).perform(click());

        // Verify that our menu item click listener hasn't been called yet
        verify(menuBuilder.mOnMenuItemClickListener, never()).onMenuItemClick(any(MenuItem.class));

        onView(withText(mResources.getString(R.string.popup_menu_share)))
                .inRoot(withDecorView(not(is(mMainDecorView))))
                .perform(click());

        // Verify that out menu item click listener has been called with the expected menu item
        verify(menuBuilder.mOnMenuItemClickListener, times(1)).onMenuItemClick(
                mPopupMenu.getMenu().findItem(R.id.action_share));

        // Sleep for a bit to allow the menu -> submenu transition to complete
        Thread.sleep(1000);

        // At this point we should now have our sub-menu displayed. At this point on newer
        // platform versions (L+) we have two view roots on the screen - one for the main popup
        // menu and one for the submenu that has just been activated. If we only use the
        // logic based on decor view, Espresso will time out on waiting for the first root
        // to acquire window focus. This is why from this point on in this test we are using
        // two root conditions to detect the submenu - one with decor view not being the same
        // as the decor view of our main activity window, and the other that checks for window
        // focus.

        // Unlike ListPopupWindow, PopupMenu doesn't have an API to check whether it is showing.
        // Use a custom matcher to check the visibility of the drop down list view instead.
        onView(withClassName(Matchers.is(DROP_DOWN_CLASS_NAME)))
                .inRoot(allOf(withDecorView(not(is(mMainDecorView))), hasWindowFocus()))
                .check(matches(isDisplayed()));

        // Note that MenuItem.isVisible() refers to the current "data" visibility state
        // and not the "on screen" visibility state. This is why we're testing the display
        // visibility of our main and sub menu items.

        // Share submenu items should now be visible
        onView(withText(mResources.getString(R.string.popup_menu_share_email)))
                .inRoot(allOf(withDecorView(not(is(mMainDecorView))), hasWindowFocus()))
                .check(matches(isDisplayed()));
        onView(withText(mResources.getString(R.string.popup_menu_share_circles)))
                .inRoot(allOf(withDecorView(not(is(mMainDecorView))), hasWindowFocus()))
                .check(matches(isDisplayed()));

        // Now click an item in the sub-menu
        onView(withText(mResources.getString(R.string.popup_menu_share_circles)))
                .inRoot(allOf(withDecorView(not(is(mMainDecorView))), hasWindowFocus()))
                .perform(click());

        // Verify that out menu item click listener has been called with the expected menu item
        verify(menuBuilder.mOnMenuItemClickListener, times(1)).onMenuItemClick(
                mPopupMenu.getMenu().findItem(R.id.action_share_circles));

        // Popup menu should be automatically dismissed on selecting an item in the submenu
        onView(withClassName(Matchers.is(DROP_DOWN_CLASS_NAME))).check(doesNotExist());
    }

    @Test
    @SmallTest
    public void testSubMenuClicksViaAPI() throws Throwable {
        Builder menuBuilder = new Builder().withMenuItemClickListener();
        menuBuilder.wireToActionButton();

        onView(withId(R.id.test_button)).perform(click());

        // Verify that our menu item click listener hasn't been called yet
        verify(menuBuilder.mOnMenuItemClickListener, never()).onMenuItemClick(any(MenuItem.class));

        InstrumentationRegistry.getInstrumentation().runOnMainSync(new Runnable() {
            @Override
            public void run() {
                mPopupMenu.getMenu().performIdentifierAction(R.id.action_share, 0);
            }
        });

        // Verify that out menu item click listener has been called with the expected menu item
        verify(menuBuilder.mOnMenuItemClickListener, times(1)).onMenuItemClick(
                mPopupMenu.getMenu().findItem(R.id.action_share));

        // Sleep for a bit to allow the menu -> submenu transition to complete
        Thread.sleep(1000);

        // At this point we should now have our sub-menu displayed. At this point on newer
        // platform versions (L+) we have two view roots on the screen - one for the main popup
        // menu and one for the submenu that has just been activated. If we only use the
        // logic based on decor view, Espresso will time out on waiting for the first root
        // to acquire window focus. This is why from this point on in this test we are using
        // two root conditions to detect the submenu - one with decor view not being the same
        // as the decor view of our main activity window, and the other that checks for window
        // focus.

        // Unlike ListPopupWindow, PopupMenu doesn't have an API to check whether it is showing.
        // Use a custom matcher to check the visibility of the drop down list view instead.
        onView(withClassName(Matchers.is(DROP_DOWN_CLASS_NAME)))
                .inRoot(allOf(withDecorView(not(is(mMainDecorView))), hasWindowFocus()))
                .check(matches(isDisplayed()));

        // Note that MenuItem.isVisible() refers to the current "data" visibility state
        // and not the "on screen" visibility state. This is why we're testing the display
        // visibility of our main and sub menu items.

        // Share submenu items should now be visible
        onView(withText(mResources.getString(R.string.popup_menu_share_email)))
                .inRoot(allOf(withDecorView(not(is(mMainDecorView))), hasWindowFocus()))
                .check(matches(isDisplayed()));
        onView(withText(mResources.getString(R.string.popup_menu_share_circles)))
                .inRoot(allOf(withDecorView(not(is(mMainDecorView))), hasWindowFocus()))
                .check(matches(isDisplayed()));

        // Now ask the share submenu to perform an action on its specific menu item
        InstrumentationRegistry.getInstrumentation().runOnMainSync(new Runnable() {
            @Override
            public void run() {
                mPopupMenu.getMenu().findItem(R.id.action_share).getSubMenu().
                        performIdentifierAction(R.id.action_share_email, 0);
            }
        });

        // Verify that out menu item click listener has been called with the expected menu item
        verify(menuBuilder.mOnMenuItemClickListener, times(1)).onMenuItemClick(
                mPopupMenu.getMenu().findItem(R.id.action_share_email));

        // Popup menu should be automatically dismissed on selecting an item in the submenu
        onView(withClassName(Matchers.is(DROP_DOWN_CLASS_NAME))).check(doesNotExist());
    }

    /**
     * Inner helper class to configure an instance of <code>PopupMenu</code> for the
     * specific test. The main reason for its existence is that once a popup menu is shown
     * with the show() method, most of its configuration APIs are no-ops. This means that
     * we can't add logic that is specific to a certain test once it's shown and we have a
     * reference to a displayed PopupMenu.
     */
    public class Builder {
        private boolean mHasDismissListener;
        private boolean mHasMenuItemClickListener;

        private PopupMenu.OnMenuItemClickListener mOnMenuItemClickListener;
        private PopupMenu.OnDismissListener mOnDismissListener;

        public Builder withMenuItemClickListener() {
            mHasMenuItemClickListener = true;
            return this;
        }

        public Builder withDismissListener() {
            mHasDismissListener = true;
            return this;
        }

        private void show() {
            mPopupMenu = new PopupMenu(mContainer.getContext(), mButton);
            final MenuInflater menuInflater = mPopupMenu.getMenuInflater();
            menuInflater.inflate(R.menu.popup_menu, mPopupMenu.getMenu());

            if (mHasMenuItemClickListener) {
                // Register a mock listener to be notified when a menu item in our popup menu has
                // been clicked.
                mOnMenuItemClickListener = mock(PopupMenu.OnMenuItemClickListener.class);
                mPopupMenu.setOnMenuItemClickListener(mOnMenuItemClickListener);
            }

            if (mHasDismissListener) {
                // Register a mock listener to be notified when our popup menu is dismissed.
                mOnDismissListener = mock(PopupMenu.OnDismissListener.class);
                mPopupMenu.setOnDismissListener(mOnDismissListener);
            }

            // Show the popup menu
            mPopupMenu.show();
        }

        public void wireToActionButton() {
            mButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    show();
                }
            });
        }
    }
}
