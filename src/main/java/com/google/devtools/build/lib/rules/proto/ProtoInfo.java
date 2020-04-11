// Copyright 2014 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.rules.proto;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.packages.BuiltinProvider;
import com.google.devtools.build.lib.packages.NativeInfo;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.skylarkbuildapi.ProtoInfoApi;
import com.google.devtools.build.lib.skylarkbuildapi.proto.ProtoBootstrap;
import com.google.devtools.build.lib.syntax.Depset;
import com.google.devtools.build.lib.syntax.Location;
import com.google.devtools.build.lib.syntax.SkylarkType;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.vfs.PathFragment;

/**
 * Configured target classes that implement this class can contribute .proto files to the
 * compilation of proto_library rules.
 */
@Immutable
@AutoCodec
public final class ProtoInfo extends NativeInfo implements ProtoInfoApi<Artifact> {
  /** Provider class for {@link ProtoInfo} objects. */
  public static class ProtoInfoProvider extends BuiltinProvider<ProtoInfo>
      implements ProtoInfoProviderApi {
    public ProtoInfoProvider() {
      super(ProtoBootstrap.PROTO_INFO_STARLARK_NAME, ProtoInfo.class);
    }
  }

  public static final ProtoInfoProvider PROVIDER = new ProtoInfoProvider();

  private static ImmutableList<Artifact> extractProtoSources(ImmutableList<ProtoSource> sources) {
    ImmutableList.Builder<Artifact> builder = ImmutableList.builder();
    for (ProtoSource source : sources) {
      builder.add(source.getSourceFile());
    }
    return builder.build();
  }

  private static ImmutableList<Artifact> extractOriginalProtoSources(
      ImmutableList<ProtoSource> sources) {
    ImmutableList.Builder<Artifact> builder = ImmutableList.builder();
    for (ProtoSource source : sources) {
      builder.add(source.getOriginalSourceFile());
    }
    return builder.build();
  }

  // Direct.
  private final ImmutableList<ProtoSource> directSources;
  private final Artifact directDescriptorSet;
  private final PathFragment directProtoSourceRoot;
  private final ImmutableList<Artifact> directProtoSources;
  private final ImmutableList<Artifact> originalDirectProtoSources;

  // Transitive.
  private final NestedSet<ProtoSource> transitiveSources;
  private final NestedSet<Artifact> transitiveDescriptorSets;
  private final NestedSet<Artifact> transitiveProtoSources;
  private final NestedSet<Artifact> originalTransitiveProtoSources;
  private final NestedSet<String> transitiveProtoSourceRoots;

  // Layering checks.
  // TODO(yannic): Consider removing some of these. It should be sufficient to do
  // layering checks when creating the descriptor-set.
  private final NestedSet<ProtoSource> exportedSources;
  private final NestedSet<ProtoSource> strictImportableSources;
  private final NestedSet<ProtoSource> publicImportSources;

  // Misc (deprecated).
  private final NestedSet<Artifact> strictImportableProtoSourcesForDependents;
  private final NestedSet<Pair<Artifact, String>>
      strictImportableProtoSourcesImportPathsForDependents;

  @AutoCodec.Instantiator
  public ProtoInfo(
      // Direct.
      ImmutableList<ProtoSource> directSources,
      Artifact directDescriptorSet,
      PathFragment directProtoSourceRoot,
      // Transitive.
      NestedSet<ProtoSource> transitiveSources,
      NestedSet<Artifact> transitiveDescriptorSets,
      NestedSet<Artifact> transitiveProtoSources,
      NestedSet<Artifact> originalTransitiveProtoSources,
      NestedSet<String> transitiveProtoSourceRoots,
      // Layering checks.
      NestedSet<ProtoSource> exportedSources,
      NestedSet<ProtoSource> strictImportableSources,
      NestedSet<ProtoSource> publicImportSources,
      // Misc (deprecated).
      NestedSet<Artifact> strictImportableProtoSourcesForDependents,
      NestedSet<Pair<Artifact, String>> strictImportableProtoSourcesImportPathsForDependents,
      Location location) {
    super(PROVIDER, location);

    // Direct.
    this.directSources = directSources;
    this.directDescriptorSet = directDescriptorSet;
    this.directProtoSourceRoot = directProtoSourceRoot;

    // Transitive.
    this.transitiveSources = transitiveSources;
    this.transitiveDescriptorSets = transitiveDescriptorSets;
    this.transitiveProtoSources = transitiveProtoSources;
    this.originalTransitiveProtoSources = originalTransitiveProtoSources;
    this.transitiveProtoSourceRoots = transitiveProtoSourceRoots;

    // Layering checks.
    this.exportedSources = exportedSources;
    this.strictImportableSources = strictImportableSources;
    this.publicImportSources = publicImportSources;

    // Misc (deprecated).
    this.strictImportableProtoSourcesForDependents = strictImportableProtoSourcesForDependents;
    this.strictImportableProtoSourcesImportPathsForDependents =
        strictImportableProtoSourcesImportPathsForDependents;

    // Derived.
    this.directProtoSources = extractProtoSources(directSources);
    this.originalDirectProtoSources = extractOriginalProtoSources(directSources);
  }

  /** The {@code .proto} source files in this {@code proto_library}'s {@code srcs}. */
  public ImmutableList<ProtoSource> getDirectSources() {
    return directSources;
  }

  /**
   * The proto source files that are used in compiling this {@code proto_library}.
   */
  @Override
  public ImmutableList<Artifact> getDirectProtoSources() {
    return directProtoSources;
  }

  /**
   * The non-virtual proto sources of the {@code proto_library} declaring this provider.
   *
   * <p>Different from {@link #getDirectProtoSources()} if a transitive dependency has {@code
   * import_prefix} or the like.
   */
  public ImmutableList<Artifact> getOriginalDirectProtoSources() {
    return originalDirectProtoSources;
  }

  /**
   * The source root of the current library.
   *
   * <p>For Bazel, this is always a (logical) prefix of all direct sources. For Blaze, this is
   * currently a lie for {@code proto_library} targets with generated sources.
   */
  @Override
  public String getDirectProtoSourceRoot() {
    return directProtoSourceRoot.getSafePathString();
  }

  /** The proto sources in the transitive closure of this rule. */
  @Override
  public Depset /*<Artifact>*/ getTransitiveProtoSourcesForStarlark() {
    return Depset.of(Artifact.TYPE, getTransitiveProtoSources());
  }

  /**
   * The {@code .proto} source files in this {@code proto_library}'s {@code srcs} and all of its
   * transitive dependencies.
   */
  public NestedSet<ProtoSource> getTransitiveSources() {
    return transitiveSources;
  }

  public NestedSet<Artifact> getTransitiveProtoSources() {
    return transitiveProtoSources;
  }

  /**
   * The non-virtual transitive proto source files.
   *
   * <p>Different from {@link #getTransitiveProtoSources()} if a transitive dependency has {@code
   * import_prefix} or the like.
   */
  public NestedSet<Artifact> getOriginalTransitiveProtoSources() {
    return originalTransitiveProtoSources;
  }

  /**
   * The proto source roots of the transitive closure of this rule. These flags will be passed to
   * {@code protoc} in the specified order, via the {@code --proto_path} flag.
   */
  @Override
  public Depset /*<String>*/ getTransitiveProtoSourceRootsForStarlark() {
    return Depset.of(SkylarkType.STRING, transitiveProtoSourceRoots);
  }

  public NestedSet<String> getTransitiveProtoSourceRoots() {
    return transitiveProtoSourceRoots;
  }

  @Deprecated
  @Override
  public Depset /*<Artifact>*/ getTransitiveImports() {
    return getTransitiveProtoSourcesForStarlark();
  }

  /**
   * Returns the set of source files importable by rules directly depending on the rule declaring
   * this provider if strict dependency checking is in effect.
   *
   * <p>(strict dependency checking: when a target can only include / import source files from its
   * direct dependencies, but not from transitive ones)
   */
  @Override
  public Depset /*<Artifact>*/ getStrictImportableProtoSourcesForDependentsForStarlark() {
    return Depset.of(Artifact.TYPE, strictImportableProtoSourcesForDependents);
  }

  public NestedSet<Artifact> getStrictImportableProtoSourcesForDependents() {
    return strictImportableProtoSourcesForDependents;
  }

  /**
   * Returns the set of source files and import paths importable by rules directly depending on the
   * rule declaring this provider if strict dependency checking is in effect.
   *
   * <p>(strict dependency checking: when a target can only include / import source files from its
   * direct dependencies, but not from transitive ones)
   */
  public NestedSet<Pair<Artifact, String>>
      getStrictImportableProtoSourcesImportPathsForDependents() {
    return strictImportableProtoSourcesImportPathsForDependents;
  }

  /**
   * Be careful while using this artifact - it is the parsing of the transitive set of .proto files.
   * It's possible to cause a O(n^2) behavior, where n is the length of a proto chain-graph.
   * (remember that proto-compiler reads all transitive .proto files, even when producing the
   * direct-srcs descriptor set)
   */
  @Override
  public Artifact getDirectDescriptorSet() {
    return directDescriptorSet;
  }

  /**
   * Be careful while using this artifact - it is the parsing of the transitive set of .proto files.
   * It's possible to cause a O(n^2) behavior, where n is the length of a proto chain-graph.
   * (remember that proto-compiler reads all transitive .proto files, even when producing the
   * direct-srcs descriptor set)
   */
  @Override
  public Depset /*<Artifact>*/ getTransitiveDescriptorSetsForStarlark() {
    return Depset.of(Artifact.TYPE, transitiveDescriptorSets);
  }

  public NestedSet<Artifact> getTransitiveDescriptorSets() {
    return transitiveDescriptorSets;
  }

  /**
   * Returns a set of {@code .proto} sources that may be imported by {@code proto_library} targets
   * directly depending on this {@code ProtoInfo}.
   */
  public NestedSet<ProtoSource> getExportedSources() {
    return exportedSources;
  }

  /**
   * Returns a set of {@code .proto} sources that may be imported by this {@code proto_library}
   * target.
   */
  public NestedSet<ProtoSource> getStrictImportableSources() {
    return strictImportableSources;
  }

  /**
   * Returns a set of {@code .proto} sources that may be re-exported by this {@code proto_library}'s
   * direct sources.
   */
  NestedSet<ProtoSource> getPublicImportSources() {
    return publicImportSources;
  }
}
