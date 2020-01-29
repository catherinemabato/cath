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

package com.google.devtools.build.lib.bazel.rules.ninja;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.base.Preconditions;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.ActionGraph;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.CommandLines.CommandLineAndParamFileInfo;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.actions.SymlinkAction;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.bazel.rules.ninja.actions.NinjaGenericAction;
import com.google.devtools.build.lib.bazel.rules.ninja.actions.NinjaGraphProvider;
import com.google.devtools.build.lib.bazel.rules.ninja.actions.NinjaGraphRule;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.skyframe.PrecomputedValue;
import com.google.devtools.build.lib.syntax.StarlarkSemantics;
import com.google.devtools.build.lib.testutil.TestRuleClassProvider;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.skyframe.Injectable;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link com.google.devtools.build.lib.bazel.rules.ninja.actions.NinjaGraphRule} */
@RunWith(JUnit4.class)
public class NinjaGraphConfiguredTargetTest extends BuildViewTestCase {

  @Override
  protected ConfiguredRuleClassProvider getRuleClassProvider() {
    ConfiguredRuleClassProvider.Builder builder = new ConfiguredRuleClassProvider.Builder();
    TestRuleClassProvider.addStandardRules(builder);
    builder.addRuleDefinition(new NinjaGraphRule());
    return builder.build();
  }

  @Before
  public void setUp() throws Exception {
    setSkylarkSemanticsOptions("--experimental_ninja_actions");
  }

  @Test
  public void testNinjaGraphRule() throws Exception {
    rewriteWorkspace("workspace(name = 'test')",
        "dont_symlink_directories_in_execroot(paths = ['build_config'])");

    scratch.file("build_config/input.txt", "World");
    scratch.file("build_config/build.ninja",
        "rule echo",
        "  command = echo \"Hello $$(cat ${in})!\" > ${out}",
        "build hello.txt: echo input.txt");

    ConfiguredTarget configuredTarget = scratchConfiguredTarget("", "graph",
        "ninja_graph(name = 'graph', output_root = 'build_config',",
        " working_directory = 'build_config',",
        " main = 'build_config/build.ninja',",
        " output_root_inputs = ['input.txt'])");
    NinjaGraphProvider provider = configuredTarget.getProvider(NinjaGraphProvider.class);
    assertThat(provider).isNotNull();
    assertThat(provider.getOutputRoot()).isEqualTo("build_config");
    assertThat(provider.getWorkingDirectory()).isEqualTo("build_config");

    NestedSet<Artifact> filesToBuild = getFilesToBuild(configuredTarget);
    assertThat(artifactsToStrings(filesToBuild)).containsExactly("/ build_config/input.txt",
        "/ build_config/hello.txt");

    ActionGraph actionGraph = getActionGraph();
    for (Artifact artifact : filesToBuild.toList()) {
      ActionAnalysisMetadata action = actionGraph.getGeneratingAction(artifact);
      if ("hello.txt".equals(artifact.getFilename())) {
        assertThat(action instanceof NinjaGenericAction).isTrue();
        NinjaGenericAction ninjaAction = (NinjaGenericAction) action;
        List<CommandLineAndParamFileInfo> commandLines = ninjaAction.getCommandLines()
            .getCommandLines();
        assertThat(commandLines).hasSize(1);
        assertThat(commandLines.get(0).commandLine.toString()).endsWith(
            "cd build_config && echo \"Hello $(cat input.txt)!\" > hello.txt");
        assertThat(ninjaAction.getPrimaryInput().getRootRelativePathString())
            .isEqualTo("build_config/input.txt");
        assertThat(ninjaAction.getPrimaryOutput().getRootRelativePathString())
            .isEqualTo("build_config/hello.txt");
      } else {
        assertThat(action instanceof SymlinkAction).isTrue();
        SymlinkAction symlinkAction = (SymlinkAction) action;
        assertThat(symlinkAction.executeUnconditionally()).isTrue();
        assertThat(symlinkAction.getInputPath()).isEqualTo(
            PathFragment.create("/workspace/build_config/input.txt"));
        assertThat(symlinkAction.getPrimaryOutput().getRootRelativePathString()).isEqualTo(
            "build_config/input.txt");
      }
    }
  }

  @Test
  public void testNinjaGraphRuleWithPhonyTarget() throws Exception {
    rewriteWorkspace("workspace(name = 'test')",
        "dont_symlink_directories_in_execroot(paths = ['build_config'])");

    scratch.file("build_config/input.txt", "World");
    scratch.file("build_config/build.ninja",
        "rule echo",
        "  command = echo \"Hello $$(cat ${in})!\" > ${out}",
        "build hello.txt: echo input.txt",
        "build alias: phony hello.txt");

    ConfiguredTarget configuredTarget = scratchConfiguredTarget("", "graph",
        "ninja_graph(name = 'graph', output_root = 'build_config',",
        " working_directory = 'build_config',",
        " main = 'build_config/build.ninja',",
        " output_root_inputs = ['input.txt'])");
    NinjaGraphProvider provider = configuredTarget.getProvider(NinjaGraphProvider.class);
    assertThat(provider).isNotNull();
    assertThat(provider.getOutputRoot()).isEqualTo("build_config");
    assertThat(provider.getWorkingDirectory()).isEqualTo("build_config");

    NestedSet<Artifact> filesToBuild = getFilesToBuild(configuredTarget);
    assertThat(artifactsToStrings(filesToBuild)).containsExactly("/ build_config/input.txt",
        "/ build_config/hello.txt");

    ActionGraph actionGraph = getActionGraph();
    for (Artifact artifact : filesToBuild.toList()) {
      ActionAnalysisMetadata action = actionGraph.getGeneratingAction(artifact);
      if ("hello.txt".equals(artifact.getFilename())) {
        assertThat(action instanceof NinjaGenericAction).isTrue();
        NinjaGenericAction ninjaAction = (NinjaGenericAction) action;
        List<CommandLineAndParamFileInfo> commandLines = ninjaAction.getCommandLines()
            .getCommandLines();
        assertThat(commandLines).hasSize(1);
        assertThat(commandLines.get(0).commandLine.toString()).endsWith(
            "cd build_config && echo \"Hello $(cat input.txt)!\" > hello.txt");
        assertThat(ninjaAction.getPrimaryInput().getRootRelativePathString())
            .isEqualTo("build_config/input.txt");
        assertThat(ninjaAction.getPrimaryOutput().getRootRelativePathString())
            .isEqualTo("build_config/hello.txt");
      } else {
        assertThat(action instanceof SymlinkAction).isTrue();
        SymlinkAction symlinkAction = (SymlinkAction) action;
        assertThat(symlinkAction.executeUnconditionally()).isTrue();
        assertThat(symlinkAction.getInputPath()).isEqualTo(
            PathFragment.create("/workspace/build_config/input.txt"));
        assertThat(symlinkAction.getPrimaryOutput().getRootRelativePathString()).isEqualTo(
            "build_config/input.txt");
      }
    }
  }
  @Test
  public void testNinjaGraphRuleWithPhonyTree() throws Exception {
    rewriteWorkspace("workspace(name = 'test')",
        "dont_symlink_directories_in_execroot(paths = ['build_config'])");

    scratch.file("build_config/a.txt", "A");
    scratch.file("build_config/b.txt", "B");
    scratch.file("build_config/c.txt", "C");
    scratch.file("build_config/d.txt", "D");
    scratch.file("build_config/e.txt", "E");

    scratch.file("build_config/build.ninja",
        "rule cat",
        "  command = cat ${in} > ${out}",
        "rule echo",
        "  command = echo \"Hello $$(cat ${in} | tr '\\r\\n' ' ')!\" > ${out}",
        "build a: cat a.txt",
        "build b: cat b.txt",
        "build c: cat c.txt",
        "build d: cat d.txt",
        "build e: cat e.txt",
        "build group1: phony a b c",
        "build group2: phony d e",
        "build inputs_alias: phony group1 group2",
        "build hello.txt: echo inputs_alias",
        "build alias: phony hello.txt");

    ConfiguredTarget configuredTarget = scratchConfiguredTarget("", "graph",
        "ninja_graph(name = 'graph', output_root = 'build_config',",
        " working_directory = 'build_config',",
        " main = 'build_config/build.ninja',",
        " output_root_inputs = ['a.txt', 'b.txt', 'c.txt', 'd.txt', 'e.txt'])");
    NinjaGraphProvider provider = configuredTarget.getProvider(NinjaGraphProvider.class);
    assertThat(provider).isNotNull();
    assertThat(provider.getOutputRoot()).isEqualTo("build_config");
    assertThat(provider.getWorkingDirectory()).isEqualTo("build_config");

    NestedSet<Artifact> filesToBuild = getFilesToBuild(configuredTarget);
    assertThat(artifactsToStrings(filesToBuild)).containsExactly("/ build_config/hello.txt",
        "/ build_config/a.txt", "/ build_config/b.txt", "/ build_config/c.txt",
        "/ build_config/d.txt", "/ build_config/e.txt", "/ build_config/a", "/ build_config/b",
        "/ build_config/c", "/ build_config/d", "/ build_config/e");

    ActionGraph actionGraph = getActionGraph();
    for (Artifact artifact : filesToBuild.toList()) {
      ActionAnalysisMetadata action = actionGraph.getGeneratingAction(artifact);
      if ("hello.txt".equals(artifact.getFilename())) {
        assertThat(action instanceof NinjaGenericAction).isTrue();
        NinjaGenericAction ninjaAction = (NinjaGenericAction) action;
        List<CommandLineAndParamFileInfo> commandLines = ninjaAction.getCommandLines()
            .getCommandLines();
        assertThat(commandLines).hasSize(1);
        assertThat(commandLines.get(0).commandLine.toString()).contains(
            "cd build_config && echo \"Hello $(cat inputs_alias | tr '\\r\\n' ' ')!\" > hello.txt");
        assertThat(artifactsToStrings(ninjaAction.getInputs()))
            .containsExactly("/ build_config/a", "/ build_config/b", "/ build_config/c",
            "/ build_config/d", "/ build_config/e");
        assertThat(ninjaAction.getPrimaryOutput().getRootRelativePathString())
            .isEqualTo("build_config/hello.txt");
      } else if (artifact.getFilename().endsWith(".txt")) {
        assertThat(action instanceof SymlinkAction).isTrue();
        SymlinkAction symlinkAction = (SymlinkAction) action;
        assertThat(symlinkAction.executeUnconditionally()).isTrue();
        assertThat(symlinkAction.getInputPath().getParentDirectory()).isEqualTo(
            PathFragment.create("/workspace/build_config"));
        assertThat(symlinkAction.getInputPath().getFileExtension()).isEqualTo("txt");
        PathFragment outputRootRelativePath = symlinkAction.getPrimaryOutput().getRootRelativePath();
        assertThat(outputRootRelativePath.getParentDirectory()).isEqualTo(
            PathFragment.create("build_config"));
        assertThat(outputRootRelativePath.getFileExtension()).isEqualTo("txt");
      }
    }
  }
}
