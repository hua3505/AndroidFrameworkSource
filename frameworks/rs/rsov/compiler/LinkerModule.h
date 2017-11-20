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

#ifndef RS_LINKER_MODULE_H
#define RS_LINKER_MODULE_H

#include "llvm/ADT/Optional.h"
#include "llvm/ADT/SmallVector.h"
#include "llvm/ADT/StringRef.h"
#include "llvm/ADT/iterator_range.h"
#include "llvm/Support/Casting.h"

#include <algorithm>
#include <cassert>
#include <memory>
#include <sstream>
#include <vector>

namespace rs2spirv {

class SPIRVLine {
  std::string Line;

public:
  SPIRVLine(llvm::StringRef L) : Line(L.str()) {}

  std::string &str() { return Line; }
  const std::string &str() const { return Line; }

  void trim() { Line = llvm::StringRef(Line).trim().str(); }

  bool empty() const { return Line.empty(); }
  bool hasCode() const;

  void getIdentifiers(llvm::SmallVectorImpl<llvm::StringRef> &Out,
                      size_t StartPos = 0) const;
  llvm::Optional<llvm::StringRef> getLHSIdentifier() const;
  void getRHSIdentifiers(llvm::SmallVectorImpl<llvm::StringRef> &Out) const;
  llvm::Optional<llvm::StringRef> getRHS() const;
  bool replaceId(llvm::StringRef Original, llvm::StringRef New);
  bool replaceStr(llvm::StringRef Original, llvm::StringRef New);
  bool contains(llvm::StringRef S) const;

  void markAsEmpty();
};

class Block {
public:
  enum BlockKind {
    BK_Header,
    BK_Decor,
    BK_TypeAndConst,
    BK_Var,
    BK_FunDecl,
    BK_Function,
    BK_MainFun
  };

private:
  const BlockKind Kind;

protected:
  llvm::SmallVector<SPIRVLine, 4> Lines;

public:
  using line_iter = decltype(Lines)::iterator;
  using const_line_iter = decltype(Lines)::const_iterator;

  const llvm::StringRef Name;

  BlockKind getKind() const { return Kind; }

  Block(BlockKind K, llvm::StringRef N) : Kind(K), Name(N) {}
  Block(const Block &) = default;
  Block &operator=(const Block &B);

  virtual ~Block() = default;

  virtual bool addLine(SPIRVLine L, bool trim = true);

  llvm::iterator_range<line_iter> lines() {
    return llvm::make_range(Lines.begin(), Lines.end());
  }

  llvm::iterator_range<const_line_iter> lines() const {
    return llvm::make_range(Lines.begin(), Lines.end());
  }

  size_t getNumLines() const { return Lines.size(); }
  bool empty() const { return Lines.empty(); }
  bool hasCode() const;
  size_t getIdCount(llvm::StringRef Id) const;
  virtual void replaceAllIds(llvm::StringRef Old, llvm::StringRef New);
  SPIRVLine &getLastLine();
  const SPIRVLine &getLastLine() const;
  virtual void appendToStream(std::ostream &OS) const;
  void removeNonCodeLines();

  virtual void dump() const;
};

class HeaderBlock : public Block {
public:
  HeaderBlock() : Block(BK_Header, "Header") {}
  HeaderBlock(const HeaderBlock &) = default;
  HeaderBlock &operator=(const HeaderBlock &) = default;
  static bool classof(const Block *B) { return B->getKind() == BK_Header; }

  bool getRSKernelNames(llvm::SmallVectorImpl<llvm::StringRef> &Out) const;
};

class DecorBlock : public Block {
public:
  DecorBlock() : Block(BK_Decor, "Decor") {}
  DecorBlock(const DecorBlock &) = default;
  DecorBlock &operator=(const DecorBlock &) = default;
  static bool classof(const Block *B) { return B->getKind() == BK_Decor; }
};

// Block containing (interleaved) type and constant definitions.
class TypeAndConstBlock : public Block {
public:
  TypeAndConstBlock() : Block(BK_TypeAndConst, "TypeAndConst") {}
  TypeAndConstBlock(const TypeAndConstBlock &) = default;
  TypeAndConstBlock &operator=(const TypeAndConstBlock &) = default;
  static bool classof(const Block *B) {
    return B->getKind() == BK_TypeAndConst;
  }
};

class VarBlock : public Block {
public:
  VarBlock() : Block(BK_Var, "Var") {}
  VarBlock(const VarBlock &) = default;
  VarBlock &operator=(const VarBlock &) = default;
  static bool classof(const Block *B) { return B->getKind() == BK_Var; }
};

class FunDeclBlock : public Block {
public:
  FunDeclBlock() : Block(BK_FunDecl, "FunDecl") {}
  FunDeclBlock(const FunDeclBlock &) = default;
  FunDeclBlock &operator=(const FunDeclBlock &) = default;
  static bool classof(const Block *B) { return B->getKind() == BK_FunDecl; }
};

class FunctionBlock : public Block {
public:
  FunctionBlock() : Block(BK_Function, "Function") {}
  FunctionBlock(const FunctionBlock &) = default;
  FunctionBlock &operator=(const FunctionBlock &) = default;
  static bool classof(const Block *B) {
    return B->getKind() >= BK_Function && B->getKind() <= BK_MainFun;
  }

  llvm::StringRef getFunctionName() const;
  size_t getArity() const;
  void getArgNames(llvm::SmallVectorImpl<llvm::StringRef> &Out) const;
  llvm::Optional<llvm::StringRef> getRetValName() const;
  llvm::iterator_range<const_line_iter> body() const;
  void getCalledFunctions(llvm::SmallVectorImpl<llvm::StringRef> &Out) const;
  bool hasFunctionCalls() const;
  bool isDirectlyRecursive() const;
  bool isReturnTypeVoid() const;

protected:
  FunctionBlock(BlockKind BK, llvm::StringRef N) : Block(BK, N) {}
};

class MainFunBlock : public FunctionBlock {
public:
  MainFunBlock() : FunctionBlock(BK_MainFun, "MainFun") {}
  MainFunBlock(const MainFunBlock &) = default;
  MainFunBlock &operator=(const MainFunBlock &) = default;
  static bool classof(const Block *B) { return B->getKind() == BK_MainFun; }
};

class LinkerModule {
  using block_ptr = std::unique_ptr<Block>;
  std::vector<block_ptr> Blocks;

public:
  using block_iter = decltype(Blocks)::iterator;
  using const_block_iter = decltype(Blocks)::const_iterator;

  LinkerModule(std::istream &ModuleIn);
  LinkerModule() = default;

  void dump() const {
    for (const auto &Blck : Blocks)
      Blck->dump();
  }

  std::vector<SPIRVLine *> lines() {
    std::vector<SPIRVLine *> res;
    for (auto &B : Blocks)
      for (auto &L : B->lines())
        res.emplace_back(&L);

    return res;
  }

  std::vector<const SPIRVLine *> lines() const {
    std::vector<const SPIRVLine *> res;
    for (const auto &B : Blocks)
      for (const auto &L : B->lines())
        res.emplace_back(&L);

    return res;
  }

  llvm::iterator_range<block_iter> blocks() {
    return llvm::make_range(Blocks.begin(), Blocks.end());
  }

  llvm::iterator_range<const_block_iter> blocks() const {
    return llvm::make_range(Blocks.cbegin(), Blocks.cend());
  }

  template <typename T, typename... Ts> T &addBlock(Ts &&... ts) {
    Blocks.emplace_back(std::unique_ptr<T>(new T(std::forward<Ts>(ts)...)));
    return *llvm::cast<T>(Blocks.back().get());
  }

  template <typename T> T &getLastBlock() {
    assert(Blocks.size());
    return *llvm::cast<T>(Blocks.back().get());
  }

  template <typename T> const T &getLastBlock() const {
    assert(Blocks.size());
    return *llvm::cast<T>(Blocks.back().get());
  }

  template <typename P>
  void getBlocksIf(llvm::SmallVectorImpl<Block *> &Out, P Predicate) {
    for (auto &BPtr : Blocks)
      if (Predicate(*BPtr))
        Out.push_back(BPtr.get());
  }

  template <typename P>
  void getBlocksIf(llvm::SmallVectorImpl<const Block *> &Out,
                   P Predicate) const {
    for (const auto &BPtr : Blocks)
      if (Predicate(*BPtr))
        Out.push_back(BPtr.get());
  }

  template <typename P> void removeBlocksIf(P Predicate) {
    Blocks.erase(std::remove_if(Blocks.begin(), Blocks.end(),
                                [&Predicate](const block_ptr &BPtr) {
                                  return Predicate(*BPtr);
                                }),
                 Blocks.end());
  }

  void fixBlockOrder();
  bool saveToFile(llvm::StringRef FName) const;
  void removeEmptyBlocks();
  void removeNonCode();
  void removeUnusedFunctions();
};

} // namespace rs2spirv

#endif