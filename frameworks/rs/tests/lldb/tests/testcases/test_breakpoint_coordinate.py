# Copyright (C) 2016 The Android Open Source Project
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

'''Module that contains the test TestBreakpointCoordinate.'''

from __future__ import absolute_import

from harness.test_base_remote import TestBaseRemote
from harness.decorators import (
    wimpy,
    ordered_test,
    cpp_only_test,
)


class TestBreakpointCoordinate(TestBaseRemote):
    '''Tests breaking on a specific kernel invocation.

    Uses the -c option to specify the coordinate.
    '''

    bundle_target = {
        'java': 'Allocations',
        'jni': 'JNIAllocations',
        'cpp': 'CppAllocations'
    }

    def setup(self, android):
        '''This test requires to be run on one thread.

        Args:
            android: The android_util module.
        '''
        android.push_prop('debug.rs.max-threads', 1)

    def teardown(self, android):
        '''Reset the number of RS threads to the previous value.

        Args:
            android: The android_util module.
        '''
        android.pop_prop('debug.rs.max-threads')

    def _check_coordinates(self, x_coord, y_coord, z_coord, kernel):
        '''Run lldb commands to check that coordinates match expected values.

        Args:
            (x_coord, y_coord, z_coord): The expected coordinates.
            kernel: String that is the name of the kernel function.

        Raises:
            TestFail: One of the lldb commands did not provide the expected
                      output.
        '''
        self.try_command('bt', ['stop reason = breakpoint', 'frame #0:', 'librs.allocs.so`%s' % kernel])

        self.try_command('language renderscript kernel coordinate',
                         ['Coordinate: (%d, %d, %d)'
                          % (x_coord, y_coord, z_coord)])

    @wimpy
    @ordered_test(0)
    def test_breakpoint_coordinate_2d_swizzle_kernel(self):
        # pylint: disable=line-too-long

        # test conditional coordinate in two dimensions
        # breakpoint 1
        self.try_command('language renderscript kernel breakpoint set swizzle_kernel -c 3,7',
                         ['Conditional kernel breakpoint on coordinate 3, 7, 0',
                          'Breakpoint(s) created'])

        # we will delete this breakpoint before we hit it.
        # breakpoint 2
        self.try_command('language renderscript kernel breakpoint set swizzle_kernel -c 199,199',
                         ['Conditional kernel breakpoint on coordinate 199, 199, 0',
                          'Breakpoint(s) created'])

        self.try_command('process continue', ['resuming', 'stopped', 'stop reason = breakpoint'])

        self._check_coordinates(3, 7, 0, 'swizzle_kernel')

        # check breakpoints that have been hit are disabled
        self.try_command('breakpoint list',
                         ["1: RenderScript kernel breakpoint for 'swizzle_kernel', locations = 1 Options: disabled",
                          "2: RenderScript kernel breakpoint for 'swizzle_kernel', locations = 1"])

        # delete breakpoint on 199,199,0
        self.try_command('breakpoint delete 2', ['1 breakpoints deleted'])

        # check breakpoints that have been hit are disabled
        self.try_command('breakpoint list',
                         ["1: RenderScript kernel breakpoint for 'swizzle_kernel', locations = 1 Options: disabled"])

        # test conditional coordinate in a single dimension
        # breakpoint 3
        self.try_command('language renderscript kernel breakpoint set square_kernel -c 8',
                         ['Conditional kernel breakpoint on coordinate 8, 0, 0', 'Breakpoint(s) created'])

        # check breakpoints that have been hit are disabled
        self.try_command('breakpoint list',
                         ["1: RenderScript kernel breakpoint for 'swizzle_kernel', locations = 1 Options: disabled",
                          "3: RenderScript kernel breakpoint for 'square_kernel', locations = 1"])

        self.try_command('process continue', ['resuming', 'stopped', 'stop reason = breakpoint'])

        self._check_coordinates(8, 0, 0, 'square_kernel')

        # check breakpoints that have been hit are disabled
        self.try_command('breakpoint list',
                         ["1: RenderScript kernel breakpoint for 'swizzle_kernel', locations = 1 Options: disabled",
                          "3: RenderScript kernel breakpoint for 'square_kernel', locations = 1 Options: disabled"])

    @wimpy
    @ordered_test(1)
    def test_breakpoint_coordinate_3d_add_half_kernel(self):
        # test conditional coordinate in three dimensions
        # breakpoint 4
        self.try_command('language renderscript kernel breakpoint set add_half_kernel -c 0,0,1',
                         ['Conditional kernel breakpoint on coordinate 0, 0, 1',
                          'Breakpoint(s) created'])

        # test we can set more than one conditional kernel breakpoint and both will be hit
        # breakpoint 5
        self.try_command('language renderscript kernel breakpoint set add_half_kernel -c 0,1,2',
                         ['Conditional kernel breakpoint on coordinate 0, 1, 2',
                          'Breakpoint(s) created'])

        self.try_command('process continue', ['resuming', 'stopped', 'stop reason = breakpoint'])

        self._check_coordinates(0, 0, 1, 'add_half_kernel')

        # continue till we hit breakpoint 4
        self.try_command('process continue', ['resuming', 'stopped', 'stop reason = breakpoint'])

        self._check_coordinates(0, 1, 2, 'add_half_kernel')

        # check we can see the coordinate from a function invoked by the kernel
        # breakpoint 6
        self.try_command('break set -n half_helper', ['librs.allocs.so`half_helper'])

        # continue till we hit breakpoint 6
        self.try_command('process continue', ['resuming', 'stopped', 'stop reason = breakpoint'])

        self._check_coordinates(0, 1, 2, 'half_helper')

        self.try_command('breakpoint list',
                         ["1: RenderScript kernel breakpoint for 'swizzle_kernel', locations = 1 Options: disabled",
                          "3: RenderScript kernel breakpoint for 'square_kernel', locations = 1 Options: disabled",
                          "4: RenderScript kernel breakpoint for 'add_half_kernel', locations = 1 Options: disabled",
                          "5: RenderScript kernel breakpoint for 'add_half_kernel', locations = 1 Options: disabled",
                          "6: name = 'half_helper', locations = 1, resolved = 1, hit count = 1"])

        self.try_command('breakpoint delete 3', ['1 breakpoints deleted'])

        self.try_command('breakpoint list',
                         ["1: RenderScript kernel breakpoint for 'swizzle_kernel', locations = 1 Options: disabled",
                          "4: RenderScript kernel breakpoint for 'add_half_kernel', locations = 1 Options: disabled",
                          "5: RenderScript kernel breakpoint for 'add_half_kernel', locations = 1 Options: disabled",
                          "6: name = 'half_helper', locations = 1, resolved = 1, hit count = 1"])

        self.try_command('breakpoint delete 6', ['1 breakpoints deleted'])

        self.try_command('breakpoint list',
                         ["1: RenderScript kernel breakpoint for 'swizzle_kernel', locations = 1 Options: disabled",
                          "4: RenderScript kernel breakpoint for 'add_half_kernel', locations = 1 Options: disabled",
                          "5: RenderScript kernel breakpoint for 'add_half_kernel', locations = 1 Options: disabled"])

    @cpp_only_test()
    @ordered_test('last')
    def test_cpp_cleanup(self):
        self.try_command('breakpoint delete 4', ['1 breakpoints deleted'])

        self.try_command('breakpoint delete 5', ['1 breakpoints deleted'])

        self.try_command('process continue', ['exited with status = 0'])
