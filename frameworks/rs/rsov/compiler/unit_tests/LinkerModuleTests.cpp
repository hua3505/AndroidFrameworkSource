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

#include "unit_tests/TestRunner.h"

#include "LinkerModule.h"

using namespace llvm;
using namespace rs2spirv;

TEST_CASE("SPIRVLine::hasCode_negative") {
  SPIRVLine L("");
  CHECK(!L.hasCode());

  L.str() = ";";
  CHECK(!L.hasCode());

  L.str() = " ;";
  CHECK(!L.hasCode());

  L.str() = "; OpReturn";
  CHECK(!L.hasCode());

  L.str() = "   ";
  CHECK(!L.hasCode());
}

TEST_CASE("SPIRVLine::hasCode_positive") {
  SPIRVLine L("OpReturn");
  CHECK(L.hasCode());

  L.str() = " OpReturn ";
  CHECK(L.hasCode());

  L.str() = "OpReturn;";
  CHECK(L.hasCode());

  L.str() = "OpReturn ;";
  CHECK(L.hasCode());
}

TEST_CASE("SPIRVLine::getIdentifiers") {
  using Vector = SmallVector<StringRef, 4>;
  Vector Ids;

  SPIRVLine L("OpReturn");
  L.getIdentifiers(Ids);
  CHECK((Ids == Vector{}));
  Ids.clear();

  L.str() = "%uint = OpTypeInt 32 0";
  L.getIdentifiers(Ids);
  CHECK((Ids == Vector{"%uint"}));
  Ids.clear();

  L.str() = "%x = OpTypeStruct %float";
  L.getIdentifiers(Ids);
  CHECK((Ids == Vector{"%x", "%float"}));
  Ids.clear();
}

TEST_CASE("SPIRVLine::getLHSIdentifier") {
  SPIRVLine L("OpReturn");
  CHECK(!L.getLHSIdentifier());

  L.str() = "%uint = OpTypeInt 32 0";
  auto Id = L.getLHSIdentifier();
  CHECK(Id);
  CHECK(*Id == "%uint");

  L.str() = "%12 = OpConstant %uint 0";
  Id = L.getLHSIdentifier();
  CHECK(Id);
  CHECK(*Id == "%12");
}

TEST_CASE("SPIRVLine::getRHSIdentifiers") {
  using Vector = SmallVector<StringRef, 4>;
  Vector Ids;

  SPIRVLine L("OpReturn");
  L.getRHSIdentifiers(Ids);
  CHECK((Ids == Vector{}));
  Ids.clear();

  L.str() = "%uint = OpTypeInt 32 0";
  L.getRHSIdentifiers(Ids);
  CHECK((Ids == Vector{}));
  Ids.clear();

  L.str() = "%x = OpTypeStruct %float";
  L.getRHSIdentifiers(Ids);
  CHECK((Ids == Vector{"%float"}));
  Ids.clear();

  L.str() = "%x = OpTypeStruct %float %uint";
  L.getRHSIdentifiers(Ids);
  CHECK((Ids == Vector{"%float", "%uint"}));
  Ids.clear();
}

TEST_CASE("SPIRVLine::getRHS") {
  SPIRVLine L("OpReturn");
  auto Res = L.getRHS();
  CHECK(!Res);

  L.str() = "%float = OpTypeFloat 32";
  Res = L.getRHS();
  CHECK(Res);
  CHECK(*Res == "OpTypeFloat 32");
}

TEST_CASE("SPIRVLine::replaceId") {
  SPIRVLine L("OpReturn");
  bool Res = L.replaceId("%uint", "%void");
  CHECK(!Res);
  CHECK(L.str() == "OpReturn");

  L.str() = "%entry = OpLabel";
  Res = L.replaceId("%wtw", "%twt");
  CHECK(!Res);
  CHECK(L.str() == "%entry = OpLabel");

  Res = L.replaceId("%entry", "%x");
  CHECK(Res);
  CHECK(L.str() == "%x = OpLabel");

  L.str() = "%7 = OpTypeFunction %v4float %v4float";
  Res = L.replaceId("%7", "%8");
  CHECK(Res);
  CHECK(L.str() == "%8 = OpTypeFunction %v4float %v4float");
  Res = L.replaceId("%v4float", "%void");
  CHECK(Res);
  CHECK(L.str() == "%8 = OpTypeFunction %void %v4float");
  Res = L.replaceId("%v4float", "%void");
  CHECK(Res);
  CHECK(L.str() == "%8 = OpTypeFunction %void %void");
}

TEST_CASE("SPIRVLine::replaceStr") {
  SPIRVLine L("OpReturn");
  bool Res = L.replaceStr("OpLoad", "OpStore");
  CHECK(!Res);
  CHECK(L.str() == "OpReturn");
  Res = L.replaceStr("OpReturn", "OpFunctionEnd");
  CHECK(Res);
  CHECK(L.str() == "OpFunctionEnd");

  L.str() = "%16 = OpUndef %v4float";
  Res = L.replaceStr("OpUndef", "OpDef");
  CHECK(Res);
  CHECK(L.str() == "%16 = OpDef %v4float");
}
