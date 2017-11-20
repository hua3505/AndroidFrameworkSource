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

#include "RSSPIRVWriter.h"

#include "SPIRVModule.h"
#include "bcinfo/MetadataExtractor.h"

#include "llvm/ADT/StringMap.h"
#include "llvm/ADT/Triple.h"
#include "llvm/IR/LegacyPassManager.h"
#include "llvm/IR/Module.h"
#include "llvm/Support/CommandLine.h"
#include "llvm/Support/Debug.h"
#include "llvm/Support/SPIRV.h"
#include "llvm/Support/raw_ostream.h"
#include "llvm/Transforms/IPO.h"

#include "GlobalMergePass.h"
#include "LinkerModule.h"
#include "ReflectionPass.h"
#include "InlinePreparationPass.h"

#include <fstream>
#include <sstream>

#define DEBUG_TYPE "rs2spirv-writer"

using namespace llvm;
using namespace SPIRV;

namespace llvm {
FunctionPass *createPromoteMemoryToRegisterPass();
}

namespace rs2spirv {

static cl::opt<std::string> WrapperOutputFile("wo",
                                              cl::desc("Wrapper output file"),
                                              cl::value_desc("filename.spt"));

static bool FixMain(LinkerModule &LM, MainFunBlock &MB, StringRef KernelName);
static bool InlineFunctionCalls(LinkerModule &LM, MainFunBlock &MB);
static bool FuseTypesAndConstants(LinkerModule &LM);
static bool TranslateInBoundsPtrAccessToAccess(SPIRVLine &L);
static bool FixVectorShuffles(MainFunBlock &MB);
static void FixModuleStorageClass(LinkerModule &M);

static void HandleTargetTriple(Module &M) {
  Triple TT(M.getTargetTriple());
  auto Arch = TT.getArch();

  StringRef NewTriple;
  switch (Arch) {
  default:
    llvm_unreachable("Unrecognized architecture");
    break;
  case Triple::arm:
    NewTriple = "spir-unknown-unknown";
    break;
  case Triple::aarch64:
    NewTriple = "spir64-unknown-unknown";
    break;
  case Triple::spir:
  case Triple::spir64:
    DEBUG(dbgs() << "!!! Already a spir triple !!!\n");
  }

  DEBUG(dbgs() << "New triple:\t" << NewTriple << "\n");
  M.setTargetTriple(NewTriple);
}

void addPassesForRS2SPIRV(llvm::legacy::PassManager &PassMgr) {
  PassMgr.add(createGlobalMergePass());
  PassMgr.add(createPromoteMemoryToRegisterPass());
  PassMgr.add(createTransOCLMD());
  // TODO: investigate removal of OCLTypeToSPIRV pass.
  PassMgr.add(createOCLTypeToSPIRV());
  PassMgr.add(createSPIRVRegularizeLLVM());
  PassMgr.add(createSPIRVLowerConstExpr());
  PassMgr.add(createSPIRVLowerBool());
  PassMgr.add(createAlwaysInlinerPass());
}

bool WriteSPIRV(Module *M, llvm::raw_ostream &OS, std::string &ErrMsg) {
  std::unique_ptr<SPIRVModule> BM(SPIRVModule::createSPIRVModule());

  HandleTargetTriple(*M);

  bcinfo::MetadataExtractor ME(M);
  if (!ME.extract()) {
    errs() << "Could not extract metadata\n";
    return false;
  }
  DEBUG(dbgs() << "Metadata extracted\n");

  llvm::legacy::PassManager PassMgr;
  PassMgr.add(createInlinePreparationPass(ME));
  addPassesForRS2SPIRV(PassMgr);

  std::ofstream WrapperF;
  if (!WrapperOutputFile.empty()) {
    WrapperF.open(WrapperOutputFile, std::ios::trunc);
    if (!WrapperF.good()) {
      errs() << "Could not create/open file:\t" << WrapperOutputFile << "\n";
      return false;
    }
    DEBUG(dbgs() << "Wrapper output:\t" << WrapperOutputFile << "\n");
    PassMgr.add(createReflectionPass(WrapperF, ME));
  }

  PassMgr.add(createLLVMToSPIRV(BM.get()));
  PassMgr.run(*M);
  DEBUG(M->dump());

  if (BM->getError(ErrMsg) != SPIRVEC_Success)
    return false;

  OS << *BM;

  return true;
}

bool Link(llvm::StringRef KernelFilename, llvm::StringRef WrapperFilename,
          llvm::StringRef OutputFilename) {
  DEBUG(dbgs() << "Linking...\n");

  std::ifstream WrapperF(WrapperFilename);
  if (!WrapperF.good()) {
    errs() << "Cannot open file: " << WrapperFilename << "\n";
  }
  std::ifstream KernelF(KernelFilename);
  if (!KernelF.good()) {
    errs() << "Cannot open file: " << KernelFilename << "\n";
  }

  LinkerModule WrapperM(WrapperF);
  LinkerModule KernelM(KernelF);

  WrapperF.close();
  KernelF.close();

  DEBUG(dbgs() << "WrapperF:\n");
  DEBUG(WrapperM.dump());
  DEBUG(dbgs() << "\n~~~~~~~~~~~~~~~~~~~~~~\n\nKernelF:\n");
  DEBUG(KernelM.dump());
  DEBUG(dbgs() << "\n======================\n\n");

  const char *const Prefix = "%rs_linker_";

  for (auto *LPtr : KernelM.lines()) {
    assert(LPtr);
    auto &L = *LPtr;
    size_t Pos = 0;
    while ((Pos = L.str().find("%", Pos)) != std::string::npos) {
      L.str().replace(Pos, 1, Prefix);
      Pos += strlen(Prefix);
    }
  }

  FixModuleStorageClass(KernelM);
  DEBUG(KernelM.dump());

  auto WBlocks = WrapperM.blocks();
  auto WIt = WBlocks.begin();
  const auto WEnd = WBlocks.end();

  auto KBlocks = KernelM.blocks();
  auto KIt = KBlocks.begin();
  const auto KEnd = KBlocks.end();

  LinkerModule OutM;

  if (WIt == WEnd || KIt == KEnd)
    return false;

  const auto *HeaderB = dyn_cast<HeaderBlock>(WIt->get());
  if (!HeaderB || !isa<HeaderBlock>(KIt->get()))
    return false;

  SmallVector<StringRef, 2> KernelNames;
  const bool KernelsFound = HeaderB->getRSKernelNames(KernelNames);

  if (!KernelsFound) {
    errs() << "RS kernel names not found in wrapper\n";
    return false;
  }

  // TODO: Support more than one kernel.
  if (KernelNames.size() != 1) {
    errs() << "Unsupported number of kernels: " << KernelNames.size() << '\n';
    return false;
  }

  const std::string KernelName =
      Prefix + KernelNames.front().drop_front().str();
  DEBUG(dbgs() << "Kernel name: " << KernelName << '\n');

  // Kernel's HeaderBlock is skipped - it has OpenCL-specific code that
  // is replaced here with compute shader code.

  OutM.addBlock<HeaderBlock>(*HeaderB);

  if (++WIt == WEnd || ++KIt == KEnd)
    return false;

  const auto *DecorBW = dyn_cast<DecorBlock>(WIt->get());
  if (!DecorBW || !isa<DecorBlock>(KIt->get()))
    return false;

  // Kernel's DecorBlock is skipped, because it contains OpenCL-specific code
  // that is not needed (eg. linkage type information).

  OutM.addBlock<DecorBlock>(*DecorBW);

  if (++WIt == WEnd || ++KIt == KEnd)
    return false;

  const auto *TypeAndConstBW = dyn_cast<TypeAndConstBlock>(WIt->get());
  auto *TypeAndConstBK = dyn_cast<TypeAndConstBlock>(KIt->get());
  if (!TypeAndConstBW || !TypeAndConstBK)
    return false;

  OutM.addBlock<TypeAndConstBlock>(*TypeAndConstBW);
  OutM.addBlock<TypeAndConstBlock>(*TypeAndConstBK);

  if (++WIt == WEnd || ++KIt == KEnd)
    return false;

  const auto *VarBW = dyn_cast<VarBlock>(WIt->get());
  auto *VarBK = dyn_cast<VarBlock>(KIt->get());
  if (!VarBW)
    return false;

  OutM.addBlock<VarBlock>(*VarBW);

  if (VarBK)
    OutM.addBlock<VarBlock>(*VarBK);
  else
    --KIt;

  MainFunBlock *MainB = nullptr;

  while (++WIt != WEnd) {
    auto *FunB = dyn_cast<FunctionBlock>(WIt->get());
    if (!FunB)
      return false;

    if (auto *MB = dyn_cast<MainFunBlock>(WIt->get())) {
      if (MainB) {
        errs() << "More than one main function found in wrapper module\n";
        return false;
      }

      MainB = &OutM.addBlock<MainFunBlock>(*MB);
    } else {
      OutM.addBlock<FunctionBlock>(*FunB);
    }
  }

  if (!MainB) {
    errs() << "Wrapper module has no main function\n";
    return false;
  }

  while (++KIt != KEnd) {
    // TODO: Check if FunDecl is a known runtime function.
    if (isa<FunDeclBlock>(KIt->get()))
      continue;

    auto *FunB = dyn_cast<FunctionBlock>(KIt->get());
    if (!FunB)
      return false;

    // TODO: Detect also indirect recurion.
    if (FunB->isDirectlyRecursive()) {
      errs() << "Function: " << FunB->getFunctionName().str()
             << " is recursive\n";
      return false;
    }

    OutM.addBlock<FunctionBlock>(*FunB);
  }

  OutM.fixBlockOrder();
  if (!FixMain(OutM, *MainB, KernelName))
    return false;

  if (!FixVectorShuffles(*MainB))
    return false;

  OutM.removeUnusedFunctions();

  DEBUG(dbgs() << ">>>>>>>>>>>>  Output module after prelink:\n\n");
  DEBUG(OutM.dump());

  if (!FuseTypesAndConstants(OutM)) {
    errs() << "Type fusion failed\n";
    return false;
  }

  DEBUG(dbgs() << ">>>>>>>>>>>>  Output module after value fusion:\n\n");
  DEBUG(OutM.dump());

  if (!OutM.saveToFile(OutputFilename)) {
    errs() << "Could not save to file: " << OutputFilename << "\n";
    return false;
  }

  return true;
}

bool FixMain(LinkerModule &LM, MainFunBlock &MainB, StringRef KernelName) {
  MainB.replaceAllIds("%RS_SPIRV_DUMMY_", KernelName);

  while (MainB.hasFunctionCalls())
    if (!InlineFunctionCalls(LM, MainB)) {
      errs() << "Could not inline function calls in main\n";
      return false;
    }

  for (auto &L : MainB.lines()) {
    if (!L.contains("OpInBoundsPtrAccessChain"))
      continue;

    if (!TranslateInBoundsPtrAccessToAccess(L))
      return false;
  }

  return true;
}

struct FunctionCallInfo {
  StringRef RetValName;
  StringRef RetTy;
  StringRef FName;
  SmallVector<StringRef, 4> ArgNames;
};

static FunctionCallInfo GetFunctionCallInfo(const SPIRVLine &L) {
  assert(L.contains("OpFunctionCall"));

  const Optional<StringRef> Ret = L.getLHSIdentifier();
  assert(Ret);

  SmallVector<StringRef, 6> Ids;
  L.getRHSIdentifiers(Ids);
  assert(Ids.size() >= 2 && "No return type and function name");

  const StringRef RetTy = Ids[0];
  const StringRef FName = Ids[1];
  SmallVector<StringRef, 4> Args(Ids.begin() + 2, Ids.end());

  return {*Ret, RetTy, FName, std::move(Args)};
}

bool InlineFunctionCalls(LinkerModule &LM, MainFunBlock &MB) {
  DEBUG(dbgs() << "InlineFunctionCalls\n");
  MainFunBlock NewMB;

  auto MLines = MB.lines();
  auto MIt = MLines.begin();
  const auto MEnd = MLines.end();
  using iter_ty = decltype(MIt);

  auto SkipToFunctionCall = [&MEnd, &NewMB](iter_ty &It) {
    while (++It != MEnd && !It->contains("OpFunctionCall"))
      NewMB.addLine(*It);

    return It != MEnd;
  };

  NewMB.addLine(*MIt);

  std::vector<std::pair<std::string, std::string>> NameMapping;

  while (SkipToFunctionCall(MIt)) {
    assert(MIt->contains("OpFunctionCall"));
    const auto FInfo = GetFunctionCallInfo(*MIt);
    DEBUG(dbgs() << "Found function call:\t" << MIt->str() << '\n');

    SmallVector<Block *, 1> Callee;
    LM.getBlocksIf(Callee, [&FInfo](Block &B) {
      auto *FB = dyn_cast<FunctionBlock>(&B);
      if (!FB)
        return false;

      return FB->getFunctionName() == FInfo.FName;
    });

    if (Callee.size() != 1) {
      errs() << "Callee not found\n";
      return false;
    }

    auto *FB = cast<FunctionBlock>(Callee.front());

    if (FB->getArity() != FInfo.ArgNames.size()) {
      errs() << "Arity mismatch (caller: " << FInfo.ArgNames.size()
             << ", callee: " << FB->getArity() << ")\n";
      return false;
    }

    Optional<StringRef> RetValName = FB->getRetValName();
    if (!RetValName && !FB->isReturnTypeVoid()) {
      errs() << "Return value not found for a function with non-void "
                "return type.\n";
      return false;
    }

    SmallVector<StringRef, 4> Params;
    FB->getArgNames(Params);

    if (Params.size() != FInfo.ArgNames.size()) {
      errs() << "Params size mismatch\n";
      return false;
    }

    for (size_t i = 0, e = FInfo.ArgNames.size(); i < e; ++i) {
      DEBUG(dbgs() << "New param mapping: " << Params[i] << " -> "
                   << FInfo.ArgNames[i] << "\n");
      NameMapping.emplace_back(Params[i].str(), FInfo.ArgNames[i].str());
    }

    if (RetValName) {
      DEBUG(dbgs() << "New ret-val mapping: " << FInfo.RetValName << " -> "
                   << *RetValName << "\n");
      NameMapping.emplace_back(FInfo.RetValName.str(), RetValName->str());
    }

    const auto Body = FB->body();
    for (const auto &L : Body)
      NewMB.addLine(L);
  }

  while (MIt != MEnd) {
    NewMB.addLine(*MIt);
    ++MIt;
  }

  std::reverse(NameMapping.begin(), NameMapping.end());
  for (const auto &P : NameMapping) {
    DEBUG(dbgs() << "Replace " << P.first << " with " << P.second << "\n");
    NewMB.replaceAllIds(P.first, P.second);
  }

  MB = NewMB;

  return true;
}

bool FuseTypesAndConstants(LinkerModule &LM) {
  StringMap<std::string> TypesAndConstDefs;
  StringMap<std::string> NameReps;

  for (auto *LPtr : LM.lines()) {
    assert(LPtr);
    auto &L = *LPtr;
    if (!L.contains("="))
      continue;

    SmallVector<StringRef, 4> IdsRefs;
    L.getRHSIdentifiers(IdsRefs);

    SmallVector<std::string, 4> Ids;
    Ids.reserve(IdsRefs.size());
    for (const auto &I : IdsRefs)
      Ids.push_back(I.str());

    for (auto &I : Ids)
      if (NameReps.count(I) != 0) {
        const bool Res = L.replaceId(I, NameReps[I]);
        (void)Res;
        assert(Res);
      }

    if (L.contains("OpType") || L.contains("OpConstant")) {
      const auto LHS = L.getLHSIdentifier();
      const auto RHS = L.getRHS();
      assert(LHS);
      assert(RHS);

      if (TypesAndConstDefs.count(*RHS) != 0) {
        NameReps.insert(
            std::make_pair(LHS->str(), TypesAndConstDefs[RHS->str()]));
        DEBUG(dbgs() << "New mapping: [" << LHS->str() << ", "
                     << TypesAndConstDefs[RHS->str()] << "]\n");
        L.markAsEmpty();
      } else {
        TypesAndConstDefs.insert(std::make_pair(RHS->str(), LHS->str()));
        DEBUG(dbgs() << "New val:\t" << RHS->str() << " : " << LHS->str()
                     << '\n');
      }
    };
  }

  LM.removeNonCode();

  return true;
}

bool TranslateInBoundsPtrAccessToAccess(SPIRVLine &L) {
  assert(L.contains(" OpInBoundsPtrAccessChain "));

  SmallVector<StringRef, 4> Ids;
  L.getRHSIdentifiers(Ids);

  if (Ids.size() < 4) {
    errs() << "OpInBoundsPtrAccessChain has not enough parameters:\n\t"
           << L.str();
    return false;
  }

  std::istringstream SS(L.str());
  std::string LHS, Eq, Op;
  SS >> LHS >> Eq >> Op;

  if (LHS.empty() || Eq != "=" || Op != "OpInBoundsPtrAccessChain") {
    errs() << "Could not decompose OpInBoundsPtrAccessChain:\n\t" << L.str();
    return false;
  }

  constexpr size_t ElementArgPosition = 2;

  std::ostringstream NewLine;
  NewLine << LHS << " " << Eq << " OpAccessChain ";
  for (size_t i = 0, e = Ids.size(); i != e; ++i)
    if (i != ElementArgPosition)
      NewLine << Ids[i].str() << " ";

  L.str() = NewLine.str();
  L.trim();

  return true;
}

// Replaces UndefValues in VectorShuffles with zeros, which is always
// safe, as the result for components marked as Undef is unused.
// Ex. 1)    OpVectorShuffle %v4uchar %a %b 0 1 2 4294967295 -->
//           OpVectorShuffle %v4uchar %a %b 0 1 2 0.
//
// Ex. 2)    OpVectorShuffle %v4uchar %a %b 0 4294967295 3 4294967295 -->
//           OpVectorShuffle %v4uchar %a %b 0 0 3 0.
//
// Fix needed for the current Vulkan driver, which crashed during
// backend compilation when case is not handled.
bool FixVectorShuffles(MainFunBlock &MB) {
  const StringRef UndefStr = " 4294967295 ";

  for (auto &L : MB.lines()) {
    if (!L.contains("OpVectorShuffle"))
      continue;

    L.str().push_back(' ');
    while (L.contains(UndefStr))
      L.replaceStr(UndefStr, " 0 ");

    L.trim();
  }

  return true;
}

// This function changes all Function StorageClass use into Uniform.
// It's needed, because llvm-spirv converter emits wrong StorageClass
// for globals.
// The transfromation, however, breaks legitimate uses of Function StorageClass
// inside functions.
//
//  Ex. 1. %ptr_Function_uint = OpTypePointer Function %uint
//     --> %ptr_Uniform_uint = OpTypePointer Uniform %uint
//
//  Ex. 2. %gep = OpAccessChain %ptr_Function_uchar %G %uint_zero
//     --> %gep = OpAccessChain %ptr_Uniform_uchar %G %uint_zero
//
// TODO: Consider a better way of fixing this.
void FixModuleStorageClass(LinkerModule &M) {
  for (auto *LPtr : M.lines()) {
    assert(LPtr);
    auto &L = *LPtr;

    while (L.contains(" Function"))
      L.replaceStr(" Function", " Uniform");

    while (L.contains("_Function_"))
      L.replaceStr("_Function_", "_Uniform_");
  }
}

} // namespace rs2spirv
