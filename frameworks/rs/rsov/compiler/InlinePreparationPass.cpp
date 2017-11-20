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

#include "InlinePreparationPass.h"

#include "bcinfo/MetadataExtractor.h"

#include "llvm/ADT/StringSet.h"
#include "llvm/IR/Attributes.h"
#include "llvm/IR/Module.h"
#include "llvm/IR/PassManager.h"
#include "llvm/Pass.h"
#include "llvm/Support/Debug.h"

#define DEBUG_TYPE "rs2spirv-inline"

using namespace llvm;

namespace rs2spirv {

namespace {

class InlinePreparationPass : public ModulePass {
  bcinfo::MetadataExtractor &ME;

public:
  static char ID;
  explicit InlinePreparationPass(bcinfo::MetadataExtractor &Extractor)
      : ModulePass(ID), ME(Extractor) {}

  const char *getPassName() const override { return "InlinePreparationPass"; }

  bool runOnModule(Module &M) override {
    DEBUG(dbgs() << "InlinePreparationPass\n");

    const size_t RSKernelNum = ME.getExportForEachSignatureCount();
    const char **RSKernelNames = ME.getExportForEachNameList();
    if (RSKernelNum == 0)
      DEBUG(dbgs() << "InlinePreparationPass detected no kernel\n");

    StringSet<> KNames;
    for (size_t i = 0; i < RSKernelNum; ++i)
      KNames.insert(RSKernelNames[i]);

    for (auto &F : M.functions()) {
      if (F.isDeclaration())
        continue;

      const auto FName = F.getName();

      // TODO: Consider inlining kernels (i.e. kernels calling other kernels)
      // when multi-kernel module support is ready.
      if (KNames.count(FName) != 0)
        continue; // Skip kernels.

      F.addFnAttr(Attribute::AlwaysInline);
      F.setLinkage(GlobalValue::InternalLinkage);
      DEBUG(dbgs() << "Marked as alwaysinline:\t" << FName << '\n');
    }

    // Return true, as the pass modifies module.
    return true;
  }
};
}

char InlinePreparationPass::ID = 0;

ModulePass *createInlinePreparationPass(bcinfo::MetadataExtractor &ME) {
  return new InlinePreparationPass(ME);
}

} // namespace rs2spirv
