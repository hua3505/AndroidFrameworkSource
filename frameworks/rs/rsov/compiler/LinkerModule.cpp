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

#include "LinkerModule.h"

#include "llvm/Support/Debug.h"
#include "llvm/Support/raw_ostream.h"

#include <fstream>
#include <sstream>

#define DEBUG_TYPE "rs2spirv-module"

using namespace llvm;

namespace rs2spirv {

bool SPIRVLine::hasCode() const {
  StringRef S(Line);
  S = S.trim();
  if (S.empty())
    return false;
  if (S[0] == ';')
    return false;

  return true;
}

static Optional<StringRef> GetFirstId(StringRef S, size_t StartPos,
                                      size_t &EndPos) {
  size_t Pos = S.find('%', StartPos);
  if (Pos == StringRef::npos) {
    return None;
  }

  const auto PosB = Pos;
  while (++Pos < S.size() && isspace(S[Pos]) == 0)
    ;

  EndPos = Pos;
  return StringRef(S.data() + PosB, EndPos - PosB);
}

void SPIRVLine::getIdentifiers(SmallVectorImpl<StringRef> &Out,
                               size_t StartPos) const {
  const StringRef S(Line);

  size_t Pos = StartPos;
  Optional<StringRef> Res;
  while ((Res = GetFirstId(S, Pos, Pos)))
    Out.push_back(*Res);
}

Optional<StringRef> SPIRVLine::getLHSIdentifier() const {
  size_t EndPos;
  const auto Id = GetFirstId(Line, 0, EndPos);
  if (!Id)
    return None;

  if (!contains("="))
    return None;

  return *Id;
}

Optional<StringRef> SPIRVLine::getRHS() const {
  const auto EqPos = Line.find('=', 0);
  if (EqPos == std::string::npos)
    return None;

  return StringRef(Line.c_str() + EqPos + 1).trim();
}

void SPIRVLine::getRHSIdentifiers(SmallVectorImpl<StringRef> &Out) const {
  const auto RHS = getRHS();
  if (!RHS)
    return;

  size_t Pos = 0;
  Optional<StringRef> Res;
  while ((Res = GetFirstId(*RHS, Pos, Pos)))
    Out.push_back(*Res);
}

bool SPIRVLine::replaceStr(StringRef Original, StringRef New) {
  const size_t Pos = StringRef(Line).find(Original);
  if (Pos == StringRef::npos)
    return false;

  Line.replace(Pos, Original.size(), New.str());
  return true;
}

bool SPIRVLine::replaceId(StringRef Original, StringRef New) {
  size_t Pos = StringRef(Line).find(Original, 0);
  if (Pos == StringRef::npos)
    return false;

  const auto OneAfter = Pos + Original.size();
  if (OneAfter < Line.size() && isspace(Line[OneAfter]) == 0) {
    Pos = StringRef(Line).find(Original, OneAfter);
    if (Pos == StringRef::npos)
      return false;
  }

  Line.replace(Pos, Original.size(), New.str());
  return true;
}

bool SPIRVLine::contains(StringRef S) const {
  return StringRef(Line).find(S, 0) != StringRef::npos;
}

void SPIRVLine::markAsEmpty() { Line = "; <<empty>>"; }

Block &Block::operator=(const Block &B) {
  assert(Kind == B.Kind);
  assert(Name == B.Name);
  Lines = B.Lines;

  return *this;
}

bool Block::addLine(SPIRVLine L, bool trim) {
  if (trim)
    L.trim();

  Lines.emplace_back(std::move(L));
  return true;
}

SPIRVLine &Block::getLastLine() {
  assert(!Lines.empty());
  return Lines.back();
}

const SPIRVLine &Block::getLastLine() const {
  assert(!Lines.empty());
  return Lines.back();
}

void Block::appendToStream(std::ostream &OS) const {
  for (const auto &L : Lines)
    OS << L.str() << '\n';
}

void Block::dump() const {
  dbgs() << "\n" << Name << "Block: {\n\n";
  for (const auto &L : Lines) {
    if (L.hasCode())
      dbgs() << '\t';
    dbgs() << L.str() << '\n';
  }
  dbgs() << "\n} (" << Name << "Block)\n\n";
}

void Block::replaceAllIds(StringRef Old, StringRef New) {
  for (auto &L : Lines)
    while (L.replaceId(Old, New))
      ;
}

bool Block::hasCode() const {
  return std::any_of(Lines.begin(), Lines.end(),
                     [](const SPIRVLine &L) { return L.hasCode(); });
}

size_t Block::getIdCount(StringRef Id) const {
  size_t Res = 0;
  for (const auto &L : Lines) {
    SmallVector<StringRef, 4> Ids;
    L.getIdentifiers(Ids);
    Res += std::count(Ids.begin(), Ids.end(), Id);
  }

  return Res;
}

void Block::removeNonCodeLines() {
  Lines.erase(std::remove_if(Lines.begin(), Lines.end(),
                             [](const SPIRVLine &L) { return !L.hasCode(); }),
              Lines.end());
}

bool HeaderBlock::getRSKernelNames(SmallVectorImpl<StringRef> &Out) const {
  for (const auto &L : Lines)
    if (L.contains("OpString")) {
      const Optional<StringRef> Name = L.getLHSIdentifier();
      if (Name && *Name == "%RS_KERNELS") {
        auto LStr = L.str();
        LStr.erase(std::remove(LStr.begin(), LStr.end(), '"'), LStr.end());

        SPIRVLine(LStr).getRHSIdentifiers(Out);
        return true;
      }
    }

  return false;
}

StringRef FunctionBlock::getFunctionName() const {
  assert(!Lines.empty());
  assert(Lines.front().contains("OpFunction"));

  Optional<StringRef> Name = Lines.front().getLHSIdentifier();
  assert(Name);
  return *Name;
}

size_t FunctionBlock::getArity() const {
  size_t A = 0;
  for (const auto &L : Lines)
    if (L.contains("OpFunctionParameter"))
      ++A;

  return A;
}

void FunctionBlock::getArgNames(SmallVectorImpl<StringRef> &Out) const {
  for (const auto &L : Lines)
    if (L.contains("OpFunctionParameter")) {
      Optional<StringRef> Id = L.getLHSIdentifier();
      assert(Id);
      Out.push_back(*Id);
    }
}

Optional<StringRef> FunctionBlock::getRetValName() const {
  for (const auto &L : Lines)
    if (L.contains("OpReturnValue")) {
      SmallVector<StringRef, 1> Id;
      L.getIdentifiers(Id);
      assert(Id.size() == 1);
      return Id.front();
    }

  return None;
}

iterator_range<Block::const_line_iter> FunctionBlock::body() const {
  auto It = Lines.begin();
  const auto End = Lines.end();

  while (It != End && !It->contains("OpLabel"))
    ++It;

  assert(It != End);

  ++It;
  const auto BBegin = It;

  while (It != End && !It->contains("OpReturn"))
    ++It;

  assert(It != End);

  return make_range(BBegin, It);
}

void FunctionBlock::getCalledFunctions(
    llvm::SmallVectorImpl<llvm::StringRef> &Out) const {
  for (const auto &L : Lines)
    if (L.contains("OpFunctionCall")) {
      SmallVector<StringRef, 4> Ids;
      L.getRHSIdentifiers(Ids);
      assert(Ids.size() >= 2);

      Out.push_back(Ids[1]);
    }
}

bool FunctionBlock::hasFunctionCalls() const {
  SmallVector<StringRef, 4> Callees;
  getCalledFunctions(Callees);
  return !Callees.empty();
}

bool FunctionBlock::isDirectlyRecursive() const {
  SmallVector<StringRef, 4> Callees;
  getCalledFunctions(Callees);

  const auto FName = getFunctionName();
  return std::find(Callees.begin(), Callees.end(), FName) != Callees.end();
}

bool FunctionBlock::isReturnTypeVoid() const {
  assert(Lines.size() >= 4);
  // At least 4 lines: OpFunction, OpLabel, OpReturn, OpFunctionEnd.

  SmallVector<StringRef, 2> Ids;
  Lines.front().getRHSIdentifiers(Ids);
  assert(Ids.size() == 2);

  if (Ids.front() != "%void" && Ids.front() != "%rs_linker_void")
    return false;

  SPIRVLine SecondLast = Lines[Lines.size() - 2];
  SecondLast.trim();
  return SecondLast.str() == "OpReturn";
}

LinkerModule::LinkerModule(std::istream &ModuleIn) {
  std::string Temp;
  std::vector<SPIRVLine> Ls;
  while (std::getline(ModuleIn, Temp))
    Ls.push_back(StringRef(Temp));

  auto It = Ls.begin();
  const auto End = Ls.end();

  {
    auto &HeaderBlck = addBlock<HeaderBlock>();
    while (It != End && !It->contains("OpDecorate"))
      HeaderBlck.addLine(*(It++));
  }

  {
    auto &DcrBlck = addBlock<DecorBlock>();
    while (It != End && !It->contains("OpType"))
      DcrBlck.addLine(*(It++));

    DcrBlck.removeNonCodeLines();
  }

  {
    auto &TypeAndConstBlck = addBlock<TypeAndConstBlock>();
    auto &VarBlck = addBlock<VarBlock>();

    while (It != End && !It->contains("OpFunction")) {
      if (!It->hasCode()) {
        ++It;
        continue;
      }

      if (It->contains("OpType") || It->contains("OpConstant")) {
        TypeAndConstBlck.addLine(*It);
      } else {
        VarBlck.addLine(*It);
      }

      ++It;
    }

    TypeAndConstBlck.removeNonCodeLines();
    VarBlck.removeNonCodeLines();
  }

  while (It != End) {
    // Consume empty lines between blocks.
    if (It->empty()) {
      ++It;
      continue;
    }

    Optional<StringRef> Id = It->getLHSIdentifier();
    assert(Id && "Functions should start with OpFunction");

    FunctionBlock &FunBlck =
        *Id == "%main" ? addBlock<MainFunBlock>() : addBlock<FunctionBlock>();
    bool HasReturn = false;

    while (It != End) {
      if (It->empty()) {
        ++It;
        continue;
      }
      HasReturn |= It->contains("OpReturn");

      FunBlck.addLine(*(It++));
      if (FunBlck.getLastLine().contains("OpFunctionEnd"))
        break;
    }

    FunBlck.removeNonCodeLines();

    if (!HasReturn) {
      FunDeclBlock FunDeclBlck;
      for (auto &L : FunBlck.lines())
        FunDeclBlck.addLine(std::move(L));

      Blocks.pop_back();
      addBlock<FunDeclBlock>(std::move(FunDeclBlck));
    }
  }

  removeNonCode();
}

void LinkerModule::fixBlockOrder() {
  std::stable_sort(Blocks.begin(), Blocks.end(),
                   [](const block_ptr &LHS, const block_ptr &RHS) {
                     return LHS->getKind() < RHS->getKind();
                   });
}

bool LinkerModule::saveToFile(StringRef FName) const {
  std::ofstream Out(FName, std::ios::trunc);
  if (!Out.good())
    return false;

  for (const auto &BPtr : blocks()) {
    if (!isa<HeaderBlock>(BPtr.get()))
      Out << "\n\n; " << BPtr->Name.str() << "\n\n";

    for (const auto &L : BPtr->lines()) {
      if (L.hasCode())
        Out << "\t";
      Out << L.str() << '\n';
    }
  }

  return true;
}

void LinkerModule::removeEmptyBlocks() {
  removeBlocksIf([](const Block &B) { return B.empty(); });
}

void LinkerModule::removeNonCode() {
  for (auto &BPtr : Blocks)
    if (!isa<HeaderBlock>(BPtr.get()))
      BPtr->removeNonCodeLines();

  removeBlocksIf([](const Block &B) { return !B.hasCode(); });
}

void LinkerModule::removeUnusedFunctions() {
  std::vector<std::string> UsedFunctions;

  assert(Blocks.size());

  const auto &MB = getLastBlock<MainFunBlock>();
  for (const auto &L : MB.lines())
    if (L.contains("OpFunctionCall")) {
      SmallVector<StringRef, 4> Ids;
      L.getRHSIdentifiers(Ids);
      assert(Ids.size() >= 2);

      const auto &FName = Ids[1];
      UsedFunctions.push_back(FName.str());
    }

  removeBlocksIf([&UsedFunctions](const Block &B) {
    const auto *FunBlck = dyn_cast<FunctionBlock>(&B);
    if (!FunBlck)
      return false;

    if (isa<MainFunBlock>(FunBlck))
      return false;

    const auto FName = FunBlck->getFunctionName().str();
    return std::find(UsedFunctions.begin(), UsedFunctions.end(), FName) ==
           UsedFunctions.end();
  });
}

} // namespace rs2spirv
