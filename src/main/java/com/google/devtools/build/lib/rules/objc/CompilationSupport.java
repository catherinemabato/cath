// Copyright 2015 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.rules.objc;

import static com.google.devtools.build.lib.packages.ImplicitOutputsFunction.fromTemplates;
import static com.google.devtools.build.lib.rules.cpp.Link.LINK_LIBRARY_FILETYPES;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.DEFINE;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.DYNAMIC_FRAMEWORK_FILE;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.FORCE_LOAD_LIBRARY;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.FRAMEWORK_DIR;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.FRAMEWORK_SEARCH_PATH_ONLY;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.Flag.USES_CPP;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.Flag.USES_SWIFT;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.HEADER;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.IMPORTED_LIBRARY;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.INCLUDE;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.INCLUDE_SYSTEM;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.LIBRARY;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.MODULE_MAP;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.SDK_DYLIB;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.SDK_FRAMEWORK;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.STATIC_FRAMEWORK_FILE;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.SWIFT_MODULE;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.WEAK_SDK_FRAMEWORK;
import static com.google.devtools.build.lib.rules.objc.ObjcRuleClasses.CLANG;
import static com.google.devtools.build.lib.rules.objc.ObjcRuleClasses.CLANG_PLUSPLUS;
import static com.google.devtools.build.lib.rules.objc.ObjcRuleClasses.COMPILABLE_SRCS_TYPE;
import static com.google.devtools.build.lib.rules.objc.ObjcRuleClasses.DSYMUTIL;
import static com.google.devtools.build.lib.rules.objc.ObjcRuleClasses.HEADERS;
import static com.google.devtools.build.lib.rules.objc.ObjcRuleClasses.NON_ARC_SRCS_TYPE;
import static com.google.devtools.build.lib.rules.objc.ObjcRuleClasses.PRECOMPILED_SRCS_TYPE;
import static com.google.devtools.build.lib.rules.objc.ObjcRuleClasses.SRCS_TYPE;
import static com.google.devtools.build.lib.rules.objc.ObjcRuleClasses.STRIP;
import static com.google.devtools.build.lib.rules.objc.ObjcRuleClasses.SWIFT;
import static com.google.devtools.build.lib.rules.objc.ObjcRuleClasses.SWIFT_SOURCES;
import static java.nio.charset.StandardCharsets.ISO_8859_1;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.actions.ParameterFile;
import com.google.devtools.build.lib.analysis.AnalysisEnvironment;
import com.google.devtools.build.lib.analysis.FilesToRunProvider;
import com.google.devtools.build.lib.analysis.PrerequisiteArtifacts;
import com.google.devtools.build.lib.analysis.RuleConfiguredTarget.Mode;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.actions.CommandLine;
import com.google.devtools.build.lib.analysis.actions.CustomCommandLine;
import com.google.devtools.build.lib.analysis.actions.ParameterFileWriteAction;
import com.google.devtools.build.lib.analysis.actions.SpawnAction;
import com.google.devtools.build.lib.analysis.actions.SpawnActionTemplate;
import com.google.devtools.build.lib.analysis.actions.SpawnActionTemplate.OutputPathMapper;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.ImplicitOutputsFunction.SafeImplicitOutputsFunction;
import com.google.devtools.build.lib.packages.RuleClass.ConfiguredTargetFactory.RuleErrorException;
import com.google.devtools.build.lib.packages.TargetUtils;
import com.google.devtools.build.lib.rules.apple.AppleConfiguration;
import com.google.devtools.build.lib.rules.apple.AppleToolchain;
import com.google.devtools.build.lib.rules.apple.Platform;
import com.google.devtools.build.lib.rules.apple.Platform.PlatformType;
import com.google.devtools.build.lib.rules.cpp.CppCompileAction.DotdFile;
import com.google.devtools.build.lib.rules.cpp.CppModuleMap;
import com.google.devtools.build.lib.rules.cpp.CppModuleMapAction;
import com.google.devtools.build.lib.rules.objc.XcodeProvider.Builder;
import com.google.devtools.build.lib.rules.test.InstrumentedFilesCollector;
import com.google.devtools.build.lib.rules.test.InstrumentedFilesCollector.InstrumentationSpec;
import com.google.devtools.build.lib.rules.test.InstrumentedFilesCollector.LocalMetadataCollector;
import com.google.devtools.build.lib.rules.test.InstrumentedFilesProvider;
import com.google.devtools.build.lib.syntax.Type;
import com.google.devtools.build.lib.util.FileTypeSet;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.util.Preconditions;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Support for rules that compile sources. Provides ways to determine files that should be output,
 * registering Xcode settings and generating the various actions that might be needed for
 * compilation.
 *
 * <p>Methods on this class can be called in any order without impacting the result.
 */
public final class CompilationSupport {

  @VisibleForTesting
  static final String OBJC_MODULE_CACHE_DIR_NAME = "_objc_module_cache";

  @VisibleForTesting
  static final String MODULES_CACHE_PATH_WARNING =
      "setting '-fmodules-cache-path' manually in copts is unsupported";

  @VisibleForTesting
  static final String ABSOLUTE_INCLUDES_PATH_FORMAT =
      "The path '%s' is absolute, but only relative paths are allowed.";

  @VisibleForTesting
  static final ImmutableList<String> LINKER_COVERAGE_FLAGS =
      ImmutableList.of("-ftest-coverage", "-fprofile-arcs");

  @VisibleForTesting
  static final ImmutableList<String> LINKER_LLVM_COVERAGE_FLAGS =
      ImmutableList.of("-fprofile-instr-generate");

  // Flags for clang 6.1(xcode 6.4)
  @VisibleForTesting
  static final ImmutableList<String> CLANG_GCOV_COVERAGE_FLAGS =
      ImmutableList.of("-fprofile-arcs", "-ftest-coverage");

  @VisibleForTesting
  static final ImmutableList<String> CLANG_LLVM_COVERAGE_FLAGS =
      ImmutableList.of("-fprofile-instr-generate", "-fcoverage-mapping");

  // These are added by Xcode when building, because the simulator is built on OSX
  // frameworks so we aim compile to match the OSX objc runtime.
  @VisibleForTesting
  static final ImmutableList<String> SIMULATOR_COMPILE_FLAGS =
      ImmutableList.of(
          "-fexceptions", "-fasm-blocks", "-fobjc-abi-version=2", "-fobjc-legacy-dispatch");

  /**
   * Returns the location of the xcrunwrapper tool.
   */
  public static final FilesToRunProvider xcrunwrapper(RuleContext ruleContext) {
    return ruleContext.getExecutablePrerequisite("$xcrunwrapper", Mode.HOST);
  }

  /**
   * Returns the location of the libtool tool.
   */
  public static final FilesToRunProvider libtool(RuleContext ruleContext) {
    return ruleContext.getExecutablePrerequisite(ObjcRuleClasses.LIBTOOL_ATTRIBUTE, Mode.HOST);
  }

  /**
   * Files which can be instrumented along with the attributes in which they may occur and the
   * attributes along which they are propagated from dependencies (via
   * {@link InstrumentedFilesProvider}).
   */
  private static final InstrumentationSpec INSTRUMENTATION_SPEC =
      new InstrumentationSpec(
              FileTypeSet.of(
                  ObjcRuleClasses.NON_CPP_SOURCES,
                  ObjcRuleClasses.CPP_SOURCES,
                  ObjcRuleClasses.SWIFT_SOURCES,
                  HEADERS))
          .withSourceAttributes("srcs", "non_arc_srcs", "hdrs")
          .withDependencyAttributes("deps", "data", "binary", "xctest_app");

  private static final String FRAMEWORK_SUFFIX = ".framework";

  private static final Predicate<String> INCLUDE_DIR_OPTION_IN_COPTS =
      new Predicate<String>() {
        @Override
        public boolean apply(String copt) {
          return copt.startsWith("-I") && copt.length() > 2;
        }
      };

  /** Predicate that matches all artifacts that can be used in a Clang module map. */
  private static final Predicate<Artifact> MODULE_MAP_HEADER =
      new Predicate<Artifact>() {
        @Override
        public boolean apply(Artifact artifact) {
          if (artifact.isTreeArtifact()) {
            // Tree artifact is basically a directory, which does not have any information about
            // the contained files and their extensions. Here we assume the passed in tree artifact
            // contains proper header files with .h extension.
            return true;
          } else {
            // The current clang (clang-600.0.57) on Darwin doesn't support 'textual', so we can't
            // have '.inc' files in the module map (since they're implictly textual).
            // TODO(bazel-team): Use HEADERS file type once clang-700 is the base clang we support.
            return artifact.getFilename().endsWith(".h");
          }
        }
      };

  /** Selects cc libraries that have alwayslink=1. */
  private static final Predicate<Artifact> ALWAYS_LINKED_CC_LIBRARY =
      new Predicate<Artifact>() {
        @Override
        public boolean apply(Artifact input) {
          return LINK_LIBRARY_FILETYPES.matches(input.getFilename());
        }
      };

  /**
   * Defines a library that contains the transitive closure of dependencies.
   */
  public static final SafeImplicitOutputsFunction FULLY_LINKED_LIB =
      fromTemplates("%{name}_fully_linked.a");

  /**
   * Iterable wrapper providing strong type safety for arguments to binary linking.
   */
  static final class ExtraLinkArgs extends IterableWrapper<String> {
    ExtraLinkArgs(String... args) {
      super(args);
    }
  }

  /**
   * Iterable wrapper providing strong type safety for extra compile flags.
   */
  static final class ExtraCompileArgs extends IterableWrapper<String> {
    static final ExtraCompileArgs NONE = new ExtraCompileArgs();
    ExtraCompileArgs(String... args) {
      super(args);
    }
  }

  @VisibleForTesting
  static final String FILE_IN_SRCS_AND_HDRS_WARNING_FORMAT =
      "File '%s' is in both srcs and hdrs.";

  @VisibleForTesting
  static final String FILE_IN_SRCS_AND_NON_ARC_SRCS_ERROR_FORMAT =
      "File '%s' is present in both srcs and non_arc_srcs which is forbidden.";

  static final ImmutableList<String> DEFAULT_COMPILER_FLAGS = ImmutableList.of("-DOS_IOS");

  static final ImmutableList<String> DEFAULT_LINKER_FLAGS = ImmutableList.of("-ObjC");

  /**
   * A mapper that maps input ObjC source {@link Artifact.TreeFileArtifact}s to output
   * object file {@link Artifact.TreeFileArtifact}s.
   */
  private static final OutputPathMapper COMPILE_ACTION_TEMPLATE_OUTPUT_PATH_MAPPER =
      new OutputPathMapper() {
        @Override
        public PathFragment parentRelativeOutputPath(TreeFileArtifact inputTreeFileArtifact) {
          return FileSystemUtils.replaceExtension(
              inputTreeFileArtifact.getParentRelativePath(), ".o");
        }
      };

  /**
   * Returns information about the given rule's compilation artifacts.
   */
  // TODO(bazel-team): Remove this information from ObjcCommon and move it internal to this class.
  static CompilationArtifacts compilationArtifacts(RuleContext ruleContext) {
    return compilationArtifacts(ruleContext,  ObjcRuleClasses.intermediateArtifacts(ruleContext));
  }

  /**
   * Returns information about the given rule's compilation artifacts. Dependencies specified
   * in the current rule's attributes are obtained via {@code ruleContext}. Output locations
   * are determined using the given {@code intermediateArtifacts} object. The fact that these
   * are distinct objects allows the caller to generate compilation actions pertaining to
   * a configuration separate from the current rule's configuration.
   */
  static CompilationArtifacts compilationArtifacts(RuleContext ruleContext,
      IntermediateArtifacts intermediateArtifacts) {
    PrerequisiteArtifacts srcs = ruleContext.getPrerequisiteArtifacts("srcs", Mode.TARGET)
        .errorsForNonMatching(SRCS_TYPE);
    return new CompilationArtifacts.Builder()
        .addSrcs(srcs.filter(COMPILABLE_SRCS_TYPE).list())
        .addNonArcSrcs(
            ruleContext
                .getPrerequisiteArtifacts("non_arc_srcs", Mode.TARGET)
                .errorsForNonMatching(NON_ARC_SRCS_TYPE)
                .list())
        .addPrivateHdrs(srcs.filter(HEADERS).list())
        .addPrecompiledSrcs(srcs.filter(PRECOMPILED_SRCS_TYPE).list())
        .setIntermediateArtifacts(intermediateArtifacts)
        .setPchFile(Optional.fromNullable(ruleContext.getPrerequisiteArtifact("pch", Mode.TARGET)))
        .build();
  }

  private final RuleContext ruleContext;
  private final BuildConfiguration buildConfiguration;
  private final ObjcConfiguration objcConfiguration;
  private final AppleConfiguration appleConfiguration;
  private final CompilationAttributes attributes;
  private final IntermediateArtifacts intermediateArtifacts;

  /**
   * Creates a new compilation support for the given rule.
   */
  public CompilationSupport(RuleContext ruleContext) {
    this(ruleContext, ruleContext.getConfiguration());
  }

  /**
   * Creates a new compilation support for the given rule.
   *
   * <p>All actions will be created under the given build configuration, which may be different than
   * the current rule context configuration.
   */
  public CompilationSupport(RuleContext ruleContext, BuildConfiguration buildConfiguration) {
    this(
        ruleContext,
        buildConfiguration,
        ObjcRuleClasses.intermediateArtifacts(ruleContext, buildConfiguration),
        CompilationAttributes.Builder.fromRuleContext(ruleContext).build());
  }

  /**
   * Creates a new compilation support for the given rule.
   *
   * <p>The compilation and linking flags will be retrieved from the given compilation attributes.
   * The names of the generated artifacts will be retrieved from the given intermediate artifacts.
   *
   * <p>By instantiating multiple compilation supports for the same rule but with intermediate
   * artifacts with different output prefixes, multiple archives can be compiled for the same
   * rule context.
   */
  public CompilationSupport(
      RuleContext ruleContext,
      IntermediateArtifacts intermediateArtifacts,
      CompilationAttributes compilationAttributes) {
    this(ruleContext, ruleContext.getConfiguration(), intermediateArtifacts, compilationAttributes);
  }

  /**
   * Creates a new compilation support for the given rule and build configuration.
   *
   * <p>All actions will be created under the given build configuration, which may be different than
   * the current rule context configuration.
   *
   * <p>The compilation and linking flags will be retrieved from the given compilation attributes.
   * The names of the generated artifacts will be retrieved from the given intermediate artifacts.
   *
   * <p>By instantiating multiple compilation supports for the same rule but with intermediate
   * artifacts with different output prefixes, multiple archives can be compiled for the same
   * rule context.
   */
  public CompilationSupport(
      RuleContext ruleContext,
      BuildConfiguration buildConfiguration,
      IntermediateArtifacts intermediateArtifacts,
      CompilationAttributes compilationAttributes) {
    this.ruleContext = ruleContext;
    this.buildConfiguration = buildConfiguration;
    this.objcConfiguration = buildConfiguration.getFragment(ObjcConfiguration.class);
    this.appleConfiguration = buildConfiguration.getFragment(AppleConfiguration.class);
    this.attributes = compilationAttributes;
    this.intermediateArtifacts = intermediateArtifacts;
  }

  /**
   * Registers all actions necessary to compile this rule's sources and archive them.
   *
   * @param common common information about this rule and its dependencies
   * @return this compilation support
   */
  CompilationSupport registerCompileAndArchiveActions(ObjcCommon common) {
    return registerCompileAndArchiveActions(
        common, ExtraCompileArgs.NONE, ImmutableList.<PathFragment>of());
  }

  /**
   * Registers all actions necessary to compile this rule's sources and archive them.
   *
   * @param common common information about this rule and its dependencies
   * @param priorityHeaders priority headers to be included before the dependency headers
   * @return this compilation support
   */
  CompilationSupport registerCompileAndArchiveActions(
      ObjcCommon common, Iterable<PathFragment> priorityHeaders) {
    return registerCompileAndArchiveActions(common, ExtraCompileArgs.NONE, priorityHeaders);
  }

  /**
   * Registers all actions necessary to compile this rule's sources and archive them.
   *
   * @param common common information about this rule and its dependencies
   * @param extraCompileArgs args to be added to compile actions
   * @return this compilation support
   */
  CompilationSupport registerCompileAndArchiveActions(
      ObjcCommon common, ExtraCompileArgs extraCompileArgs) {
    return registerCompileAndArchiveActions(
        common, extraCompileArgs, ImmutableList.<PathFragment>of());
  }

  /**
   * Registers all actions necessary to compile this rule's sources and archive them.
   *
   * @param common common information about this rule and its dependencies
   * @param extraCompileArgs args to be added to compile actions
   * @param priorityHeaders priority headers to be included before the dependency headers
   * @return this compilation support
   */
  CompilationSupport registerCompileAndArchiveActions(
      ObjcCommon common,
      ExtraCompileArgs extraCompileArgs,
      Iterable<PathFragment> priorityHeaders) {
    if (common.getCompilationArtifacts().isPresent()) {
      registerGenerateModuleMapActions(common.getCompilationArtifacts());
      Optional<CppModuleMap> moduleMap;
      if (objcConfiguration.moduleMapsEnabled()) {
        moduleMap = Optional.of(intermediateArtifacts.unextendedModuleMap());
      } else {
        moduleMap = Optional.absent();
      }
      registerCompileAndArchiveActions(
          common.getCompilationArtifacts().get(),
          common.getObjcProvider(),
          extraCompileArgs,
          priorityHeaders,
          moduleMap);
    }
    return this;
  }

  /**
   * Creates actions to compile each source file individually, and link all the compiled object
   * files into a single archive library.
   */
  private void registerCompileAndArchiveActions(
      CompilationArtifacts compilationArtifacts,
      ObjcProvider objcProvider,
      ExtraCompileArgs extraCompileArgs,
      Iterable<PathFragment> priorityHeaders,
      Optional<CppModuleMap> moduleMap) {
    ImmutableList.Builder<Artifact> objFiles = new ImmutableList.Builder<>();
    for (Artifact sourceFile : compilationArtifacts.getSrcs()) {
      Artifact objFile = intermediateArtifacts.objFile(sourceFile);
      objFiles.add(objFile);
      if (!appleConfiguration.disableNativeSwiftRules()
          && ObjcRuleClasses.SWIFT_SOURCES.matches(sourceFile.getFilename())) {
        registerSwiftCompileAction(sourceFile, objFile, objcProvider);
      } else {
        if (objFile.isTreeArtifact()) {
          registerCompileActionTemplate(
              sourceFile,
              objFile,
              objcProvider,
              priorityHeaders,
              moduleMap,
              compilationArtifacts,
              Iterables.concat(extraCompileArgs, ImmutableList.of("-fobjc-arc")));
        } else {
          registerCompileAction(
              sourceFile,
              objFile,
              objcProvider,
              priorityHeaders,
              moduleMap,
              compilationArtifacts,
              Iterables.concat(extraCompileArgs, ImmutableList.of("-fobjc-arc")));
        }
      }
    }
    for (Artifact nonArcSourceFile : compilationArtifacts.getNonArcSrcs()) {
      Artifact objFile = intermediateArtifacts.objFile(nonArcSourceFile);
      objFiles.add(objFile);
      if (objFile.isTreeArtifact()) {
        registerCompileActionTemplate(
            nonArcSourceFile,
            objFile,
            objcProvider,
            priorityHeaders,
            moduleMap,
            compilationArtifacts,
            Iterables.concat(extraCompileArgs, ImmutableList.of("-fno-objc-arc")));
      } else {
        registerCompileAction(
            nonArcSourceFile,
            objFile,
            objcProvider,
            priorityHeaders,
            moduleMap,
            compilationArtifacts,
            Iterables.concat(extraCompileArgs, ImmutableList.of("-fno-objc-arc")));
      }
    }

    objFiles.addAll(compilationArtifacts.getPrecompiledSrcs());

    if (compilationArtifacts.hasSwiftSources()) {
      registerSwiftModuleMergeAction(compilationArtifacts, objcProvider);
    }

    for (Artifact archive : compilationArtifacts.getArchive().asSet()) {
      registerArchiveActions(objFiles.build(), archive);
    }
  }

  /**
   * Adds a source file to a command line, honoring the useAbsolutePathForActions flag.
   */
  private CustomCommandLine.Builder addSource(CustomCommandLine.Builder commandLine,
      Artifact sourceFile) {
    PathFragment sourceExecPathFragment = sourceFile.getExecPath();
    String sourcePath = sourceExecPathFragment.getPathString();
    if (!sourceExecPathFragment.isAbsolute() && objcConfiguration.getUseAbsolutePathsForActions()) {
      sourcePath = objcConfiguration.getXcodeWorkspaceRoot() + "/" + sourcePath;
    }
    commandLine.add(sourcePath);
    return commandLine;
  }

  private CustomCommandLine.Builder addSource(String argName, CustomCommandLine.Builder commandLine,
      Artifact sourceFile) {
    commandLine.add(argName);
    return addSource(commandLine, sourceFile);
  }

  private CustomCommandLine compileActionCommandLine(
      Artifact sourceFile,
      Artifact objFile,
      ObjcProvider objcProvider,
      Iterable<PathFragment> priorityHeaders,
      Optional<CppModuleMap> moduleMap,
      Optional<Artifact> pchFile,
      Optional<Artifact> dotdFile,
      Iterable<String> otherFlags,
      boolean collectCodeCoverage,
      boolean isCPlusPlusSource,
      boolean hasSwiftSources) {
    CustomCommandLine.Builder commandLine = new CustomCommandLine.Builder().add(CLANG);

    if (isCPlusPlusSource) {
      commandLine.add("-stdlib=libc++");
      commandLine.add("-std=gnu++14");
    }

    if (hasSwiftSources) {
      // Add the directory that contains merged TargetName-Swift.h header to search path, in case
      // any of ObjC files use it.
      commandLine.add("-iquote");
      commandLine.addPath(intermediateArtifacts.swiftHeader().getExecPath().getParentDirectory());
    }

    // The linker needs full debug symbol information to perform binary dead-code stripping.
    if (objcConfiguration.shouldStripBinary()) {
      commandLine.add("-g");
    }

    List<String> coverageFlags = ImmutableList.of();
    if (collectCodeCoverage) {
      if (buildConfiguration.isLLVMCoverageMapFormatEnabled()) {
        coverageFlags = CLANG_LLVM_COVERAGE_FLAGS;
      } else {
        coverageFlags = CLANG_GCOV_COVERAGE_FLAGS;
      }
    }

    commandLine
        .add(compileFlagsForClang(appleConfiguration))
        .add(commonLinkAndCompileFlagsForClang(objcProvider, objcConfiguration, appleConfiguration))
        .add(objcConfiguration.getCoptsForCompilationMode())
        .addBeforeEachPath("-iquote", ObjcCommon.userHeaderSearchPaths(buildConfiguration))
        .addBeforeEachExecPath("-include", pchFile.asSet())
        .addBeforeEachPath("-I", priorityHeaders)
        .addBeforeEachPath("-I", objcProvider.get(INCLUDE))
        .addBeforeEachPath("-isystem", objcProvider.get(INCLUDE_SYSTEM))
        .add(otherFlags)
        .addFormatEach("-D%s", objcProvider.get(DEFINE))
        .add(coverageFlags)
        .add(getCompileRuleCopts());

    // Add input source file arguments
    commandLine.add("-c");
    if (!sourceFile.getExecPath().isAbsolute()
        && objcConfiguration.getUseAbsolutePathsForActions()) {
      String workspaceRoot = objcConfiguration.getXcodeWorkspaceRoot();

      // If the source file is a tree artifact, it means the file is basically a directory that may
      // contain multiple concrete source files at execution time. When constructing the command
      // line, we insert the source tree artifact as a placeholder, which will be replaced with
      // one of its contained source files of type {@link Artifact.TreeFileArtifact} at execution
      // time.
      //
      // We also do something similar for the object file arguments below.
      if (sourceFile.isTreeArtifact()) {
        commandLine.addPlaceholderTreeArtifactFormattedExecPath(workspaceRoot + "/%s", sourceFile);
      } else {
        commandLine.addPaths(workspaceRoot + "/%s", sourceFile.getExecPath());
      }
    } else {
      if (sourceFile.isTreeArtifact()) {
        commandLine.addPlaceholderTreeArtifactExecPath(sourceFile);
      } else {
        commandLine.addPath(sourceFile.getExecPath());
      }
    }

    // Add output object file arguments.
    commandLine.add("-o");
    if (objFile.isTreeArtifact()) {
      commandLine.addPlaceholderTreeArtifactExecPath(objFile);
    } else {
      commandLine.addPath(objFile.getExecPath());
    }

    // Add Dotd file arguments.
    if (dotdFile.isPresent()) {
      commandLine
          .add("-MD")
          .addExecPath("-MF", dotdFile.get());
    }

    // Add module map arguments.
    if (moduleMap.isPresent()) {
      // If modules are enabled for the rule, -fmodules is added to the copts already. (This implies
      // module map usage). Otherwise, we need to pass -fmodule-maps.
      if (!attributes.enableModules()) {
        commandLine.add("-fmodule-maps");
      }
      // -fmodule-map-file only loads the module in Xcode 7, so we add the module maps's directory
      // to the include path instead.
      // TODO(bazel-team): Use -fmodule-map-file when Xcode 6 support is dropped.
      commandLine
          .add("-fmodule-map-file=" +
              moduleMap.get().getArtifact().getExecPath().toString())
          .add("-fmodule-name=" + moduleMap.get().getName());
    }

    return commandLine.build();
  }

  private void registerCompileAction(
      Artifact sourceFile,
      Artifact objFile,
      ObjcProvider objcProvider,
      Iterable<PathFragment> priorityHeaders,
      Optional<CppModuleMap> moduleMap,
      CompilationArtifacts compilationArtifacts,
      Iterable<String> otherFlags) {
    boolean isCPlusPlusSource = ObjcRuleClasses.CPP_SOURCES.matches(sourceFile.getExecPath());
    boolean runCodeCoverage =
        buildConfiguration.isCodeCoverageEnabled() && ObjcRuleClasses.isInstrumentable(sourceFile);
    boolean hasSwiftSources = compilationArtifacts.hasSwiftSources();
    DotdFile dotdFile = intermediateArtifacts.dotdFile(sourceFile);

    CustomCommandLine commandLine =
        compileActionCommandLine(
            sourceFile,
            objFile,
            objcProvider,
            priorityHeaders,
            moduleMap,
            compilationArtifacts.getPchFile(),
            Optional.of(dotdFile.artifact()),
            otherFlags,
            runCodeCoverage,
            isCPlusPlusSource,
            hasSwiftSources);

    Optional<Artifact> gcnoFile = Optional.absent();
    if (runCodeCoverage && !buildConfiguration.isLLVMCoverageMapFormatEnabled()) {
      gcnoFile = Optional.of(intermediateArtifacts.gcnoFile(sourceFile));
    }

    Optional<Artifact> swiftHeader = Optional.absent();
    if (hasSwiftSources) {
      swiftHeader = Optional.of(intermediateArtifacts.swiftHeader());
    }

    NestedSet<Artifact> moduleMapInputs = NestedSetBuilder.emptySet(Order.STABLE_ORDER);
    if (objcConfiguration.moduleMapsEnabled()) {
      moduleMapInputs = objcProvider.get(MODULE_MAP);
    }

    Optional<Artifact> moduleMapArtifact = moduleMap.transform(new Function<CppModuleMap, Artifact>() {
      @Override public Artifact apply(CppModuleMap cppModuleMap) {
        return cppModuleMap.getArtifact();
      }
    });

    // TODO(bazel-team): Remove private headers from inputs once they're added to the provider.
    ruleContext.registerAction(
        ObjcCompileAction.Builder.createObjcCompileActionBuilderWithAppleEnv(
                appleConfiguration, appleConfiguration.getSingleArchPlatform())
            .setDotdPruningPlan(objcConfiguration.getDotdPruningPlan())
            .setSourceFile(sourceFile)
            .addMandatoryInputs(swiftHeader.asSet())
            .addTransitiveMandatoryInputs(moduleMapInputs)
            .addTransitiveMandatoryInputs(objcProvider.get(STATIC_FRAMEWORK_FILE))
            .addTransitiveMandatoryInputs(objcProvider.get(DYNAMIC_FRAMEWORK_FILE))
            .setDotdFile(dotdFile)
            .addInputs(compilationArtifacts.getPrivateHdrs())
            .addInputs(compilationArtifacts.getPchFile().asSet())
            .setMnemonic("ObjcCompile")
            .setExecutable(xcrunwrapper(ruleContext))
            .setCommandLine(commandLine)
            .addInputs(moduleMapArtifact.asSet())
            .addOutput(objFile)
            .addOutputs(gcnoFile.asSet())
            .addOutput(dotdFile.artifact())
            .addTransitiveInputs(objcProvider.get(HEADER))
            .build(ruleContext));
  }

  /**
   * Registers a SpawnActionTemplate to compile the source file tree artifact, {@code sourceFiles},
   * which can contain multiple concrete source files unknown at analysis time. At execution time,
   * the SpawnActionTemplate will register one ObjcCompile action for each individual source file
   * under {@code sourceFiles}.
   *
   * <p>Note that this method currently does not support code coverage and sources other than ObjC
   * sources.
   *
   * @param sourceFiles tree artifact containing source files to compile
   * @param objFiles tree artifact containing object files compiled from {@code sourceFiles}
   * @param objcProvider ObjcProvider instance for this invocation
   * @param priorityHeaders priority headers to be included before the dependency headers
   * @param moduleMap the module map generated from the associated headers
   * @param compilationArtifacts the CompilationArtifacts instance for this invocation
   * @param otherFlags extra compilation flags to add to the compile action command line
   */
  private void registerCompileActionTemplate(
      Artifact sourceFiles,
      Artifact objFiles,
      ObjcProvider objcProvider,
      Iterable<PathFragment> priorityHeaders,
      Optional<CppModuleMap> moduleMap,
      CompilationArtifacts compilationArtifacts,
      Iterable<String> otherFlags) {
    CustomCommandLine commandLine = compileActionCommandLine(
        sourceFiles,
        objFiles,
        objcProvider,
        priorityHeaders,
        moduleMap,
        compilationArtifacts.getPchFile(),
        Optional.<Artifact>absent(),
        otherFlags,
        /* runCodeCoverage=*/false,
        /* isCPlusPlusSource=*/false,
        /* hasSwiftSources=*/false);

    AppleConfiguration appleConfiguration = ruleContext.getFragment(AppleConfiguration.class);
    Platform platform = appleConfiguration.getSingleArchPlatform();

    NestedSet<Artifact> moduleMapInputs = NestedSetBuilder.emptySet(Order.STABLE_ORDER);
    if (objcConfiguration.moduleMapsEnabled()) {
      moduleMapInputs = objcProvider.get(MODULE_MAP);
    }

    ruleContext.registerAction(
        new SpawnActionTemplate.Builder(sourceFiles, objFiles)
            .setMnemonics("ObjcCompileActionTemplate", "ObjcCompile")
            .setExecutable(xcrunwrapper(ruleContext))
            .setCommandLineTemplate(commandLine)
            .setEnvironment(ObjcRuleClasses.appleToolchainEnvironment(appleConfiguration, platform))
            .setExecutionInfo(ObjcRuleClasses.darwinActionExecutionRequirement())
            .setOutputPathMapper(COMPILE_ACTION_TEMPLATE_OUTPUT_PATH_MAPPER)
            .addCommonTransitiveInputs(objcProvider.get(HEADER))
            .addCommonTransitiveInputs(moduleMapInputs)
            .addCommonInputs(compilationArtifacts.getPrivateHdrs())
            .addCommonTransitiveInputs(objcProvider.get(STATIC_FRAMEWORK_FILE))
            .addCommonTransitiveInputs(objcProvider.get(DYNAMIC_FRAMEWORK_FILE))
            .addCommonInputs(compilationArtifacts.getPchFile().asSet())
            .build(ruleContext.getActionOwner()));
  }

  /**
   * Returns the copts for the compile action in the current rule context (using a combination
   * of the rule's "copts" attribute as well as the current configuration copts).
   */
  Iterable<String> getCompileRuleCopts() {
    List<String> copts = Lists.newArrayList(
        Iterables.concat(objcConfiguration.getCopts(), attributes.copts()));

    for (String copt : copts) {
      if (copt.contains("-fmodules-cache-path")) {
        // Bazel decides on the cache path location.
        ruleContext.ruleWarning(MODULES_CACHE_PATH_WARNING);
      }
    }

    if (attributes.enableModules()) {
      copts.add("-fmodules");
    }
    if (copts.contains("-fmodules")) {
      // If modules are enabled, clang caches module information. If unspecified, this is a
      // system-wide cache directory, which is a problem for remote executors which may run
      // multiple actions with different source trees that can't share this cache.
      // We thus set its path to the root of the genfiles directory.
      // Unfortunately, this cache contains non-hermetic information, thus we avoid declaring it as
      // an implicit output (as outputs must be hermetic).
      String cachePath =
          buildConfiguration.getGenfilesFragment() + "/" + OBJC_MODULE_CACHE_DIR_NAME;
      copts.add("-fmodules-cache-path=" + cachePath);
    }
    return copts;
  }

  /**
   * Compiles a single swift file.
   *
   * @param sourceFile the artifact to compile
   * @param objFile the resulting object artifact
   * @param objcProvider ObjcProvider instance for this invocation
   */
  private void registerSwiftCompileAction(
      Artifact sourceFile,
      Artifact objFile,
      ObjcProvider objcProvider) {

    // Compiling a single swift file requires knowledge of all of the other
    // swift files in the same module. The primary file ({@code sourceFile}) is
    // compiled to an object file, while the remaining files are used to resolve
    // symbols (they behave like c/c++ headers in this context).
    ImmutableSet.Builder<Artifact> otherSwiftSourcesBuilder = ImmutableSet.builder();
    for (Artifact otherSourceFile : compilationArtifacts(ruleContext).getSrcs()) {
      if (ObjcRuleClasses.SWIFT_SOURCES.matches(otherSourceFile.getFilename())
          && otherSourceFile != sourceFile) {
        otherSwiftSourcesBuilder.add(otherSourceFile);
      }
    }
    ImmutableSet<Artifact> otherSwiftSources = otherSwiftSourcesBuilder.build();

    Iterable<String> swiftopts = Iterables.concat(objcConfiguration.getSwiftopts(), attributes.swiftopts());

    CustomCommandLine.Builder commandLine = new CustomCommandLine.Builder()
        .add(SWIFT)
        .add("-frontend")
        .add("-emit-object")
        .add("-target").add(swiftTarget(appleConfiguration, objcConfiguration))
        .add("-sdk").add(AppleToolchain.sdkDir())
        .add("-enable-objc-interop")
        .add(objcConfiguration.getSwiftCoptsForCompilationMode())
        .add(swiftopts);

    if (objcConfiguration.generateDsym()) {
      commandLine.add("-g");
    }

    commandLine
      .add("-module-name").add(getModuleName());

    // Hack, but not better way to do this.

    if (!sourceFile.getFilename().equals("main.swift")) {
      commandLine.add("-parse-as-library");
    }
    addSource("-primary-file", commandLine, sourceFile)
      .addExecPaths(otherSwiftSources)
      .addExecPath("-o", objFile)
      .addExecPath("-emit-module-path", intermediateArtifacts.swiftModuleFile(sourceFile))
      // The swift compiler will invoke clang itself when compiling module maps. This invocation
      // does not include the current working directory, causing cwd-relative imports to fail.
      // Including the current working directory to the header search paths ensures that these
      // relative imports will work.
      .add("-Xcc").add("-I.");

    // Using addExecPathBefore here adds unnecessary quotes around '-Xcc -I', which trips the
    // compiler. Using two add() calls generates a correctly formed command line.
    for (PathFragment directory : objcProvider.get(INCLUDE).toList()) {
      commandLine.add("-Xcc").add(String.format("-I%s", directory.toString()));
    }

    // Make it so swift gets our command line options
    for (String option : commonLinkAndCompileFlagsForClang(objcProvider, objcConfiguration,
        appleConfiguration)) {
      commandLine.add("-Xcc").add(option);
    }

    for (String option : getCompileRuleCopts()) {
      commandLine.add("-Xcc").add(option);
    }

    ImmutableList.Builder<Artifact> inputHeaders = ImmutableList.<Artifact>builder()
        .addAll(attributes.hdrs())
        .addAll(attributes.ccHdrs())
        .addAll(attributes.textualHdrs());

    Optional<Artifact> bridgingHeader = attributes.bridgingHeader();
    if (bridgingHeader.isPresent()) {
      commandLine.addExecPath("-import-objc-header", bridgingHeader.get());
      inputHeaders.add(bridgingHeader.get());
    }

    // Import the Objective-C module map.
    // TODO(bazel-team): Find a way to import the module map directly, instead of the parent
    // directory?
    if (objcConfiguration.moduleMapsEnabled()) {
      CppModuleMap unextendedModuleMap = intermediateArtifacts.unextendedModuleMap();
      PathFragment moduleMapPath = unextendedModuleMap.getArtifact().getExecPath();

      commandLine.add("-I").add(moduleMapPath.getParentDirectory().getPathString());
      commandLine.add("-import-underlying-module");

      inputHeaders.add(unextendedModuleMap.getArtifact());
    }

    inputHeaders.addAll(Iterables.filter(
        objcProvider.get(MODULE_MAP).toList(),
        Predicates.not(Predicates.equalTo(intermediateArtifacts.moduleMap().getArtifact()))));

    Set<String> seenSwiftModulePaths = new HashSet<>();
    // For any dependency we have we need to make sure we are visible
    for (Artifact swiftModule : objcProvider.get(SWIFT_MODULE)) {
      String path = swiftModule.getExecPath().getParentDirectory().toString();
      if (!swiftModule.equals(intermediateArtifacts.swiftModule())) {
        inputHeaders.add(swiftModule);

        if (!seenSwiftModulePaths.contains(path)) {
          seenSwiftModulePaths.add(path);
          commandLine.add("-I").add(path);
        }
      }
    }

    commandLine.add(commonFrameworkFlags(objcProvider, appleConfiguration));

    Artifact swiftHeader = intermediateArtifacts.swiftHeader();

    ruleContext.registerAction(
        ObjcRuleClasses.spawnAppleEnvActionBuilder(
                appleConfiguration, appleConfiguration.getSingleArchPlatform())
            .setMnemonic("SwiftCompile")
            .setExecutable(xcrunwrapper(ruleContext))
            .setCommandLine(commandLine.build())
            .addInput(sourceFile)
            .addInputs(otherSwiftSources)
            .addInputs(inputHeaders.build())
            .addInputs(Iterables.filter(
                objcProvider.get(HEADER).toList(),
                Predicates.not(Predicates.equalTo(swiftHeader))))
            .addOutput(objFile)
            .addOutput(intermediateArtifacts.swiftModuleFile(sourceFile))
            .build(ruleContext));
  }

  /**
   * Merges multiple .partial_swiftmodule files together. Also produces a swift header that can be
   * used by Objective-C code.
   */
  private void registerSwiftModuleMergeAction(
      CompilationArtifacts compilationArtifacts,
      ObjcProvider objcProvider) {
    ImmutableList.Builder<Artifact> moduleFiles = new ImmutableList.Builder<>();
    for (Artifact src : compilationArtifacts.getSrcs()) {
      if (ObjcRuleClasses.SWIFT_SOURCES.matches(src.getFilename())) {
        moduleFiles.add(intermediateArtifacts.swiftModuleFile(src));
      }
    }

    CustomCommandLine.Builder commandLine = new CustomCommandLine.Builder()
        .add(SWIFT)
        .add("-frontend")
        .add("-emit-module")
        .add("-sdk").add(AppleToolchain.sdkDir())
        .add("-target").add(swiftTarget(appleConfiguration, objcConfiguration))
        .add(objcConfiguration.getSwiftCoptsForCompilationMode());

    if (objcConfiguration.generateDsym()) {
      commandLine.add("-g");
    }

    Artifact swiftHeader = intermediateArtifacts.swiftHeader();
    Artifact swiftModule = intermediateArtifacts.swiftModule();

    commandLine
        .add("-module-name").add(getModuleName())
        .add("-parse-as-library")
        .addExecPaths(moduleFiles.build())
        .addExecPath("-emit-module-path", swiftModule)
        .addExecPath("-emit-objc-header-path", swiftHeader)
        // The swift compiler will invoke clang itself when compiling module maps. This invocation
        // does not include the current working directory, causing cwd-relative imports to fail.
        // Including the current working directory to the header search paths ensures that these
        // relative imports will work.
        .add("-Xcc").add("-I.");


    // Using addExecPathBefore here adds unnecessary quotes around '-Xcc -I', which trips the
    // compiler. Using two add() calls generates a correctly formed command line.
    for (PathFragment directory : objcProvider.get(INCLUDE).toList()) {
      commandLine.add("-Xcc").add(String.format("-I%s", directory.toString()));
    }

    for (String option : getCompileRuleCopts()) {
      commandLine.add("-Xcc").add(option);
    }

    // Import the Objective-C module map.
    if (objcConfiguration.moduleMapsEnabled()) {
      PathFragment moduleMapPath = intermediateArtifacts.unextendedModuleMap().getArtifact().getExecPath().getParentDirectory();
      commandLine.add("-I").add(moduleMapPath.toString());
    }

    Set<String> seenSwiftModulePaths = new HashSet<>();
    // For any dependency we have we need to make sure we are visible
    for (Artifact depSwiftModule : objcProvider.get(SWIFT_MODULE).toList()) {
      String path = depSwiftModule.getExecPath().getParentDirectory().getPathString();

      if (!depSwiftModule.equals(swiftModule)) {
        moduleFiles.add(depSwiftModule);

        if (!seenSwiftModulePaths.contains(path)) {
          seenSwiftModulePaths.add(path);
          commandLine.add("-I").add(path);
        }
      }
    }

    commandLine.add(commonFrameworkFlags(objcProvider, appleConfiguration));

    Artifact moduleMap = intermediateArtifacts.moduleMap().getArtifact();

    ruleContext.registerAction(ObjcRuleClasses.spawnAppleEnvActionBuilder(
            appleConfiguration, appleConfiguration.getSingleArchPlatform())
        .setMnemonic("SwiftModuleMerge")
        .setExecutable(xcrunwrapper(ruleContext))
        .setCommandLine(commandLine.build())
        .addInputs(moduleFiles.build())
        .addInputs(Iterables.filter(
            objcProvider.get(HEADER).toList(),
            Predicates.not(Predicates.equalTo(swiftHeader))))
        .addInput(intermediateArtifacts.unextendedModuleMap().getArtifact())
        .addInputs(Iterables.filter(
            objcProvider.get(MODULE_MAP).toList(),
            Predicates.not(Predicates.equalTo(moduleMap))))
        .addOutput(swiftModule)
        .addOutput(swiftHeader)
        .build(ruleContext));
  }

  private void registerArchiveActions(List<Artifact> objFiles, Artifact archive) {
    Artifact objList = intermediateArtifacts.archiveObjList();
    registerObjFilelistAction(objFiles, objList);
    registerArchiveAction(objFiles, archive);
  }

  private void registerArchiveAction(
      Iterable<Artifact> objFiles,
      Artifact archive) {
    Artifact objList = intermediateArtifacts.archiveObjList();
    ruleContext.registerAction(ObjcRuleClasses.spawnAppleEnvActionBuilder(
            appleConfiguration, appleConfiguration.getSingleArchPlatform())
        .setMnemonic("ObjcLink")
        .setExecutable(libtool(ruleContext))
        .setCommandLine(new CustomCommandLine.Builder()
            .add("-static")
            .add("-filelist").add(objList.getExecPathString())
            .add("-arch_only").add(appleConfiguration.getSingleArchitecture())
            .add("-syslibroot").add(AppleToolchain.sdkDir())
            .add("-o").add(archive.getExecPathString())
            .build())
        .addInputs(objFiles)
        .addInput(objList)
        .addOutput(archive)
        .build(ruleContext));
  }

  /**
   * Registers an action that writes given set of object files to the given objList. This objList is
   * suitable to signal symbols to archive in a libtool archiving invocation.
   */
  CompilationSupport registerObjFilelistAction(Iterable<Artifact> objFiles, Artifact objList) {
    ImmutableSet<Artifact> dedupedObjFiles = ImmutableSet.copyOf(objFiles);
    CustomCommandLine.Builder objFilesToLinkParam = new CustomCommandLine.Builder();
    ImmutableList.Builder<Artifact> treeObjFiles = new ImmutableList.Builder<>();

    for (Artifact objFile : dedupedObjFiles) {
      // If the obj file is a tree artifact, we need to expand it into the contained individual
      // files properly.
      if (objFile.isTreeArtifact()) {
        treeObjFiles.add(objFile);
        objFilesToLinkParam.addExpandedTreeArtifactExecPaths(objFile);
      } else {
        objFilesToLinkParam.addPath(objFile.getExecPath());
      }
    }

    ruleContext.registerAction(new ParameterFileWriteAction(
        ruleContext.getActionOwner(),
        treeObjFiles.build(),
        objList,
        objFilesToLinkParam.build(),
        ParameterFile.ParameterFileType.UNQUOTED,
        ISO_8859_1));
    return this;
  }

  private CompilationSupport registerFullyLinkAction(Iterable<Artifact> inputArtifacts,
      Artifact outputArchive) {
    ruleContext.registerAction(ObjcRuleClasses.spawnAppleEnvActionBuilder(
            appleConfiguration, appleConfiguration.getSingleArchPlatform())
        .setMnemonic("ObjcLink")
        .setExecutable(libtool(ruleContext))
        .setCommandLine(new CustomCommandLine.Builder()
            .add("-static")
            .add("-arch_only").add(appleConfiguration.getSingleArchitecture())
            .add("-syslibroot").add(AppleToolchain.sdkDir())
            .add("-o").add(outputArchive.getExecPathString())
            .addExecPaths(inputArtifacts)
            .build())
        .addInputs(inputArtifacts)
        .addOutput(outputArchive)
        .build(ruleContext));
    return this;
  }

  /**
   * Registers an action to create an archive artifact by fully (statically) linking all
   * transitive dependencies of this rule.
   *
   * @param objcProvider provides all compiling and linking information to create this artifact
   * @param outputArchive the output artifact for this action
   */
  public CompilationSupport registerFullyLinkAction(ObjcProvider objcProvider,
      Artifact outputArchive) {
    ImmutableList<Artifact> inputArtifacts = ImmutableList.<Artifact>builder()
        .addAll(objcProvider.getObjcLibraries())
        .addAll(objcProvider.get(IMPORTED_LIBRARY))
        .addAll(objcProvider.getCcLibraries()).build();
    return registerFullyLinkAction(inputArtifacts, outputArchive);
  }

  /**
   * Registers an action to create an archive artifact by fully (statically) linking all
   * transitive dependencies of this rule *except* for dependencies given in {@code avoidsDeps}.
   *
   * @param objcProvider provides all compiling and linking information to create this artifact
   * @param outputArchive the output artifact for this action
   * @param avoidsDeps list of providers with dependencies that should not be linked into the
   *     output artifact
   */
  public CompilationSupport registerFullyLinkActionWithAvoids(ObjcProvider objcProvider,
      Artifact outputArchive, Iterable<ObjcProvider> avoidsDeps) {
    ImmutableSet.Builder<Artifact> avoidsDepsArtifacts = ImmutableSet.builder();

    for (ObjcProvider avoidsProvider : avoidsDeps) {
      avoidsDepsArtifacts.addAll(avoidsProvider.getObjcLibraries())
          .addAll(avoidsProvider.get(IMPORTED_LIBRARY))
          .addAll(avoidsProvider.getCcLibraries());
    }
    ImmutableList<Artifact> depsArtifacts = ImmutableList.<Artifact>builder()
        .addAll(objcProvider.getObjcLibraries())
        .addAll(objcProvider.get(IMPORTED_LIBRARY))
        .addAll(objcProvider.getCcLibraries()).build();

    Iterable<Artifact> inputArtifacts = Iterables.filter(depsArtifacts,
        Predicates.not(Predicates.in(avoidsDepsArtifacts.build())));
    return registerFullyLinkAction(inputArtifacts, outputArchive);
  }

  private NestedSet<Artifact> getGcovForObjectiveCIfNeeded() {
    if (ruleContext.getConfiguration().isCodeCoverageEnabled()
        && ruleContext.attributes().has(IosTest.OBJC_GCOV_ATTR, BuildType.LABEL)) {
      return PrerequisiteArtifacts.nestedSet(ruleContext, IosTest.OBJC_GCOV_ATTR, Mode.HOST);
    } else {
      return NestedSetBuilder.emptySet(Order.STABLE_ORDER);
    }
  }

  /**
   * Returns a provider that collects this target's instrumented sources as well as those of its
   * dependencies.
   *
   * @param common common information about this rule and its dependencies
   * @return an instrumented files provider
   */
  public InstrumentedFilesProvider getInstrumentedFilesProvider(ObjcCommon common) {
    ImmutableList.Builder<Artifact> oFiles = ImmutableList.builder();

    if (common.getCompilationArtifacts().isPresent()) {
      CompilationArtifacts artifacts = common.getCompilationArtifacts().get();
      for (Artifact artifact : Iterables.concat(artifacts.getSrcs(), artifacts.getNonArcSrcs())) {
        oFiles.add(intermediateArtifacts.objFile(artifact));
      }
    }

    return InstrumentedFilesCollector.collect(
        ruleContext,
        INSTRUMENTATION_SPEC,
        new ObjcCoverageMetadataCollector(),
        oFiles.build(),
        getGcovForObjectiveCIfNeeded(),
        // The COVERAGE_GCOV_PATH environment variable is added in TestSupport#getExtraProviders()
        NestedSetBuilder.<Pair<String, String>>emptySet(Order.COMPILE_ORDER),
        !TargetUtils.isTestRule(ruleContext.getTarget()));
  }

  /**
   * Registers any actions necessary to link this rule and its dependencies.
   *
   * <p>Dsym bundle is generated if
   * {@link ObjcConfiguration#generateDsym()} is set.
   *
   * <p>When Bazel flags {@code --compilation_mode=opt} and {@code --objc_enable_binary_stripping}
   * are specified, additional optimizations will be performed on the linked binary: all-symbol
   * stripping (using {@code /usr/bin/strip}) and dead-code stripping (using linker flags:
   * {@code -dead_strip} and {@code -no_dead_strip_inits_and_terms}).
   *
   * @param objcProvider common information about this rule's attributes and its dependencies
   * @param j2ObjcMappingFileProvider contains mapping files for j2objc transpilation
   * @param j2ObjcEntryClassProvider contains j2objc entry class information for dead code removal
   * @param extraLinkArgs any additional arguments to pass to the linker
   * @param extraLinkInputs any additional input artifacts to pass to the link action
   * @param dsymOutputType the file type of the dSYM bundle to be generated
   *
   * @return this compilation support
   */
  CompilationSupport registerLinkActions(
      ObjcProvider objcProvider,
      J2ObjcMappingFileProvider j2ObjcMappingFileProvider,
      J2ObjcEntryClassProvider j2ObjcEntryClassProvider,
      ExtraLinkArgs extraLinkArgs,
      Iterable<Artifact> extraLinkInputs,
      DsymOutputType dsymOutputType) {
    Optional<Artifact> dsymBundleZip;
    Optional<Artifact> linkmap;
    if (objcConfiguration.generateDsym()) {
      registerDsymActions(dsymOutputType);
      dsymBundleZip = Optional.of(intermediateArtifacts.tempDsymBundleZip(dsymOutputType));
    } else {
      dsymBundleZip = Optional.absent();
    }

    Iterable<Artifact> prunedJ2ObjcArchives = ImmutableList.<Artifact>of();
    if (stripJ2ObjcDeadCode(j2ObjcEntryClassProvider)) {
      registerJ2ObjcDeadCodeRemovalActions(objcProvider, j2ObjcMappingFileProvider,
          j2ObjcEntryClassProvider);
      prunedJ2ObjcArchives = j2objcPrunedLibraries(objcProvider);
    }

    if (objcConfiguration.generateLinkmap()) {
      linkmap = Optional.of(intermediateArtifacts.linkmap());
    } else {
      linkmap = Optional.absent();
    }

    registerLinkAction(
        objcProvider,
        extraLinkArgs,
        extraLinkInputs,
        dsymBundleZip,
        prunedJ2ObjcArchives,
        linkmap);
    return this;
  }

  private boolean stripJ2ObjcDeadCode(J2ObjcEntryClassProvider j2ObjcEntryClassProvider) {
    J2ObjcConfiguration j2objcConfiguration =
        buildConfiguration.getFragment(J2ObjcConfiguration.class);
    // Only perform J2ObjC dead code stripping if flag --j2objc_dead_code_removal is specified and
    // users have specified entry classes.
    return j2objcConfiguration.removeDeadCode()
        && !j2ObjcEntryClassProvider.getEntryClasses().isEmpty();
  }

  /**
   * Registers an action that will generate a clang module map for this target, using the hdrs
   * attribute of this rule.
   */
  CompilationSupport registerGenerateModuleMapActions(
      Optional<CompilationArtifacts> compilationArtifacts) {
    // TODO(bazel-team): Include textual headers in the module map when Xcode 6 support is
    // dropped.
    Iterable<Artifact> publicHeaders = attributes.hdrs();
    Iterable<Artifact> publicCcHeaders = attributes.ccHdrs();
    Iterable<Artifact> privateHeaders = ImmutableList.of();
    Optional<Artifact> swiftCompatibilityHeader = Optional.absent();

    if (compilationArtifacts.isPresent()) {
      CompilationArtifacts artifacts = compilationArtifacts.get();
      publicHeaders = Iterables.concat(publicHeaders, artifacts.getAdditionalHdrs());
      privateHeaders = Iterables.concat(privateHeaders, artifacts.getPrivateHdrs());
      swiftCompatibilityHeader = artifacts.getSwiftCompatabilityHeader();
    }

    registerGenerateModuleMapAction(intermediateArtifacts.moduleMap(), publicHeaders, publicCcHeaders,
        privateHeaders,
        swiftCompatibilityHeader,
        false);

    registerGenerateModuleMapAction(intermediateArtifacts.unextendedModuleMap(), publicHeaders,
        publicCcHeaders,
        privateHeaders,
        swiftCompatibilityHeader,
        true);

    return this;
  }

  /**
   * Registers an action that will generate a clang module map.
   *
   * @param moduleMap the module map to generate
   * @param publicHeaders the headers that should be directly accessible by dependers
   * @param publicCcHeaders the headers that should be directly accessible by dependers that are c++ or objc++
   * @param privateHeaders the headers that should only be directly accessible by this module
   * @param swiftCompatibilityHeader
   * @param isUnextendedSwift
   */
  private void registerGenerateModuleMapAction(
      CppModuleMap moduleMap, Iterable<Artifact> publicHeaders,
      Iterable<Artifact> publicCcHeaders, Iterable<Artifact> privateHeaders,
      Optional<Artifact> swiftCompatibilityHeader, boolean isUnextendedSwift) {
    publicHeaders = Iterables.filter(publicHeaders, MODULE_MAP_HEADER);
    privateHeaders = Iterables.filter(privateHeaders, MODULE_MAP_HEADER);
    ruleContext.registerAction(
        new CppModuleMapAction(
            ruleContext.getActionOwner(),
            moduleMap,
            //privateHeaders,
            ImmutableList.<Artifact>of(),
            publicHeaders,
            publicCcHeaders,
            attributes.moduleMapsForDirectDeps(),
            ImmutableList.<PathFragment>of(),
            swiftCompatibilityHeader,
            /*isUnextendedSwift=*/ isUnextendedSwift,
            /*compiledModule=*/ true,
            /*moduleMapHomeIsCwd=*/ false,
            /*generateSubModules=*/ true,
            /*externDependencies=*/ true));
  }

  private boolean isDynamicLib(CommandLine commandLine) {
    return Iterables.contains(commandLine.arguments(), "-dynamiclib");
  }

  private void registerLinkAction(
      ObjcProvider objcProvider,
      ExtraLinkArgs extraLinkArgs,
      Iterable<Artifact> extraLinkInputs,
      Optional<Artifact> dsymBundleZip,
      Iterable<Artifact> prunedJ2ObjcArchives,
      Optional<Artifact> linkmap) {
    // When compilation_mode=opt and objc_enable_binary_stripping are specified, the unstripped
    // binary containing debug symbols is generated by the linker, which also needs the debug
    // symbols for dead-code removal. The binary is also used to generate dSYM bundle if
    // --apple_generate_dsym is specified. A symbol strip action is later registered to strip
    // the symbol table from the unstripped binary.
    Artifact binaryToLink =
        objcConfiguration.shouldStripBinary()
            ? intermediateArtifacts.unstrippedSingleArchitectureBinary()
            : intermediateArtifacts.strippedSingleArchitectureBinary();

    ImmutableList<Artifact> objcLibraries = objcProvider.getObjcLibraries();
    ImmutableList<Artifact> ccLibraries = objcProvider.getCcLibraries();
    ImmutableList<Artifact> bazelBuiltLibraries = Iterables.isEmpty(prunedJ2ObjcArchives)
        ? objcLibraries : substituteJ2ObjcPrunedLibraries(objcProvider);
    CommandLine commandLine =
        linkCommandLine(
            extraLinkArgs,
            objcProvider,
            binaryToLink,
            dsymBundleZip,
            ccLibraries,
            bazelBuiltLibraries,
            linkmap);
    ruleContext.registerAction(
        ObjcRuleClasses.spawnAppleEnvActionBuilder(
                appleConfiguration, appleConfiguration.getSingleArchPlatform())
            .setMnemonic("ObjcLink")
            .setShellCommand(ImmutableList.of("/bin/bash", "-c"))
            .setCommandLine(new SingleArgCommandLine(commandLine))
            .addOutput(binaryToLink)
            .addOutputs(dsymBundleZip.asSet())
            .addOutputs(linkmap.asSet())
            .addInputs(bazelBuiltLibraries)
            .addTransitiveInputs(objcProvider.get(IMPORTED_LIBRARY))
            .addTransitiveInputs(objcProvider.get(STATIC_FRAMEWORK_FILE))
            .addTransitiveInputs(objcProvider.get(DYNAMIC_FRAMEWORK_FILE))
            .addInputs(ccLibraries)
            .addInputs(extraLinkInputs)
            .addInputs(prunedJ2ObjcArchives)
            .addInput(intermediateArtifacts.linkerObjList())
            .addInput(xcrunwrapper(ruleContext).getExecutable())
            .build(ruleContext));

    if (objcConfiguration.shouldStripBinary()) {
      final Iterable<String> stripArgs;
      if (TargetUtils.isTestRule(ruleContext.getRule())) {
        // For test targets, only debug symbols are stripped off, since /usr/bin/strip is not able
        // to strip off all symbols in XCTest bundle.
        stripArgs = ImmutableList.of("-S");
      } else if (isDynamicLib(commandLine)) {
        // For dynamic libs must pass "-x" to strip only local symbols.
        stripArgs = ImmutableList.of("-x");
      } else {
        stripArgs = ImmutableList.<String>of();
      }

      Artifact strippedBinary = intermediateArtifacts.strippedSingleArchitectureBinary();

      ruleContext.registerAction(
          ObjcRuleClasses.spawnAppleEnvActionBuilder(
                  appleConfiguration, appleConfiguration.getSingleArchPlatform())
              .setMnemonic("ObjcBinarySymbolStrip")
              .setExecutable(xcrunwrapper(ruleContext))
              .setCommandLine(symbolStripCommandLine(stripArgs, binaryToLink, strippedBinary))
              .addOutput(strippedBinary)
              .addInput(binaryToLink)
              .build(ruleContext));
    }
  }

  private ImmutableList<Artifact> j2objcPrunedLibraries(ObjcProvider objcProvider) {
    ImmutableList.Builder<Artifact> j2objcPrunedLibraryBuilder = ImmutableList.builder();
    for (Artifact j2objcLibrary : objcProvider.get(ObjcProvider.J2OBJC_LIBRARY)) {
      j2objcPrunedLibraryBuilder.add(intermediateArtifacts.j2objcPrunedArchive(j2objcLibrary));
    }
    return j2objcPrunedLibraryBuilder.build();
  }

  private static CommandLine symbolStripCommandLine(
      Iterable<String> extraFlags, Artifact unstrippedArtifact, Artifact strippedArtifact) {
    return CustomCommandLine.builder()
        .add(STRIP)
        .add(extraFlags)
        .addExecPath("-o", strippedArtifact)
        .addPath(unstrippedArtifact.getExecPath())
        .build();
  }

  /**
   * Returns a nested set of Bazel-built ObjC libraries with all unpruned J2ObjC libraries
   * substituted with pruned ones.
   */
  private ImmutableList<Artifact> substituteJ2ObjcPrunedLibraries(ObjcProvider objcProvider) {
    ImmutableList.Builder<Artifact> libraries = new ImmutableList.Builder<>();

    Set<Artifact> unprunedJ2ObjcLibs = objcProvider.get(ObjcProvider.J2OBJC_LIBRARY).toSet();
    for (Artifact library : objcProvider.getObjcLibraries()) {
      // If we match an unpruned J2ObjC library, add the pruned version of the J2ObjC static library
      // instead.
      if (unprunedJ2ObjcLibs.contains(library)) {
        libraries.add(intermediateArtifacts.j2objcPrunedArchive(library));
      } else {
        libraries.add(library);
      }
    }
    return libraries.build();
  }

  private CommandLine linkCommandLine(
      ExtraLinkArgs extraLinkArgs,
      ObjcProvider objcProvider,
      Artifact linkedBinary,
      Optional<Artifact> dsymBundleZip,
      Iterable<Artifact> ccLibraries,
      Iterable<Artifact> bazelBuiltLibraries,
      Optional<Artifact> linkmap) {
    Iterable<String> libraryNames = libraryNames(objcProvider);

    CustomCommandLine.Builder commandLine = CustomCommandLine.builder()
        .addPath(xcrunwrapper(ruleContext).getExecutable().getExecPath());
    if (objcProvider.is(USES_CPP)) {
      commandLine
          .add(CLANG_PLUSPLUS)
          .add("-stdlib=libc++")
          .add("-std=gnu++14");
    } else {
      commandLine.add(CLANG);
    }

    // Do not perform code stripping on tests because XCTest binary is linked not as an executable
    // but as a bundle without any entry point.
    boolean isTestTarget = TargetUtils.isTestRule(ruleContext.getRule());
    if (objcConfiguration.shouldStripBinary() && !isTestTarget) {
      commandLine.add("-dead_strip").add("-no_dead_strip_inits_and_terms");
    }

    Iterable<Artifact> ccLibrariesToForceLoad =
        Iterables.filter(ccLibraries, ALWAYS_LINKED_CC_LIBRARY);

    ImmutableSet<Artifact> forceLinkArtifacts = ImmutableSet.<Artifact>builder()
        .addAll(objcProvider.get(FORCE_LOAD_LIBRARY))
        .addAll(ccLibrariesToForceLoad).build();

    Artifact inputFileList = intermediateArtifacts.linkerObjList();
    Iterable<Artifact> objFiles =
        Iterables.concat(bazelBuiltLibraries, objcProvider.get(IMPORTED_LIBRARY), ccLibraries);
    // Clang loads archives specified in filelists and also specified as -force_load twice,
    // resulting in duplicate symbol errors unless they are deduped.
    objFiles = Iterables.filter(objFiles, Predicates.not(Predicates.in(forceLinkArtifacts)));

    registerObjFilelistAction(objFiles, inputFileList);

    if (objcConfiguration.shouldPrioritizeStaticLibs()) {
      commandLine.add("-filelist").add(inputFileList.getExecPathString());
    }

    // For any dependency we have we need to make sure we are visible
    for (Artifact swiftModule : objcProvider.get(SWIFT_MODULE).toList()) {
      commandLine.add("-Xlinker")
          .add("-add_ast_path")
          .add("-Xlinker")
          .add(swiftModule.getExecPath().getPathString());
    }

    commandLine
        .add(commonLinkAndCompileFlagsForClang(objcProvider, objcConfiguration, appleConfiguration))
        .add("-Xlinker")
        .add("-objc_abi_version")
        .add("-Xlinker")
        .add("2")
        // Set the rpath so that at runtime dylibs can be loaded from the bundle root's "Frameworks"
        // directory.
        .add("-Xlinker")
        .add("-rpath")
        .add("-Xlinker")
        .add("@executable_path/Frameworks")
        .add("-fobjc-link-runtime")
        .add(DEFAULT_LINKER_FLAGS)
        .addBeforeEach("-framework", frameworkNames(objcProvider))
        .addBeforeEach("-weak_framework", SdkFramework.names(objcProvider.get(WEAK_SDK_FRAMEWORK)))
        .addFormatEach("-l%s", libraryNames);

    if (!objcConfiguration.shouldPrioritizeStaticLibs()) {
      commandLine.add("-filelist").add(inputFileList.getExecPathString());
    }

    commandLine
        .addExecPath("-o", linkedBinary)
        .addBeforeEachExecPath("-force_load", forceLinkArtifacts)
        .add(extraLinkArgs)
        .add(objcProvider.get(ObjcProvider.LINKOPT));

    if (buildConfiguration.isCodeCoverageEnabled()) {
      if (buildConfiguration.isLLVMCoverageMapFormatEnabled()) {
        commandLine.add(LINKER_LLVM_COVERAGE_FLAGS);
      } else {
        commandLine.add(LINKER_COVERAGE_FLAGS);
      }
    }

    if (objcProvider.is(USES_SWIFT)) {
      // TODO: Add option in objc_binary or determine based on type of target
      //boolean useStaticSwiftLibs = objcConfiguration.shouldPrioritizeStaticLibs();

      commandLine
          .add("-L")
          .add(AppleToolchain.swiftLibDir(appleConfiguration.getSingleArchPlatform(),
              false));
    }

    for (String linkopt : attributes.linkopts()) {
      commandLine.add("-Wl," + linkopt);
    }

    if (linkmap.isPresent()) {
      commandLine
          .add("-Xlinker -map")
          .add("-Xlinker " + linkmap.get().getExecPath());
    }

    // Call to dsymutil for debug symbol generation must happen in the link action.
    // All debug symbol information is encoded in object files inside archive files. To generate
    // the debug symbol bundle, dsymutil will look inside the linked binary for the encoded
    // absolute paths to archive files, which are only valid in the link action.
    if (dsymBundleZip.isPresent()) {
      PathFragment dsymPath = FileSystemUtils.removeExtension(dsymBundleZip.get().getExecPath());
      commandLine
          .add("&&")
          .addPath(xcrunwrapper(ruleContext).getExecutable().getExecPath())
          .add(DSYMUTIL)
          .add(linkedBinary.getExecPathString())
          .add("-o " + dsymPath)
          .add("&& zipped_bundle=${PWD}/" + dsymBundleZip.get().getShellEscapedExecPathString())
          .add("&& cd " + dsymPath)
          .add("&& /usr/bin/zip -q -r \"${zipped_bundle}\" .");
    }

    return commandLine.build();
  }

  /**
   * Command line that converts its input's arg array to a single input.
   *
   * <p>Required as a hack to the link command line because that may contain two commands, which are
   * then passed to {@code /bin/bash -c}, and accordingly need to be a single argument.
   */
  @Immutable // if original is immutable
  private static final class SingleArgCommandLine extends CommandLine {
    private final CommandLine original;

    private SingleArgCommandLine(CommandLine original) {
      this.original = original;
    }

    @Override
    public Iterable<String> arguments() {
      return ImmutableList.of(Joiner.on(' ').join(original.arguments()));
    }
  }

  private Iterable<String> libraryNames(ObjcProvider objcProvider) {
    ImmutableList.Builder<String> args = new ImmutableList.Builder<>();
    for (String dylib : objcProvider.get(SDK_DYLIB)) {
      if (dylib.startsWith("lib")) {
        // remove lib prefix if it exists which is standard
        // for libraries (libxml.dylib -> -lxml).
        dylib = dylib.substring(3);
      }
      args.add(dylib);
    }
    return args.build();
  }

  /**
   * All framework names to pass to the linker using {@code -framework} flags. For a framework in
   * the directory foo/bar.framework, the name is "bar". Each framework is found without using the
   * full path by means of the framework search paths. The search paths are added by
   * {@link #commonLinkAndCompileFlagsForClang(ObjcProvider, ObjcConfiguration,
   * AppleConfiguration)}).
   *
   * <p>It's awful that we can't pass the full path to the framework and avoid framework search
   * paths, but this is imposed on us by clang. clang does not support passing the full path to the
   * framework, so Bazel cannot do it either.
   */
  private Iterable<String> frameworkNames(ObjcProvider provider) {
    List<String> names = new ArrayList<>();
    Iterables.addAll(names, SdkFramework.names(provider.get(SDK_FRAMEWORK)));
    for (PathFragment frameworkDir : provider.get(FRAMEWORK_DIR)) {
      String segment = frameworkDir.getBaseName();
      Preconditions.checkState(segment.endsWith(FRAMEWORK_SUFFIX),
          "expect %s to end with %s, but it does not", segment, FRAMEWORK_SUFFIX);
      names.add(segment.substring(0, segment.length() - FRAMEWORK_SUFFIX.length()));
    }
    return names;
  }

  private void registerJ2ObjcDeadCodeRemovalActions(ObjcProvider objcProvider,
      J2ObjcMappingFileProvider j2ObjcMappingFileProvider,
      J2ObjcEntryClassProvider j2ObjcEntryClassProvider) {
    NestedSet<String> entryClasses = j2ObjcEntryClassProvider.getEntryClasses();
    Artifact pruner = ruleContext.getPrerequisiteArtifact("$j2objc_dead_code_pruner", Mode.HOST);
    NestedSet<Artifact> j2ObjcDependencyMappingFiles =
        j2ObjcMappingFileProvider.getDependencyMappingFiles();
    NestedSet<Artifact> j2ObjcHeaderMappingFiles =
        j2ObjcMappingFileProvider.getHeaderMappingFiles();
    NestedSet<Artifact> j2ObjcArchiveSourceMappingFiles =
        j2ObjcMappingFileProvider.getArchiveSourceMappingFiles();

    for (Artifact j2objcArchive : objcProvider.get(ObjcProvider.J2OBJC_LIBRARY)) {
      PathFragment paramFilePath = FileSystemUtils.replaceExtension(
          j2objcArchive.getOwner().toPathFragment(), ".param.j2objc");
      Artifact paramFile = ruleContext.getUniqueDirectoryArtifact(
          "_j2objc_pruned",
          paramFilePath,
          ruleContext.getBinOrGenfilesDirectory());
      Artifact prunedJ2ObjcArchive = intermediateArtifacts.j2objcPrunedArchive(j2objcArchive);
      Artifact dummyArchive = Iterables.getOnlyElement(
          ruleContext.getPrerequisite("$dummy_lib", Mode.TARGET, ObjcProvider.class).get(LIBRARY));

      CustomCommandLine commandLine = CustomCommandLine.builder()
          .addExecPath("--input_archive", j2objcArchive)
          .addExecPath("--output_archive", prunedJ2ObjcArchive)
          .addExecPath("--dummy_archive", dummyArchive)
          .addExecPath("--xcrunwrapper", xcrunwrapper(ruleContext).getExecutable())
          .addJoinExecPaths("--dependency_mapping_files", ",", j2ObjcDependencyMappingFiles)
          .addJoinExecPaths("--header_mapping_files", ",", j2ObjcHeaderMappingFiles)
          .addJoinExecPaths("--archive_source_mapping_files", ",", j2ObjcArchiveSourceMappingFiles)
          .add("--entry_classes").add(Joiner.on(",").join(entryClasses))
          .build();

        ruleContext.registerAction(new ParameterFileWriteAction(
            ruleContext.getActionOwner(),
            paramFile,
            commandLine,
            ParameterFile.ParameterFileType.UNQUOTED, ISO_8859_1));
        ruleContext.registerAction(ObjcRuleClasses.spawnAppleEnvActionBuilder(
                appleConfiguration, appleConfiguration.getSingleArchPlatform())
            .setMnemonic("DummyPruner")
            .setExecutable(pruner)
            .addInput(dummyArchive)
            .addInput(pruner)
            .addInput(paramFile)
            .addInput(j2objcArchive)
            .addInput(xcrunwrapper(ruleContext).getExecutable())
            .addTransitiveInputs(j2ObjcDependencyMappingFiles)
            .addTransitiveInputs(j2ObjcHeaderMappingFiles)
            .addTransitiveInputs(j2ObjcArchiveSourceMappingFiles)
            .setCommandLine(CustomCommandLine.builder()
                .addPaths("@%s", paramFile.getExecPath())
                .build())
            .addOutput(prunedJ2ObjcArchive)
            .build(ruleContext));
    }
  }

  /**
   * Sets compilation-related Xcode project information on the given provider builder.
   *
   * @param common common information about this rule's attributes and its dependencies
   * @return this compilation support
   */
  CompilationSupport addXcodeSettings(Builder xcodeProviderBuilder, ObjcCommon common) {
    for (CompilationArtifacts artifacts : common.getCompilationArtifacts().asSet()) {
      xcodeProviderBuilder.setCompilationArtifacts(artifacts);
    }

    // The include directory options ("-I") are parsed out of copts. The include directories are
    // added as non-propagated header search paths local to the associated Xcode target.
    Iterable<String> copts = Iterables.concat(objcConfiguration.getCopts(), attributes.copts());
    Iterable<String> includeDirOptions = Iterables.filter(copts, INCLUDE_DIR_OPTION_IN_COPTS);
    Iterable<String> coptsWithoutIncludeDirs = Iterables.filter(
        copts, Predicates.not(INCLUDE_DIR_OPTION_IN_COPTS));
    ImmutableList.Builder<PathFragment> nonPropagatedHeaderSearchPaths =
        new ImmutableList.Builder<>();
    for (String includeDirOption : includeDirOptions) {
      nonPropagatedHeaderSearchPaths.add(new PathFragment(includeDirOption.substring(2)));
    }

    Iterable<String> swiftopts = Iterables.concat(objcConfiguration.getSwiftopts(), attributes.swiftopts());

    // We also need to add the -isystem directories from the CC header providers. ObjCommon
    // adds these to the objcProvider, so let's just get them from there.
    Iterable<PathFragment> includeSystemPaths = common.getObjcProvider().get(INCLUDE_SYSTEM);

    xcodeProviderBuilder
        .addHeaders(attributes.hdrs())
        .addHeaders(attributes.textualHdrs())
        .addHeaders(attributes.ccHdrs())
        .addUserHeaderSearchPaths(ObjcCommon.userHeaderSearchPaths(buildConfiguration))
        .addHeaderSearchPaths("$(WORKSPACE_ROOT)",
            attributes.headerSearchPaths(buildConfiguration.getGenfilesFragment()))
        .addHeaderSearchPaths("$(WORKSPACE_ROOT)", includeSystemPaths)
        .addHeaderSearchPaths("$(SDKROOT)/usr/include", attributes.sdkIncludes())
        .addNonPropagatedHeaderSearchPaths(
            "$(WORKSPACE_ROOT)", nonPropagatedHeaderSearchPaths.build())
        .addCompilationModeCopts(objcConfiguration.getCoptsForCompilationMode())
        .addCopts(coptsWithoutIncludeDirs)
        .addSwiftopts(swiftopts);

    if (objcConfiguration.moduleMapsEnabled()) {
      xcodeProviderBuilder.setModulemap(
          Optional.of(intermediateArtifacts.unextendedModuleMap().getArtifact()))
      .setEnableModules();
    }

    if (appleConfiguration.getSwiftVersion().isPresent()) {
      xcodeProviderBuilder.setSwiftVersion(appleConfiguration.getSwiftVersion());
    }

    return this;
  }

  /**
   * Validates compilation-related attributes on this rule.
   *
   * @return this compilation support
   * @throws RuleErrorException if there are attribute errors
   */
  CompilationSupport validateAttributes() throws RuleErrorException {
    for (PathFragment absoluteInclude :
        Iterables.filter(attributes.includes(), PathFragment.IS_ABSOLUTE)) {
      ruleContext.attributeError(
          "includes", String.format(ABSOLUTE_INCLUDES_PATH_FORMAT, absoluteInclude));
    }

    if (ruleContext.attributes().has("srcs", BuildType.LABEL_LIST)) {
      ImmutableSet<Artifact> hdrsSet = ImmutableSet.copyOf(Iterables.concat(attributes.hdrs(), attributes.ccHdrs()));
      ImmutableSet<Artifact> srcsSet =
          ImmutableSet.copyOf(ruleContext.getPrerequisiteArtifacts("srcs", Mode.TARGET).list());

      // Check for overlap between srcs and hdrs.
      for (Artifact header : Sets.intersection(hdrsSet, srcsSet)) {
        String path = header.getRootRelativePath().toString();
        ruleContext.attributeWarning(
            "srcs", String.format(FILE_IN_SRCS_AND_HDRS_WARNING_FORMAT, path));
      }

      // Check for overlap between srcs and non_arc_srcs.
      ImmutableSet<Artifact> nonArcSrcsSet =
          ImmutableSet.copyOf(
              ruleContext.getPrerequisiteArtifacts("non_arc_srcs", Mode.TARGET).list());
      for (Artifact conflict : Sets.intersection(nonArcSrcsSet, srcsSet)) {
        String path = conflict.getRootRelativePath().toString();
        ruleContext.attributeError(
            "srcs", String.format(FILE_IN_SRCS_AND_NON_ARC_SRCS_ERROR_FORMAT, path));
      }

      if (appleConfiguration.disableNativeSwiftRules()) {
        for (Artifact src : srcsSet) {
          if (SWIFT_SOURCES.apply(src.getFilename())) {
            ruleContext.attributeError("srcs", "Swift is not supported in native rules.");
          }
        }
      }
    }

    ruleContext.assertNoErrors();
    return this;
  }

  private CompilationSupport registerDsymActions(DsymOutputType dsymOutputType) {
    Artifact tempDsymBundleZip = intermediateArtifacts.tempDsymBundleZip(dsymOutputType);
    Artifact linkedBinary =
        objcConfiguration.shouldStripBinary()
            ? intermediateArtifacts.unstrippedSingleArchitectureBinary()
            : intermediateArtifacts.strippedSingleArchitectureBinary();
    Artifact debugSymbolFile = intermediateArtifacts.dsymSymbol(dsymOutputType);
    Artifact dsymPlist = intermediateArtifacts.dsymPlist(dsymOutputType);

    PathFragment dsymOutputDir = removeSuffix(tempDsymBundleZip.getExecPath(), ".temp.zip");
    PathFragment dsymPlistZipEntry = dsymPlist.getExecPath().relativeTo(dsymOutputDir);
    PathFragment debugSymbolFileZipEntry =
        debugSymbolFile
            .getExecPath()
            .replaceName(linkedBinary.getFilename())
            .relativeTo(dsymOutputDir);

    StringBuilder unzipDsymCommand =
        new StringBuilder()
            .append(
                String.format(
                    "unzip -p %s %s > %s",
                    tempDsymBundleZip.getExecPathString(),
                    dsymPlistZipEntry,
                    dsymPlist.getExecPathString()))
            .append(
                String.format(
                    " && unzip -p %s %s > %s",
                    tempDsymBundleZip.getExecPathString(),
                    debugSymbolFileZipEntry,
                    debugSymbolFile.getExecPathString()));

    ruleContext.registerAction(
        new SpawnAction.Builder()
            .setMnemonic("UnzipDsym")
            .setShellCommand(unzipDsymCommand.toString())
            .addInput(tempDsymBundleZip)
            .addOutput(dsymPlist)
            .addOutput(debugSymbolFile)
            .build(ruleContext));

    return this;
  }

  private PathFragment removeSuffix(PathFragment path, String suffix) {
    String name = path.getBaseName();
    Preconditions.checkArgument(
        name.endsWith(suffix), "expect %s to end with %s, but it does not", name, suffix);
    return path.replaceName(name.substring(0, name.length() - suffix.length()));
  }

  /**
   * Returns the name of Swift module for this target.
   */
  private String getModuleName() {
    // If we have module maps support, we need to use the generated module name, this way
    // clang can properly load objc part of the module via -import-underlying-module command.
    if (objcConfiguration.moduleMapsEnabled()) {
      return intermediateArtifacts.moduleMap().getName();
    }

    if (ruleContext.attributes().has("clang_module_name", Type.STRING)) {
      return ruleContext.attributes().get("clang_module_name", Type.STRING);
    }

    // Otherwise, just use target name, it doesn't matter.
    return ruleContext.getRule().getName();
  }

  /**
   * Collector that, given a list of output artifacts, finds and registers coverage notes metadata
   * for any compilation action.
   */
  private static class ObjcCoverageMetadataCollector extends LocalMetadataCollector {

    @Override
    public void collectMetadataArtifacts(
        Iterable<Artifact> artifacts,
        AnalysisEnvironment analysisEnvironment,
        NestedSetBuilder<Artifact> metadataFilesBuilder) {
      for (Artifact artifact : artifacts) {
        ActionAnalysisMetadata action = analysisEnvironment.getLocalGeneratingAction(artifact);
        if (action.getMnemonic().equals("ObjcCompile")) {
          addOutputs(metadataFilesBuilder, action, ObjcRuleClasses.COVERAGE_NOTES);
        }
      }
    }
  }

  private static Iterable<PathFragment> uniqueParentDirectories(Iterable<PathFragment> paths) {
    ImmutableSet.Builder<PathFragment> parents = new ImmutableSet.Builder<>();
    for (PathFragment path : paths) {
      parents.add(path.getParentDirectory());
    }
    return parents.build();
  }

  /**
   * Returns the target string for swift compiler. For example, "x86_64-apple-ios8.2"
   */
  @VisibleForTesting
  static String swiftTarget(AppleConfiguration configuration, ObjcConfiguration objcConfiguration) {
    // TODO(bazel-team): Assert the configuration is for an apple platform, or support
    // other platform types.
    Platform platform = configuration.getSingleArchPlatform();
    return configuration.getSingleArchitecture() + "-apple-" + platform.getType().toString() + configuration
        .getMinimumOsForPlatformType(platform.getType());
  }

  /**
   * Returns a list of clang flags used for all link and compile actions executed through clang.
   */
  private List<String> commonLinkAndCompileFlagsForClang(
      ObjcProvider provider, ObjcConfiguration objcConfiguration,
      AppleConfiguration appleConfiguration) {
    ImmutableList.Builder<String> builder = new ImmutableList.Builder<>();
    Platform platform = appleConfiguration.getSingleArchPlatform();
    switch (platform) {
      case IOS_SIMULATOR:
        builder.add("-mios-simulator-version-min="
            + appleConfiguration.getMinimumOsForPlatformType(platform.getType()));
        break;
      case IOS_DEVICE:
        builder.add("-miphoneos-version-min="
            + appleConfiguration.getMinimumOsForPlatformType(platform.getType()));
        break;
      case WATCHOS_SIMULATOR:
        // TODO(bazel-team): Use the value from --watchos-minimum-os instead of tying to the SDK
        // version.
        builder.add("-mwatchos-simulator-version-min="
            + appleConfiguration.getSdkVersionForPlatform(platform));
        break;
      case WATCHOS_DEVICE:
        // TODO(bazel-team): Use the value from --watchos-minimum-os instead of tying to the SDK
        // version.
        builder.add("-mwatchos-version-min="
            + appleConfiguration.getSdkVersionForPlatform(platform));
        break;
      case TVOS_SIMULATOR:
        builder.add("-mtvos-simulator-version-min="
            + appleConfiguration.getMinimumOsForPlatformType(platform.getType()));
        break;
      case MACOS_X:
        builder.add("-mmacosx-version-min="
            + appleConfiguration.getMinimumOsForPlatformType(platform.getType()));
        break;
      case TVOS_DEVICE:
        builder.add("-mtvos-version-min="
            + appleConfiguration.getMinimumOsForPlatformType(platform.getType()));
        break;
      default:
        throw new IllegalArgumentException("Unhandled platform " + platform);
    }

    if (objcConfiguration.generateDsym()) {
      builder.add("-g");
    }

    return builder
        .add("-arch", appleConfiguration.getSingleArchitecture())
        .add("-isysroot", AppleToolchain.sdkDir())
        // TODO(bazel-team): Pass framework search paths to Xcodegen.
        .addAll(commonFrameworkFlags(provider, appleConfiguration))
        .build();
  }

  /**
   * Returns a list of framework search path flags for clang/swift actions.
   */
  static Iterable<String> commonFrameworkFlags(
      ObjcProvider provider, AppleConfiguration appleConfiguration) {
    return Interspersing.beforeEach("-F",
        commonFrameworkNames(provider, appleConfiguration));
  }

  /**
   * Returns a list of frameworks for clang/swift actions.
   */
  static Iterable<String> commonFrameworkNames(
      ObjcProvider provider, AppleConfiguration appleConfiguration) {
    Platform platform = appleConfiguration.getSingleArchPlatform();

    ImmutableList.Builder<String> frameworkNames = new ImmutableList.Builder<String>()
        .add(AppleToolchain.sdkFrameworkDir(platform, appleConfiguration));
    if (platform.getType() == PlatformType.IOS) {
        // As of sdk8.1, XCTest is in a base Framework dir
      frameworkNames.add(AppleToolchain.platformDeveloperFrameworkDir(appleConfiguration));
    }
    return frameworkNames
        // Add custom (non-SDK) framework search paths. For each framework foo/bar.framework,
        // include "foo" as a search path.
        .addAll(PathFragment.safePathStrings(uniqueParentDirectories(provider.get(FRAMEWORK_DIR))))
        .addAll(PathFragment.safePathStrings(
            uniqueParentDirectories(provider.get(FRAMEWORK_SEARCH_PATH_ONLY))))
        .build();
  }

  private static Iterable<String> compileFlagsForClang(AppleConfiguration configuration) {
    return Iterables.concat(
        AppleToolchain.DEFAULT_WARNINGS.values(),
        platformSpecificCompileFlagsForClang(configuration),
        configuration.getBitcodeMode().getCompilerFlags(),
        DEFAULT_COMPILER_FLAGS
    );
  }

  private static List<String> platformSpecificCompileFlagsForClang(
      AppleConfiguration configuration) {
    switch (configuration.getSingleArchPlatform()) {
      case MACOS_X:
        return ImmutableList.of();
      case IOS_DEVICE:
      case WATCHOS_DEVICE:
      case TVOS_DEVICE:
        return ImmutableList.of();
      case IOS_SIMULATOR:
      case WATCHOS_SIMULATOR:
      case TVOS_SIMULATOR:
        return SIMULATOR_COMPILE_FLAGS;
      default:
        throw new AssertionError();
    }
  }
}
