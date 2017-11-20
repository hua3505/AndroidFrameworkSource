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

#include "RSAllocationUtils.h"

#include "SPIRVInternal.h"
#include "llvm/IR/GlobalVariable.h"
#include "llvm/IR/Instructions.h"
#include "llvm/IR/Module.h"
#include "llvm/Support/Debug.h"
#include "llvm/Support/raw_ostream.h"
#include "llvm/Transforms/Utils/Cloning.h"

#include <algorithm>
#include <sstream>
#include <unordered_map>

#define DEBUG_TYPE "rs2spirv-rs-allocation-utils"

using namespace llvm;

namespace rs2spirv {

bool isRSAllocation(const GlobalVariable &GV) {
  auto *PT = cast<PointerType>(GV.getType());
  DEBUG(PT->dump());

  auto *VT = PT->getElementType();
  DEBUG(VT->dump());
  std::string TypeName;
  raw_string_ostream RSO(TypeName);
  VT->print(RSO);
  RSO.str(); // Force flush.
  DEBUG(dbgs() << "TypeName: " << TypeName << '\n');

  return TypeName.find("struct.rs_allocation") != std::string::npos;
}

bool getRSAllocationInfo(Module &M, SmallVectorImpl<RSAllocationInfo> &Allocs) {
  DEBUG(dbgs() << "getRSAllocationInfo\n");

  for (auto &GV : M.globals()) {
    if (GV.isDeclaration() || !isRSAllocation(GV))
      continue;

    Allocs.push_back({'%' + GV.getName().str(), None, &GV});
  }

  return true;
}

bool getRSAllocAccesses(SmallVectorImpl<RSAllocationInfo> &Allocs,
                        SmallVectorImpl<RSAllocationCallInfo> &Calls) {
  DEBUG(dbgs() << "getRSGEATCalls\n");
  DEBUG(dbgs() << "\n\n~~~~~~~~~~~~~~~~~~~~~\n\n");

  std::unordered_map<Value *, GlobalVariable *> Mapping;

  for (auto &A : Allocs) {
    auto *GV = A.GlobalVar;
    std::vector<User *> WorkList(GV->user_begin(), GV->user_end());
    size_t Idx = 0;

    while (Idx < WorkList.size()) {
      auto *U = WorkList[Idx];
      DEBUG(dbgs() << "Visiting ");
      DEBUG(U->dump());
      ++Idx;
      auto It = Mapping.find(U);
      if (It != Mapping.end()) {
        if (It->second == GV) {
          continue;
        } else {
          errs() << "Duplicate global mapping discovered!\n";
          errs() << "\nGlobal: ";
          GV->print(errs());
          errs() << "\nExisting mapping: ";
          It->second->print(errs());
          errs() << "\nUser: ";
          U->print(errs());
          errs() << '\n';

          return false;
        }
      }

      Mapping[U] = GV;
      DEBUG(dbgs() << "New mapping: ");
      DEBUG(U->print(dbgs()));
      DEBUG(dbgs() << " -> " << GV->getName() << '\n');

      if (auto *FCall = dyn_cast<CallInst>(U)) {
        if (auto *F = FCall->getCalledFunction()) {
          const auto FName = F->getName();
          DEBUG(dbgs() << "Discovered function call to : " << FName << '\n');

          std::string DemangledName;
          oclIsBuiltin(FName, &DemangledName);
          const StringRef DemangledNameRef(DemangledName);
          DEBUG(dbgs() << "Demangled name: " << DemangledNameRef << '\n');

          const StringRef GEAPrefix = "rsGetElementAt_";
          const StringRef SEAPrefix = "rsSetElementAt_";
          assert(GEAPrefix.size() == SEAPrefix.size());

          const bool IsGEA = DemangledNameRef.startswith(GEAPrefix);
          const bool IsSEA = DemangledNameRef.startswith(SEAPrefix);

          assert(!IsGEA || !IsSEA);

          if (IsGEA || IsSEA) {
            DEBUG(dbgs() << "Found rsAlloc function!\n");

            const auto Kind =
                IsGEA ? RSAllocAccessKind::GEA : RSAllocAccessKind::SEA;

            const auto RSElementTy =
                DemangledNameRef.drop_front(GEAPrefix.size());

            Calls.push_back({A, FCall, Kind, RSElementTy.str()});
            continue;
          } else if (DemangledNameRef.startswith(GEAPrefix.drop_back()) ||
                     DemangledNameRef.startswith(SEAPrefix.drop_back())) {
            errs() << "Untyped accesses to global rs_allocations are not "
                      "supported.\n";
            return false;
          }
        }
      }

      // TODO: Consider using set-like container to reduce computational
      // complexity.
      for (auto *NewU : U->users())
        if (std::find(WorkList.begin(), WorkList.end(), NewU) == WorkList.end())
          WorkList.push_back(NewU);
    }
  }

  std::unordered_map<GlobalVariable *, std::string> GVAccessTypes;

  for (auto &Access : Calls) {
    auto AccessElemTyIt = GVAccessTypes.find(Access.RSAlloc.GlobalVar);
    if (AccessElemTyIt != GVAccessTypes.end() &&
        AccessElemTyIt->second != Access.RSElementTy) {
      errs() << "Could not infere element type for: ";
      Access.RSAlloc.GlobalVar->print(errs());
      errs() << '\n';
      return false;
    } else if (AccessElemTyIt == GVAccessTypes.end()) {
      GVAccessTypes.emplace(Access.RSAlloc.GlobalVar, Access.RSElementTy);
      Access.RSAlloc.RSElementType = Access.RSElementTy;
    }
  }

  DEBUG(dbgs() << "\n\n~~~~~~~~~~~~~~~~~~~~~\n\n");
  return true;
}

bool solidifyRSAllocAccess(Module &M, RSAllocationCallInfo CallInfo) {
  DEBUG(dbgs() << "\tsolidifyRSAllocAccess " << CallInfo.RSAlloc.VarName
               << '\n');
  auto *FCall = CallInfo.FCall;
  auto *Fun = FCall->getCalledFunction();
  assert(Fun);

  const auto FName = Fun->getName();

  StringRef GVName = CallInfo.RSAlloc.VarName;
  std::ostringstream OSS;
  OSS << "RS_" << GVName.drop_front().str() << FName.str();

  auto *NewF = Function::Create(Fun->getFunctionType(),
                                Function::ExternalLinkage, OSS.str(), &M);
  FCall->setCalledFunction(NewF);
  NewF->setAttributes(Fun->getAttributes());

  DEBUG(M.dump());

  return true;
}

} // rs2spirv
