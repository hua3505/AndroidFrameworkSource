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

package android.support.design.widget;

import org.junit.Test;

import android.content.Context;
import android.support.design.test.R;
import android.support.design.widget.BottomSheetBehavior;
import android.support.design.widget.BottomSheetDialog;
import android.support.test.InstrumentationRegistry;
import android.support.test.espresso.Espresso;
import android.support.test.espresso.action.ViewActions;
import android.support.test.espresso.assertion.ViewAssertions;
import android.support.test.espresso.matcher.ViewMatchers;
import android.support.v7.widget.AppCompatTextView;
import android.test.suitebuilder.annotation.MediumTest;
import android.widget.FrameLayout;

import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.lessThan;

public class BottomSheetDialogTest extends
        BaseInstrumentationTestCase<BottomSheetDialogActivity> {

    private BottomSheetDialog mDialog;

    public BottomSheetDialogTest() {
        super(BottomSheetDialogActivity.class);
    }

    @Test
    @MediumTest
    public void testBasicDialogSetup() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(new Runnable() {
            @Override
            public void run() {
                Context context = mActivityTestRule.getActivity();
                mDialog = new BottomSheetDialog(context);
                AppCompatTextView text = new AppCompatTextView(context);
                StringBuilder builder = new StringBuilder();
                builder.append("It is fine today. ");
                text.setText(builder);
                mDialog.setContentView(text);
                mDialog.show();
                // Confirms that the dialog is shown
                assertThat(mDialog.isShowing(), is(true));
                FrameLayout bottomSheet = (FrameLayout) mDialog
                        .findViewById(R.id.design_bottom_sheet);
                assertThat(bottomSheet, is(notNullValue()));
                BottomSheetBehavior<FrameLayout> behavior = BottomSheetBehavior.from(bottomSheet);
                assertThat(behavior, is(notNullValue()));
            }
        });
        // Click outside the bottom sheet
        Espresso.onView(ViewMatchers.withId(R.id.touch_outside))
                .perform(ViewActions.click());
        InstrumentationRegistry.getInstrumentation().runOnMainSync(new Runnable() {
            @Override
            public void run() {
                // Confirm that the dialog is no longer shown
                assertThat(mDialog.isShowing(), is(false));
            }
        });
    }

    @Test
    @MediumTest
    public void testShortDialog() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(new Runnable() {
            @Override
            public void run() {
                Context context = mActivityTestRule.getActivity();
                mDialog = new BottomSheetDialog(context);
                AppCompatTextView text = new AppCompatTextView(context);
                StringBuilder builder = new StringBuilder();
                builder.append("It is fine today. ");
                text.setText(builder);
                mDialog.setContentView(text);
                mDialog.show();
            }
        });
        // This ensures that the views are laid out before assertions below
        Espresso.onView(ViewMatchers.withId(R.id.design_bottom_sheet))
                .check(ViewAssertions.matches(ViewMatchers.isDisplayed()));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(new Runnable() {
            @Override
            public void run() {
                FrameLayout bottomSheet = (FrameLayout) mDialog
                        .findViewById(R.id.design_bottom_sheet);
                CoordinatorLayout coordinator = (CoordinatorLayout) bottomSheet.getParent();
                BottomSheetBehavior<FrameLayout> behavior = BottomSheetBehavior.from(bottomSheet);
                assertThat(bottomSheet, is(notNullValue()));
                assertThat(coordinator, is(notNullValue()));
                assertThat(behavior, is(notNullValue()));
                // This bottom sheet is shorter than the peek height
                assertThat(bottomSheet.getHeight(), is(lessThan(behavior.getPeekHeight())));
                // Confirm that the bottom sheet is bottom-aligned
                assertThat(bottomSheet.getTop(),
                        is(coordinator.getHeight() - bottomSheet.getHeight()));
            }
        });
    }

}

