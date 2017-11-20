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
#include "llvm/Bitcode/ReaderWriter.h"
#include "llvm/IR/LLVMContext.h"
#include "llvm/IR/Module.h"
#include "llvm/Support/CommandLine.h"
#include "llvm/Support/DataStream.h"
#include "llvm/Support/Debug.h"
#include "llvm/Support/FileSystem.h"
#include "llvm/Support/PrettyStackTrace.h"
#include "llvm/Support/SPIRV.h"
#include "llvm/Support/Signals.h"
#include "llvm/Support/ToolOutputFile.h"
#include "llvm/Support/raw_ostream.h"
#include "unit_tests/TestRunner.h"

#include <fstream>
#include <iterator>

#define DEBUG_TYPE "rs2spirv"

namespace kExt {
const char SPIRVBinary[] = ".spv";
}

using namespace llvm;

static cl::opt<std::string> InputFile(cl::Positional, cl::desc("<input file>"),
                                      cl::init("-"));

static cl::opt<std::string> OutputFile("o",
                                       cl::desc("Override output filename"),
                                       cl::value_desc("filename"));

static cl::opt<std::string>
    KernelFile("lk", cl::desc("File with a compute shader kernel"),
               cl::value_desc("kernel.spt"));

static cl::opt<std::string> WrapperFile(
    "lw",
    cl::desc("Generated wrapper file"
             "(with entrypoint function and input/output images or buffers)"),
    cl::value_desc("wrapper.spt"));

static cl::opt<bool> IsPrintAsWords(
    "print-as-words",
    cl::desc("Print an input .spv file as a brace-init-list of words"),
    cl::init(false));

static cl::opt<bool>
    IsRegularization("s",
                     cl::desc("Regularize LLVM to be representable by SPIR-V"));

#ifdef RS2SPIRV_DEBUG
static cl::opt<bool> RunTests("run-tests", cl::desc("Run unit tests"),
                              cl::init(false));
#endif

namespace SPIRV {
extern bool SPIRVUseTextFormat;
}

static std::string removeExt(const std::string &FileName) {
  size_t Pos = FileName.find_last_of(".");
  if (Pos != std::string::npos)
    return FileName.substr(0, Pos);
  return FileName;
}

static int convertLLVMToSPIRV() {
  if (!KernelFile.empty() && !WrapperFile.empty()) {
    DEBUG(dbgs() << "Link " << KernelFile << " into " << WrapperFile << "\n");
    if (!rs2spirv::Link(KernelFile, WrapperFile, OutputFile)) {
      errs() << "Linking failed!\n\n";
      return -1;
    }
    return 0;
  }

  LLVMContext Context;

  std::string Err;
  auto DS = getDataFileStreamer(InputFile, &Err);
  if (!DS) {
    errs() << "Fails to open input file: " << Err;
    return -1;
  }

  ErrorOr<std::unique_ptr<Module>> MOrErr =
      getStreamedBitcodeModule(InputFile, std::move(DS), Context);

  if (std::error_code EC = MOrErr.getError()) {
    errs() << "Fails to load bitcode: " << EC.message();
    return -1;
  }

  std::unique_ptr<Module> M = std::move(*MOrErr);

  if (std::error_code EC = M->materializeAll()) {
    errs() << "Fails to materialize: " << EC.message();
    return -1;
  }

  if (OutputFile.empty()) {
    if (InputFile == "-")
      OutputFile = "-";
    else
      OutputFile = removeExt(InputFile) + kExt::SPIRVBinary;
  }

  llvm::StringRef outFile(OutputFile);
  std::error_code EC;
  llvm::raw_fd_ostream OFS(outFile, EC, llvm::sys::fs::F_None);
  if (!rs2spirv::WriteSPIRV(M.get(), OFS, Err)) {
    errs() << "Fails to save LLVM as SPIRV: " << Err << '\n';
    return -1;
  }

  return 0;
}

static int printAsWords() {
  std::ifstream IFS(InputFile, std::ios::binary);
  if (!IFS.good()) {
    errs() << "Could not open input file\n";
    return -1;
  }

  uint64_t FSize;
  const auto EC = llvm::sys::fs::file_size(InputFile, FSize);
  if (EC) {
    errs() << "Fails to open input file: " << EC.message() << '\n';
    return -1;
  }

  if (FSize % 4 != 0) {
    errs() << "Input file is not a stream of words. Size mismatch.\n";
    return -1;
  }

  std::istreambuf_iterator<char> It(IFS);
  const std::istreambuf_iterator<char> End;

  outs() << '{';

  while (It != End) {
    uint32_t val = 0;
    // Mask the sign-extended values to prevent higher bits pollution.
    val += uint32_t(*(It++)) & 0x000000FF;
    val += (uint32_t(*(It++)) << 8) & 0x0000FF00;
    val += (uint32_t(*(It++)) << 16) & 0x00FF0000;
    val += (uint32_t(*(It++)) << 24) & 0xFF000000;
    outs() << val << (It != End ? ", " : "};\n");
  }

  return 0;
}

int main(int ac, char **av) {
  EnablePrettyStackTrace();
  sys::PrintStackTraceOnErrorSignal(av[0]);
  PrettyStackTraceProgram X(ac, av);

  cl::ParseCommandLineOptions(ac, av, "RenderScript to SPIRV translator");

#ifdef RS2SPIRV_DEBUG
  if (RunTests)
    return rs2spirv::TestRunnerContext::runTests();
#endif

  if (IsPrintAsWords)
    return printAsWords();

  return convertLLVMToSPIRV();
}
