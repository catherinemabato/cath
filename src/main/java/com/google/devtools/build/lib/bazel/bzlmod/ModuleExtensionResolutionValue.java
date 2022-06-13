// Copyright 2021 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.bazel.bzlmod;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.skyframe.SkyFunctions;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationConstant;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;

/** The result of {@link ModuleExtensionResolutionFunction}. */
@AutoValue
public abstract class ModuleExtensionResolutionValue implements SkyValue {
  @SerializationConstant
  public static final SkyKey KEY = () -> SkyFunctions.MODULE_EXTENSION_RESOLUTION;

  /**
   * A mapping from a canonical repo name of a repo generated by a module extension, to the package
   * containing (only) that repo.
   */
  public abstract ImmutableMap<RepositoryName, Package> getCanonicalRepoNameToPackage();

  /**
   * A mapping from a canonical repo name of a repo generated by a module extension, to the ID of
   * that extension.
   */
  public abstract ImmutableMap<RepositoryName, ModuleExtensionId>
      getCanonicalRepoNameToExtensionId();

  /**
   * A mapping from a module extension ID, to the list of "internal" names of the repos generated by
   * that extension. The "internal" name is the name directly used by the extension when
   * instantiating a repo rule.
   */
  public abstract ImmutableListMultimap<ModuleExtensionId, String>
      getExtensionIdToRepoInternalNames();

  public static ModuleExtensionResolutionValue create(
      ImmutableMap<RepositoryName, Package> canonicalRepoNameToPackage,
      ImmutableMap<RepositoryName, ModuleExtensionId> canonicalRepoNameToExtensionId,
      ImmutableListMultimap<ModuleExtensionId, String> extensionIdToRepoInternalNames) {
    return new AutoValue_ModuleExtensionResolutionValue(
        canonicalRepoNameToPackage, canonicalRepoNameToExtensionId, extensionIdToRepoInternalNames);
  }
}
