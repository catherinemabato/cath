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
package com.google.devtools.build.lib.rules.platform;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.SkylarkProvider.SkylarkKey;
import com.google.devtools.build.lib.packages.StructImpl;
import com.google.devtools.build.lib.skylarkbuildapi.platform.PlatformConfigurationApi;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests Skylark API for Platform configuration fragments. */
@RunWith(JUnit4.class)
public class PlatformConfigurationApiTest extends BuildViewTestCase {

  @Test
  public void testHostPlatform() throws Exception {
    scratch.file("platforms/BUILD", "platform(name = 'test_platform')");

    useConfiguration("--host_platform=//platforms:test_platform");
    ruleBuilder().build();
    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "my_rule(",
        "  name = 'my_skylark_rule',",
        ")");
    assertNoEvents();

    PlatformConfigurationApi platformConfiguration = fetchPlatformConfiguration();
    assertThat(platformConfiguration).isNotNull();
    assertThat(platformConfiguration.getHostPlatform())
        .isEqualTo(Label.parseAbsoluteUnchecked("//platforms:test_platform"));
  }

  @Test
  public void testTargetPlatform_single() throws Exception {
    scratch.file("platforms/BUILD", "platform(name = 'test_platform')");

    useConfiguration("--platforms=//platforms:test_platform");
    ruleBuilder().build();
    scratch.file(
        "foo/BUILD",
        "load(':extension.bzl', 'my_rule')",
        "my_rule(",
        "  name = 'my_skylark_rule',",
        ")");
    assertNoEvents();

    PlatformConfigurationApi platformConfiguration = fetchPlatformConfiguration();
    assertThat(platformConfiguration).isNotNull();
    assertThat(platformConfiguration.getTargetPlatform())
        .isEqualTo(Label.parseAbsoluteUnchecked("//platforms:test_platform"));
    assertThat(platformConfiguration.getTargetPlatforms())
        .containsExactly(Label.parseAbsoluteUnchecked("//platforms:test_platform"));
  }

  private RuleBuilder ruleBuilder() {
    return new RuleBuilder();
  }

  private class RuleBuilder {
    private void build() throws Exception {
      ImmutableList.Builder<String> lines = ImmutableList.builder();
      lines.add(
          "result = provider()",
          "def _impl(ctx):",
          "  platformConfig = ctx.fragments.platform",
          "  return [result(property = platformConfig)]");
      lines.add("my_rule = rule(", "  implementation = _impl,", "  fragments = ['platform'],", ")");

      scratch.file("foo/extension.bzl", lines.build().toArray(new String[] {}));
    }
  }

  private PlatformConfigurationApi fetchPlatformConfiguration() throws Exception {
    ConfiguredTarget myRuleTarget = getConfiguredTarget("//foo:my_skylark_rule");
    StructImpl info =
        (StructImpl)
            myRuleTarget.get(
                new SkylarkKey(
                    Label.parseAbsolute("//foo:extension.bzl", ImmutableMap.of()), "result"));

    @SuppressWarnings("unchecked")
    PlatformConfigurationApi javaInfo = (PlatformConfigurationApi) info.getValue("property");
    return javaInfo;
  }
}
