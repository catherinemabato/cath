// Copyright 2019 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.skydoc.fakebuildapi.java;

import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.skylarkbuildapi.java.JavaRuntimeInfoApi;
import com.google.devtools.build.lib.skylarkinterface.SkylarkPrinter;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.SkylarkNestedSet;

final class FakeJavaRuntimeInfoApi implements JavaRuntimeInfoApi {

  @Override
  public String javaHome() {
    return null;
  }

  @Override
  public String javaBinaryExecPath() {
    return null;
  }

  @Override
  public String javaHomeRunfilesPath() {
    return null;
  }

  @Override
  public String javaBinaryRunfilesPath() {
    return null;
  }

  @Override
  public SkylarkNestedSet skylarkJavaBaseInputs() {
    return null;
  }

  @Override
  public String toProto(Location loc) throws EvalException {
    return "";
  }

  @Override
  public String toJson(Location loc) throws EvalException {
    return "";
  }

  @Override
  public void repr(SkylarkPrinter printer) {}

  private FakeJavaRuntimeInfoApi() {}
}
