#!/bin/bash -e

current_dir=$(pwd)
LIT_PATH=$current_dir/llvm-lit
LIBSPIRV_TESTS=$current_dir

$LIT_PATH $LIBSPIRV_TESTS $@
