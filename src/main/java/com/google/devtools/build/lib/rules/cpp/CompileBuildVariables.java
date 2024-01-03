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
package com.google.devtools.build.lib.rules.cpp;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.RuleErrorConsumer;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.CoreOptions;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.FeatureConfiguration;
import com.google.devtools.build.lib.rules.cpp.CcToolchainVariables.VariablesExtension;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.List;
import java.util.Map;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.StarlarkThread;

/** Enum covering all build variables we create for all various {@link CppCompileAction}. */
public enum CompileBuildVariables {
  /** Variable for the path to the source file being compiled. */
  SOURCE_FILE("source_file"),
  /**
   * Variable for all flags coming from copt rule attribute, and from --copt, --cxxopt, or
   * --conlyopt options.
   */
  USER_COMPILE_FLAGS("user_compile_flags"),
  /** Variable for the path to the compilation output file. */
  OUTPUT_FILE("output_file"),
  /** Variable for the dependency file path */
  DEPENDENCY_FILE("dependency_file"),
  /** Variable for the serialized diagnostics file path */
  SERIALIZED_DIAGNOSTICS_FILE("serialized_diagnostics_file"),
  /** Variable for the module file name. */
  MODULE_NAME("module_name"),
  /**
   * Variable for the collection of include paths.
   *
   * @see CcCompilationContext#getIncludeDirs().
   */
  INCLUDE_PATHS("include_paths"),
  /**
   * Variable for the collection of quote include paths.
   *
   * @see CcCompilationContext#getQuoteIncludeDirs().
   */
  QUOTE_INCLUDE_PATHS("quote_include_paths"),
  /**
   * Variable for the collection of system include paths.
   *
   * @see CcCompilationContext#getSystemIncludeDirs().
   */
  SYSTEM_INCLUDE_PATHS("system_include_paths"),
  /**
   * Variable for the collection of external include paths.
   *
   * @see CcCompilationContext#getExternalIncludeDirs().
   */
  EXTERNAL_INCLUDE_PATHS("external_include_paths"),

  /**
   * Variable for the collection of framework include paths.
   *
   * @see CcCompilationContext#getFrameworkIncludeDirs().
   */
  FRAMEWORK_PATHS("framework_include_paths"),
  /** Variable for the module map file name. */
  MODULE_MAP_FILE("module_map_file"),
  /** Variable for the dependent module map file name. */
  DEPENDENT_MODULE_MAP_FILES("dependent_module_map_files"),
  /** Variable for the collection of module files. */
  MODULE_FILES("module_files"),
  /** Variable for the collection of macros defined for preprocessor. */
  PREPROCESSOR_DEFINES("preprocessor_defines"),
  /** Variable for the gcov coverage file path. */
  GCOV_GCNO_FILE("gcov_gcno_file"),
  /**
   * Variable for the minimized LTO indexing bitcode file, used by the LTO indexing action. This
   * file was generated by CppCompile actions. For efficiency, it contains minimal information that
   * is required by the LTO indexing action.
   */
  LTO_INDEXING_BITCODE_FILE("lto_indexing_bitcode_file"),
  /**
   * Variable for the LTO index file, used by the LTO backend action. This file was generated by the
   * LTO indexing action.
   */
  THINLTO_INDEX("thinlto_index"),
  /** Variable for the bitcode file that is input to LTO backend. */
  THINLTO_INPUT_BITCODE_FILE("thinlto_input_bitcode_file"),
  /** Variable for the object file that is output by LTO backend. */
  THINLTO_OUTPUT_OBJECT_FILE("thinlto_output_object_file"),
  /** Variable marking fission is used. */
  IS_USING_FISSION("is_using_fission"),
  /** Variable for the per object debug info file. */
  PER_OBJECT_DEBUG_INFO_FILE("per_object_debug_info_file"),
  /** Variable present when the output is compiled as position independent. */
  PIC("pic"),
  /** Variable marking that we are generating preprocessed sources (from --save_temps). */
  OUTPUT_PREPROCESS_FILE("output_preprocess_file"),
  /** Variable marking that we are generating assembly source (from --save_temps). */
  OUTPUT_ASSEMBLY_FILE("output_assembly_file"),
  /** Path to the fdo instrument artifact */
  FDO_INSTRUMENT_PATH("fdo_instrument_path"),
  /** Path to the fdo profile artifact */
  FDO_PROFILE_PATH("fdo_profile_path"),
  /** Path to the context sensitive fdo instrument artifact */
  CS_FDO_INSTRUMENT_PATH("cs_fdo_instrument_path"),
  /** Path to the cache prefetch profile artifact */
  FDO_PREFETCH_HINTS_PATH("fdo_prefetch_hints_path"),
  /** Path to the Propeller Optimize compiler profile artifact */
  PROPELLER_OPTIMIZE_CC_PATH("propeller_optimize_cc_path"),
  /** Path to the Propeller Optimize linker profile artifact */
  PROPELLER_OPTIMIZE_LD_PATH("propeller_optimize_ld_path"),
  /** Path to the memprof profile artifact */
  MEMPROF_PROFILE_PATH("memprof_profile_path"),
  /** Variable for includes that compiler needs to include into sources. */
  INCLUDES("includes");

  private final String variableName;

  CompileBuildVariables(String variableName) {
    this.variableName = variableName;
  }

  public static CcToolchainVariables setupVariablesOrReportRuleError(
      StarlarkThread thread,
      RuleErrorConsumer ruleErrorConsumer,
      FeatureConfiguration featureConfiguration,
      CcToolchainProvider ccToolchainProvider,
      BuildOptions buildOptions,
      CppConfiguration cppConfiguration,
      String sourceFile,
      String outputFile,
      String gcnoFile,
      boolean isUsingFission,
      String dwoFile,
      String ltoIndexingFile,
      ImmutableList<String> includes,
      Iterable<String> userCompileFlags,
      CppModuleMap cppModuleMap,
      boolean usePic,
      String fdoStamp,
      String dotdFileExecPath,
      String diagnosticsFileExecPath,
      ImmutableList<VariablesExtension> variablesExtensions,
      ImmutableMap<String, String> additionalBuildVariables,
      Iterable<Artifact> directModuleMaps,
      NestedSet<PathFragment> includeDirs,
      NestedSet<PathFragment> quoteIncludeDirs,
      NestedSet<PathFragment> systemIncludeDirs,
      NestedSet<PathFragment> frameworkIncludeDirs,
      Iterable<String> defines,
      Iterable<String> localDefines)
      throws InterruptedException {
    try {
      if (usePic
          && !featureConfiguration.isEnabled(CppRuleClasses.PIC)
          && !featureConfiguration.isEnabled(CppRuleClasses.SUPPORTS_PIC)) {
        throw new EvalException(CcCommon.PIC_CONFIGURATION_ERROR);
      }
      return setupVariables(
          featureConfiguration,
          CcToolchainProvider.getBuildVars(
              ccToolchainProvider,
              thread,
              cppConfiguration,
              buildOptions,
              buildOptions.get(CoreOptions.class).cpu,
              ccToolchainProvider.getBuildVarsFunc()),
          sourceFile,
          outputFile,
          gcnoFile,
          isUsingFission,
          dwoFile,
          ltoIndexingFile,
          /* thinLtoIndex= */ null,
          /* thinLtoInputBitcodeFile= */ null,
          /* thinLtoOutputObjectFile= */ null,
          includes,
          userCompileFlags,
          cppModuleMap,
          usePic,
          fdoStamp,
          dotdFileExecPath,
          diagnosticsFileExecPath,
          variablesExtensions,
          additionalBuildVariables,
          directModuleMaps,
          getSafePathStrings(includeDirs),
          getSafePathStrings(quoteIncludeDirs),
          getSafePathStrings(systemIncludeDirs),
          getSafePathStrings(frameworkIncludeDirs),
          defines,
          localDefines);
    } catch (EvalException e) {
      ruleErrorConsumer.ruleError(e.getMessage());
      return CcToolchainVariables.EMPTY;
    }
  }

  public static CcToolchainVariables setupVariablesOrThrowEvalException(
      StarlarkThread thread,
      FeatureConfiguration featureConfiguration,
      CcToolchainProvider ccToolchainProvider,
      BuildOptions buildOptions,
      CppConfiguration cppConfiguration,
      String sourceFile,
      String outputFile,
      String gcnoFile,
      boolean isUsingFission,
      String dwoFile,
      String ltoIndexingFile,
      String thinLtoIndex,
      String thinLtoInputBitcodeFile,
      String thinLtoOutputObjectFile,
      ImmutableList<String> includes,
      Iterable<String> userCompileFlags,
      CppModuleMap cppModuleMap,
      boolean usePic,
      String fdoStamp,
      String dotdFileExecPath,
      String diagnosticsFileExecPath,
      ImmutableList<VariablesExtension> variablesExtensions,
      ImmutableMap<String, String> additionalBuildVariables,
      Iterable<Artifact> directModuleMaps,
      NestedSet<String> includeDirs,
      NestedSet<String> quoteIncludeDirs,
      NestedSet<String> systemIncludeDirs,
      NestedSet<String> frameworkIncludeDirs,
      Iterable<String> defines,
      Iterable<String> localDefines)
      throws EvalException, InterruptedException {
    if (usePic
        && !featureConfiguration.isEnabled(CppRuleClasses.PIC)
        && !featureConfiguration.isEnabled(CppRuleClasses.SUPPORTS_PIC)) {
      throw new EvalException(CcCommon.PIC_CONFIGURATION_ERROR);
    }
    return setupVariables(
        featureConfiguration,
        CcToolchainProvider.getBuildVars(
            ccToolchainProvider,
            thread,
            cppConfiguration,
            buildOptions,
            buildOptions.get(CoreOptions.class).cpu,
            ccToolchainProvider.getBuildVarsFunc()),
        sourceFile,
        outputFile,
        gcnoFile,
        isUsingFission,
        dwoFile,
        ltoIndexingFile,
        thinLtoIndex,
        thinLtoInputBitcodeFile,
        thinLtoOutputObjectFile,
        includes,
        userCompileFlags,
        cppModuleMap,
        usePic,
        fdoStamp,
        dotdFileExecPath,
        diagnosticsFileExecPath,
        variablesExtensions,
        additionalBuildVariables,
        directModuleMaps,
        includeDirs,
        quoteIncludeDirs,
        systemIncludeDirs,
        frameworkIncludeDirs,
        defines,
        localDefines);
  }

  private static CcToolchainVariables setupVariables(
      FeatureConfiguration featureConfiguration,
      CcToolchainVariables parent,
      String sourceFile,
      String outputFile,
      String gcnoFile,
      boolean isUsingFission,
      String dwoFile,
      String ltoIndexingFile,
      String thinLtoIndex,
      String thinLtoInputBitcodeFile,
      String thinLtoOutputObjectFile,
      ImmutableList<String> includes,
      Iterable<String> userCompileFlags,
      CppModuleMap cppModuleMap,
      boolean usePic,
      String fdoStamp,
      String dotdFileExecPath,
      String diagnosticsFileExecPath,
      ImmutableList<VariablesExtension> variablesExtensions,
      ImmutableMap<String, String> additionalBuildVariables,
      Iterable<Artifact> directModuleMaps,
      NestedSet<String> includeDirs,
      NestedSet<String> quoteIncludeDirs,
      NestedSet<String> systemIncludeDirs,
      NestedSet<String> frameworkIncludeDirs,
      Iterable<String> defines,
      Iterable<String> localDefines) {
    CcToolchainVariables.Builder buildVariables = CcToolchainVariables.builder(parent);
    setupCommonVariablesInternal(
        buildVariables,
        featureConfiguration,
        includes,
        cppModuleMap,
        fdoStamp,
        variablesExtensions,
        additionalBuildVariables,
        directModuleMaps,
        includeDirs,
        quoteIncludeDirs,
        systemIncludeDirs,
        frameworkIncludeDirs,
        defines,
        localDefines);
    setupSpecificVariables(
        buildVariables,
        sourceFile,
        outputFile,
        gcnoFile,
        dwoFile,
        isUsingFission,
        ltoIndexingFile,
        thinLtoIndex,
        thinLtoInputBitcodeFile,
        thinLtoOutputObjectFile,
        userCompileFlags,
        dotdFileExecPath,
        diagnosticsFileExecPath,
        usePic,
        ImmutableList.of(),
        ImmutableMap.of());
    return buildVariables.build();
  }

  public static void setupSpecificVariables(
      CcToolchainVariables.Builder buildVariables,
      String sourceFile,
      String outputFile,
      String gcnoFile,
      String dwoFile,
      boolean isUsingFission,
      String ltoIndexingFile,
      String thinLtoIndex,
      String thinLtoInputBitcodeFile,
      String thinLtoOutputObjectFile,
      Iterable<String> userCompileFlags,
      String dotdFileExecPath,
      String diagnosticsFileExecPath,
      boolean usePic,
      ImmutableList<PathFragment> externalIncludeDirs,
      Map<String, String> additionalBuildVariables) {
    buildVariables.addStringSequenceVariable(
        USER_COMPILE_FLAGS.getVariableName(), userCompileFlags);

    if (sourceFile != null) {
      buildVariables.addStringVariable(SOURCE_FILE.getVariableName(), sourceFile);
    }

    if (outputFile != null) {
      buildVariables.addStringVariable(OUTPUT_FILE.getVariableName(), outputFile);
    }

    // Set dependency_file to enable <object>.d file generation.
    if (dotdFileExecPath != null) {
      buildVariables.addStringVariable(DEPENDENCY_FILE.getVariableName(), dotdFileExecPath);
    }

    // Set diagnostics_file to enable <object>.dia file generation.
    if (diagnosticsFileExecPath != null) {
      buildVariables.addStringVariable(
          SERIALIZED_DIAGNOSTICS_FILE.getVariableName(), diagnosticsFileExecPath);
    }

    if (gcnoFile != null) {
      buildVariables.addStringVariable(GCOV_GCNO_FILE.getVariableName(), gcnoFile);
    }

    if (dwoFile != null) {
      buildVariables.addStringVariable(PER_OBJECT_DEBUG_INFO_FILE.getVariableName(), dwoFile);
    }

    if (isUsingFission) {
      buildVariables.addStringVariable(IS_USING_FISSION.getVariableName(), "");
    }

    if (ltoIndexingFile != null) {
      buildVariables.addStringVariable(
          LTO_INDEXING_BITCODE_FILE.getVariableName(), ltoIndexingFile);
    }
    if (thinLtoIndex != null) {
      buildVariables.addStringVariable(THINLTO_INDEX.getVariableName(), thinLtoIndex);
    }
    if (thinLtoInputBitcodeFile != null) {
      buildVariables.addStringVariable(
          THINLTO_INPUT_BITCODE_FILE.getVariableName(), thinLtoInputBitcodeFile);
    }
    if (thinLtoOutputObjectFile != null) {
      buildVariables.addStringVariable(
          THINLTO_OUTPUT_OBJECT_FILE.getVariableName(), thinLtoOutputObjectFile);
    }

    if (usePic) {
      buildVariables.addStringVariable(PIC.getVariableName(), "");
    }

    if (!externalIncludeDirs.isEmpty()) {
      buildVariables.addStringSequenceVariable(
          EXTERNAL_INCLUDE_PATHS.getVariableName(),
          Iterables.transform(externalIncludeDirs, PathFragment::getSafePathString));
    }

    buildVariables.addAllStringVariables(additionalBuildVariables);
  }

  public static void setupCommonVariables(
      CcToolchainVariables.Builder buildVariables,
      FeatureConfiguration featureConfiguration,
      List<String> includes,
      CppModuleMap cppModuleMap,
      String fdoStamp,
      List<VariablesExtension> variablesExtensions,
      Map<String, String> additionalBuildVariables,
      Iterable<Artifact> directModuleMaps,
      List<PathFragment> includeDirs,
      List<PathFragment> quoteIncludeDirs,
      List<PathFragment> systemIncludeDirs,
      List<PathFragment> frameworkIncludeDirs,
      Iterable<String> defines,
      Iterable<String> localDefines) {
    setupCommonVariablesInternal(
        buildVariables,
        featureConfiguration,
        includes,
        cppModuleMap,
        fdoStamp,
        variablesExtensions,
        additionalBuildVariables,
        directModuleMaps,
        getSafePathStrings(includeDirs),
        getSafePathStrings(quoteIncludeDirs),
        getSafePathStrings(systemIncludeDirs),
        getSafePathStrings(frameworkIncludeDirs),
        defines,
        localDefines);
  }

  private static void setupCommonVariablesInternal(
      CcToolchainVariables.Builder buildVariables,
      FeatureConfiguration featureConfiguration,
      List<String> includes,
      CppModuleMap cppModuleMap,
      String fdoStamp,
      List<VariablesExtension> variablesExtensions,
      Map<String, String> additionalBuildVariables,
      Iterable<Artifact> directModuleMaps,
      NestedSet<String> includeDirs,
      NestedSet<String> quoteIncludeDirs,
      NestedSet<String> systemIncludeDirs,
      NestedSet<String> frameworkIncludeDirs,
      Iterable<String> defines,
      Iterable<String> localDefines) {
    Preconditions.checkNotNull(directModuleMaps);
    Preconditions.checkNotNull(includeDirs);
    Preconditions.checkNotNull(quoteIncludeDirs);
    Preconditions.checkNotNull(systemIncludeDirs);
    Preconditions.checkNotNull(frameworkIncludeDirs);
    Preconditions.checkNotNull(defines);
    Preconditions.checkNotNull(localDefines);

    if (featureConfiguration.isEnabled(CppRuleClasses.MODULE_MAPS) && cppModuleMap != null) {
      buildVariables.addStringVariable(MODULE_NAME.getVariableName(), cppModuleMap.getName());
      buildVariables.addStringVariable(
          MODULE_MAP_FILE.getVariableName(), cppModuleMap.getArtifact().getExecPathString());
      buildVariables.addStringSequenceVariable(
          DEPENDENT_MODULE_MAP_FILES.getVariableName(),
          Iterables.transform(directModuleMaps, Artifact::getExecPathString));
    }
    if (featureConfiguration.isEnabled(CppRuleClasses.USE_HEADER_MODULES)) {
      // Module inputs will be set later when the action is executed.
      buildVariables.addStringSequenceVariable(MODULE_FILES.getVariableName(), ImmutableSet.of());
    }
    buildVariables.addStringSequenceVariable(INCLUDE_PATHS.getVariableName(), includeDirs);
    buildVariables.addStringSequenceVariable(
        QUOTE_INCLUDE_PATHS.getVariableName(), quoteIncludeDirs);
    buildVariables.addStringSequenceVariable(
        SYSTEM_INCLUDE_PATHS.getVariableName(), systemIncludeDirs);

    if (!includes.isEmpty()) {
      buildVariables.addStringSequenceVariable(INCLUDES.getVariableName(), includes);
    }

    buildVariables.addStringSequenceVariable(
        FRAMEWORK_PATHS.getVariableName(), frameworkIncludeDirs);

    Iterable<String> allDefines;
    if (fdoStamp != null) {
      // Stamp FDO builds with FDO subtype string
      allDefines =
          Iterables.concat(
              defines,
              localDefines,
              ImmutableList.of(CppConfiguration.FDO_STAMP_MACRO + "=\"" + fdoStamp + "\""));
    } else {
      allDefines = Iterables.concat(defines, localDefines);
    }

    buildVariables.addStringSequenceVariable(PREPROCESSOR_DEFINES.getVariableName(), allDefines);

    buildVariables.addAllStringVariables(additionalBuildVariables);
    for (VariablesExtension extension : variablesExtensions) {
      extension.addVariables(buildVariables);
    }
  }

  /** Get the safe path strings for a list of paths to use in the build variables. */
  private static NestedSet<String> getSafePathStrings(NestedSet<PathFragment> paths) {
    return getSafePathStrings(paths.toList());
  }

  /** Get the safe path strings for a list of paths to use in the build variables. */
  private static NestedSet<String> getSafePathStrings(List<PathFragment> paths) {
    // Using ImmutableSet first to remove duplicates, then NestedSet for smaller memory footprint.
    return NestedSetBuilder.wrap(
        Order.STABLE_ORDER,
        Iterables.transform(ImmutableSet.copyOf(paths), PathFragment::getSafePathString));
  }

  public String getVariableName() {
    return variableName;
  }
}
