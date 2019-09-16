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
package com.google.devtools.build.lib.rules.android;

import com.google.auto.value.AutoValue;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.rules.android.AndroidConfiguration.AndroidAaptVersion;
import javax.annotation.Nullable;

/** Helper for creating an {@code aapt2 optimize} action. */
@AutoValue
abstract class Aapt2OptimizeActionBuilder {

  void registerAction(AndroidDataContext dataContext) {
    BusyBoxActionBuilder builder =
        BusyBoxActionBuilder.create(dataContext, "AAPT2_OPTIMIZE")
            .addAapt(AndroidAaptVersion.AAPT2)
            .addFlag("--");
    if (resourcePathShorteningMapOut() != null) {
      builder
          .addFlag("--enable-resource-path-shortening")
          .addOutput("--resource-path-shortening-map", resourcePathShorteningMapOut());
    }
    if (resourceNameObfuscationExemptionList() != null) {
      builder
          .addFlag("--enable-resource-name-obfuscation")
          .addInput(
              "--resource-name-obfuscation-exemption-list", resourceNameObfuscationExemptionList());
    }
    builder
        .addOutput("-o", optimizedApkOut())
        .addInput(resourceApk())
        .buildAndRegister("Optimizing Android resources", "Aapt2Optimize");
  }

  abstract Artifact resourceApk();

  abstract Artifact optimizedApkOut();

  @Nullable
  abstract Artifact resourcePathShorteningMapOut();

  @Nullable
  abstract Artifact resourceNameObfuscationExemptionList();

  static Builder builder() {
    return new AutoValue_Aapt2OptimizeActionBuilder.Builder();
  }

  // NOTE(bcsf): I'm aware this is Aapt2OptimizeActionBuilder.Builder. I'm ok with it. Are you?
  @AutoValue.Builder
  abstract static class Builder {
    abstract Builder setResourceApk(Artifact apk);

    abstract Builder setOptimizedApkOut(Artifact apk);

    abstract Builder setResourcePathShorteningMapOut(Artifact map);

    abstract Builder setResourceNameObfuscationExemptionList(Artifact whitelist);

    abstract Aapt2OptimizeActionBuilder build();
  }
}
