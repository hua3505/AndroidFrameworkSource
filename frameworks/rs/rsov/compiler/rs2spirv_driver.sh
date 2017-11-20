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

#!/bin/bash

if [ $# -lt 3 ]; then
  echo 1>&2 "$0: not enough arguments"
  echo 1>&2 $#
  exit 2
fi

AND_HOME=$ANDROID_BUILD_TOP
SPIRV_TOOLS_PATH=$1

script_name="$2"
script=${2%.*} # Remove extension.

output_folder="$3"
mkdir -p $output_folder

eval llvm-rs-cc -o "$output_folder" -S -emit-llvm -Wall -Werror -target-api 24 \
  -I "$AND_HOME/external/clang/lib/Headers" -I "$AND_HOME/frameworks/rs/scriptc" \
  "$script_name"
eval llvm-as "$output_folder/bc32/$script.ll" -o "$output_folder/$script.bc"
eval rs2spirv "$output_folder/$script.bc" -o "$output_folder/$script.rs.spv" \
              -wo "$output_folder/$script.w.spt"
eval "$SPIRV_TOOLS_PATH/spirv-dis" "$output_folder/$script.rs.spv" \
              --no-color > "$output_folder/$script.rs.spt"
eval rs2spirv -o "$output_folder/$script.spt" -lk "$output_folder/$script.rs.spt" \
              -lw "$output_folder/$script.w.spt"
eval "$SPIRV_TOOLS_PATH/spirv-as" "$output_folder/$script.spt" \
              -o "$output_folder/$script.spv"
echo
eval rs2spirv "$output_folder/$script.spv" -print-as-words
echo
eval "$SPIRV_TOOLS_PATH/spirv-val" "$output_folder/$script.spv"
echo
