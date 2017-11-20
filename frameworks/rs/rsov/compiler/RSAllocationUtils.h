/*
 * Copyright 2016, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef RS2SPIRV_RS_ALLOCATION_UTILS_H
#define RS2SPIRV_RS_ALLOCATION_UTILS_H

#include "llvm/ADT/Optional.h"
#include "llvm/ADT/SmallVector.h"
#include "llvm/ADT/StringRef.h"

namespace llvm {
class CallInst;
class GlobalVariable;
class Module;
class Type;
}

namespace rs2spirv {

struct RSAllocationInfo {
  std::string VarName;
  llvm::Optional<std::string> RSElementType;
  llvm::GlobalVariable *GlobalVar;
};

enum class RSAllocAccessKind { GEA, SEA };

struct RSAllocationCallInfo {
  RSAllocationInfo &RSAlloc;
  llvm::CallInst *FCall;
  RSAllocAccessKind Kind;
  std::string RSElementTy;
};

bool isRSAllocation(const llvm::GlobalVariable &GV);
llvm::Optional<llvm::StringRef> getRSTypeName(const llvm::GlobalVariable &GV);
bool getRSAllocationInfo(llvm::Module &M,
                         llvm::SmallVectorImpl<RSAllocationInfo> &Allocs);
bool getRSAllocAccesses(llvm::SmallVectorImpl<RSAllocationInfo> &Allocs,
                        llvm::SmallVectorImpl<RSAllocationCallInfo> &Calls);
bool solidifyRSAllocAccess(llvm::Module &M, RSAllocationCallInfo CallInfo);

} // namespace rs2spirv

#endif
