#!/bin/bash

# Copyright 2016, The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# TODO: Consider rewriting as a native binary instead of shell script.

if [ $# -lt 1 ]; then
  echo 1>&2 "$0: not enough arguments"
  echo 1>&2 $#
  exit 2
fi

script_path="$1"
script_name=$(basename $script_path)
script=${script_name%.*} # Remove extension.

output_folder="driver_out"
mkdir -p $output_folder

eval llvm-as "$script_path" -o "$output_folder/$script.bc"
eval rs2spirv "$output_folder/$script.bc" -o "$output_folder/$script.rs.spv" \
              -wo "$output_folder/$script.w.spt"
eval spirv-dis "$output_folder/$script.rs.spv" \
              --no-color > "$output_folder/$script.rs.spt"
eval rs2spirv -o "$output_folder/$script.spt" -lk "$output_folder/$script.rs.spt" \
              -lw "$output_folder/$script.w.spt"
eval spirv-as "$output_folder/$script.spt" \
              -o "$output_folder/$script.spv"

eval spirv-val "$output_folder/$script.spv"
eval cat "$output_folder/$script.spt"

eval rm "$output_folder/$script.*"
