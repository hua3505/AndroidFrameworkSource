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
package android.support.v4.content;

import org.junit.Before;
import org.junit.Test;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.support.v4.BaseInstrumentationTestCase;
import android.support.v4.ThemedYellowActivity;
import android.support.v4.test.R;
import android.support.v4.testutils.TestUtils;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.DisplayMetrics;

import static org.junit.Assert.assertEquals;

@SmallTest
public class ContextCompatTest extends BaseInstrumentationTestCase<ThemedYellowActivity> {
    private Context mContext;

    public ContextCompatTest() {
        super(ThemedYellowActivity.class);
    }

    @Before
    public void setup() {
        mContext = mActivityTestRule.getActivity();
    }

    @Test
    public void testGetColor() throws Throwable {
        assertEquals("Unthemed color load", 0xFFFF8090,
                ContextCompat.getColor(mContext, R.color.text_color));

        if (Build.VERSION.SDK_INT >= 23) {
            // The following test is only expected to pass on v23+ devices. The result of
            // calling theme-aware getColor() in pre-v23 is undefined.
            assertEquals("Themed yellow color load",
                    ContextCompat.getColor(mContext, R.color.simple_themed_selector),
                    0xFFF0B000);
        }
    }

    @Test
    public void testGetColorStateList() throws Throwable {
        ColorStateList unthemedColorStateList =
                ContextCompat.getColorStateList(mContext, R.color.complex_unthemed_selector);
        assertEquals("Unthemed color state list load: default", 0xFF70A0C0,
                unthemedColorStateList.getDefaultColor());
        assertEquals("Unthemed color state list load: focused", 0xFF70B0F0,
                unthemedColorStateList.getColorForState(
                        new int[]{android.R.attr.state_focused}, 0));
        assertEquals("Unthemed color state list load: pressed", 0xFF6080B0,
                unthemedColorStateList.getColorForState(
                        new int[]{android.R.attr.state_pressed}, 0));

        if (Build.VERSION.SDK_INT >= 23) {
            // The following tests are only expected to pass on v23+ devices. The result of
            // calling theme-aware getColorStateList() in pre-v23 is undefined.
            ColorStateList themedYellowColorStateList =
                    ContextCompat.getColorStateList(mContext, R.color.complex_themed_selector);
            assertEquals("Themed yellow color state list load: default", 0xFFF0B000,
                    themedYellowColorStateList.getDefaultColor());
            assertEquals("Themed yellow color state list load: focused", 0xFFF0A020,
                    themedYellowColorStateList.getColorForState(
                            new int[]{android.R.attr.state_focused}, 0));
            assertEquals("Themed yellow color state list load: pressed", 0xFFE0A040,
                    themedYellowColorStateList.getColorForState(
                            new int[]{android.R.attr.state_pressed}, 0));
        }
    }

    @Test
    public void testGetDrawable() throws Throwable {
        Drawable unthemedDrawable =
                ContextCompat.getDrawable(mContext, R.drawable.test_drawable_red);
        TestUtils.assertAllPixelsOfColor("Unthemed drawable load",
                unthemedDrawable, mContext.getResources().getColor(R.color.test_red));

        if (Build.VERSION.SDK_INT >= 23) {
            // The following test is only expected to pass on v23+ devices. The result of
            // calling theme-aware getDrawable() in pre-v23 is undefined.
            Drawable themedYellowDrawable =
                    ContextCompat.getDrawable(mContext, R.drawable.themed_drawable);
            TestUtils.assertAllPixelsOfColor("Themed yellow drawable load",
                    themedYellowDrawable, 0xFFF0B000);
        }
    }

    @Test
    public void testDrawableConfigurationWorkaround() throws Throwable {
        final int expectedWidth = scaleFromDensity(7, DisplayMetrics.DENSITY_LOW,
                mContext.getResources().getDisplayMetrics().densityDpi);

        // Ensure we retrieve the correct drawable configuration. Specifically,
        // this tests a workaround for a bug in drawable configuration that
        // exists on API < 16 for references to drawables.
        Drawable referencedDrawable = ContextCompat.getDrawable(mContext,
                R.drawable.aliased_drawable);
        assertEquals("Drawable configuration does not match DisplayMetrics",
                expectedWidth, referencedDrawable.getIntrinsicWidth());
    }

    private static int scaleFromDensity(int size, int sdensity, int tdensity) {
        if (sdensity == tdensity) {
            return size;
        }

        // Scale by tdensity / sdensity, rounding up.
        return ((size * tdensity) + (sdensity >> 1)) / sdensity;
    }

    @Test(expected = IllegalArgumentException.class)
    public void testCheckSelfPermissionNull() {
        ContextCompat.checkSelfPermission(mContext, null);
    }

    @Test
    public void testCheckSelfPermission() {
        assertEquals("Vibrate permission granted", PackageManager.PERMISSION_GRANTED,
                ContextCompat.checkSelfPermission(mContext,
                        android.Manifest.permission.VIBRATE));
        assertEquals("Wake lock permission granted", PackageManager.PERMISSION_GRANTED,
                ContextCompat.checkSelfPermission(mContext,
                        android.Manifest.permission.WAKE_LOCK));

        if (Build.VERSION.SDK_INT >= 23) {
            // As documented in http://developer.android.com/training/permissions/requesting.html
            // starting in Android M (v23) dangerous permissions are not granted automactically
            // to apps targeting SDK 23 even if those are defined in the manifest.
            // This is why the following permissions are expected to be denied.
            assertEquals("Read contacts permission granted", PackageManager.PERMISSION_DENIED,
                    ContextCompat.checkSelfPermission(mContext,
                            android.Manifest.permission.READ_CONTACTS));
            assertEquals("Write contacts permission granted", PackageManager.PERMISSION_DENIED,
                    ContextCompat.checkSelfPermission(mContext,
                            android.Manifest.permission.WRITE_CONTACTS));
        } else {
            // And on older devices declared dangerous permissions are expected to be granted.
            assertEquals("Read contacts permission denied", PackageManager.PERMISSION_GRANTED,
                    ContextCompat.checkSelfPermission(mContext,
                            android.Manifest.permission.READ_CONTACTS));
            assertEquals("Write contacts permission denied", PackageManager.PERMISSION_GRANTED,
                    ContextCompat.checkSelfPermission(mContext,
                            android.Manifest.permission.WRITE_CONTACTS));
        }

        // The following permissions (normal and dangerous) are expected to be denied as they are
        // not declared in our manifest.
        assertEquals("Access network state permission denied", PackageManager.PERMISSION_DENIED,
                ContextCompat.checkSelfPermission(mContext,
                        android.Manifest.permission.ACCESS_NETWORK_STATE));
        assertEquals("Bluetooth permission denied", PackageManager.PERMISSION_DENIED,
                ContextCompat.checkSelfPermission(mContext,
                        android.Manifest.permission.BLUETOOTH));
        assertEquals("Call phone permission denied", PackageManager.PERMISSION_DENIED,
                ContextCompat.checkSelfPermission(mContext,
                        android.Manifest.permission.CALL_PHONE));
        assertEquals("Delete packages permission denied", PackageManager.PERMISSION_DENIED,
                ContextCompat.checkSelfPermission(mContext,
                        android.Manifest.permission.DELETE_PACKAGES));
    }
}