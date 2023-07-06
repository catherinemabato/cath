// Copyright 2018 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.starlarkbuildapi.java;

import com.google.devtools.build.lib.collect.nestedset.Depset;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.starlarkbuildapi.FileApi;
import com.google.devtools.build.lib.starlarkbuildapi.core.ProviderApi;
import com.google.devtools.build.lib.starlarkbuildapi.core.StructApi;
import com.google.devtools.build.lib.starlarkbuildapi.cpp.CcInfoApi;
import com.google.devtools.build.lib.starlarkbuildapi.java.JavaPluginInfoApi.JavaPluginDataApi;
import javax.annotation.Nullable;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.Sequence;

/** Info object encapsulating all information by java rules. */
public interface JavaInfoApi<
        FileT extends FileApi,
        JavaOutputT extends JavaOutputApi<FileT>,
        JavaPluginDataT extends JavaPluginDataApi>
    extends StructApi, JavaPluginInfoApi<FileT, JavaPluginDataT, JavaOutputT> {

  @StarlarkMethod(
      name = "_neverlink",
      doc = "Whether this library should be used only for compilation and not at runtime.",
      structField = true)
  boolean isNeverlink();

  @StarlarkMethod(
      name = "transitive_runtime_jars",
      doc = "Returns a transitive set of Jars required on the target's runtime classpath.",
      structField = true)
  Depset getTransitiveRuntimeJars();

  @StarlarkMethod(
      name = "transitive_compile_time_jars",
      doc = "Returns the transitive set of Jars required to build the target.",
      structField = true)
  Depset getTransitiveCompileTimeJars();

  @StarlarkMethod(
      name = "compile_jars",
      doc =
          "Returns the Jars required by this target directly at compile time. They can be"
              + " interface jars (ijar or hjar), regular jars or both, depending on whether rule"
              + " implementations chose to create interface jars or not.",
      structField = true)
  Depset getCompileTimeJars();

  @StarlarkMethod(
      name = "full_compile_jars",
      doc =
          "Returns the regular, full compile time Jars required by this target directly. They can"
              + " be <ul><li> the corresponding regular Jars of the interface Jars returned by"
              + " <code><a class=\"anchor\""
              + " href=\"#compile_jars\">JavaInfo.compile_jars</a></code></li><li>"
              + " the regular (full) Jars returned by <code><a class=\"anchor\""
              + " href=\"#compile_jars\">JavaInfo.compile_jars</a></code></li></ul>"
              + "<p>Note: <code><a class=\"anchor\""
              + " href=\"#compile_jars\">JavaInfo.compile_jars</a></code> can return"
              + " a mix of interface Jars and regular Jars.<p>Only use this method if interface"
              + " Jars don't work with your rule set(s) (e.g. some Scala targets) If you're"
              + " working with Java-only targets it's preferable to use interface Jars via"
              + " <code><a class=\"anchor\""
              + " href=\"#compile_jars\">JavaInfo.compile_jars</a></code></li>",
      structField = true)
  Depset getFullCompileTimeJars();

  @StarlarkMethod(
      name = "source_jars",
      doc =
          "Returns a list of Jars with all the source files (including those generated by"
              + " annotations) of the target  itself, i.e. NOT including the sources of the"
              + " transitive dependencies.",
      structField = true)
  Sequence<FileT> getSourceJars();

  @StarlarkMethod(
      name = "outputs",
      doc =
          "Returns information about outputs of this Java/Java-like target. Deprecated: use"
              + " java_outputs.",
      structField = true,
      allowReturnNones = true)
  @Nullable
  @Deprecated
  JavaRuleOutputJarsProviderApi<?> getOutputJars();

  @StarlarkMethod(
      name = "annotation_processing",
      structField = true,
      allowReturnNones = true,
      doc =
          "Returns information about annotation processors applied on this Java/Java-like target."
              + "<p>Deprecated: Please use <code>plugins</code> instead (which returns information "
              + "about annotation processors to be applied by consuming targets).")
  @Nullable
  JavaAnnotationProcessingApi<?> getGenJarsProvider();

  @StarlarkMethod(
      name = "compilation_info",
      structField = true,
      allowReturnNones = true,
      doc = "Returns compilation information for this Java/Java-like target.")
  @Nullable
  JavaCompilationInfoProviderApi<?> getCompilationInfoProvider();

  @StarlarkMethod(
      name = "runtime_output_jars",
      doc = "Returns a list of runtime Jars created by this Java/Java-like target.",
      structField = true)
  Sequence<FileT> getRuntimeOutputJars();

  @StarlarkMethod(
      name = "transitive_deps",
      doc =
          "Deprecated: Please use <code><a class=\"anchor\" "
              + "href=\"#transitive_compile_time_jars\">JavaInfo.transitive_compile_time_jars</a></code>"
              + " instead. It returns the same value.",
      structField = true)
  Depset /*<FileT>*/ getTransitiveDeps();

  @StarlarkMethod(
      name = "transitive_runtime_deps",
      doc =
          "Deprecated: please use <code><a class=\"anchor\""
              + " href=\"#transitive_runtime_jars\">JavaInfo.transitive_runtime_jars"
              + "</a></code> instead. It returns the same value",
      structField = true)
  Depset /*<FileT>*/ getTransitiveRuntimeDeps();

  @StarlarkMethod(
      name = "transitive_source_jars",
      doc =
          "Returns the Jars containing source files of the current target and all of its"
              + " transitive dependencies.",
      structField = true)
  Depset /*<FileT>*/ getTransitiveSourceJars();

  @StarlarkMethod(
      name = "transitive_native_libraries",
      structField = true,
      doc = "Returns the transitive set of CC native libraries required by the target.")
  Depset /*<LibraryToLink>*/ getTransitiveNativeLibrariesForStarlark();

  @StarlarkMethod(
      name = "cc_link_params_info",
      structField = true,
      enableOnlyWithFlag = BuildLanguageOptions.EXPERIMENTAL_GOOGLE_LEGACY_API,
      doc = "Deprecated, do not use. C++ libraries to be linked into Java targets.")
  CcInfoApi<FileT> getCcLinkParamInfo();

  @StarlarkMethod(
      name = "module_flags_info",
      doc = "Returns the Java module flag configuration.",
      structField = true)
  JavaModuleFlagsProviderApi getJavaModuleFlagsInfo();

  @StarlarkMethod(
      name = "_transitive_full_compile_time_jars",
      documented = false,
      structField = true)
  Depset getTransitiveFullCompileJars();

  @StarlarkMethod(name = "_compile_time_java_dependencies", documented = false, structField = true)
  Depset getCompileTimeJavaDependencies();

  @StarlarkMethod(name = "_constraints", documented = false, structField = true)
  Sequence<String> getJavaConstraintsStarlark();

  /** Provider class for {@link JavaInfoApi} objects. */
  interface JavaInfoProviderApi extends ProviderApi {

  }
}
