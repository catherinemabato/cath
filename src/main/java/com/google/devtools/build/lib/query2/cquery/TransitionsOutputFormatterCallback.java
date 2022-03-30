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
package com.google.devtools.build.lib.query2.cquery;

import static java.util.stream.Collectors.joining;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.DependencyKey;
import com.google.devtools.build.lib.analysis.DependencyKind;
import com.google.devtools.build.lib.analysis.DependencyKind.ToolchainDependencyKind;
import com.google.devtools.build.lib.analysis.DependencyResolver;
import com.google.devtools.build.lib.analysis.InconsistentAspectOrderException;
import com.google.devtools.build.lib.analysis.TargetAndConfiguration;
import com.google.devtools.build.lib.analysis.ToolchainCollection;
import com.google.devtools.build.lib.analysis.ToolchainContext;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.BuildOptions.OptionsDiff;
import com.google.devtools.build.lib.analysis.config.ConfigMatchingProvider;
import com.google.devtools.build.lib.analysis.config.transitions.ConfigurationTransition;
import com.google.devtools.build.lib.analysis.config.transitions.NoTransition;
import com.google.devtools.build.lib.analysis.config.transitions.NullTransition;
import com.google.devtools.build.lib.analysis.config.transitions.TransitionFactory;
import com.google.devtools.build.lib.analysis.config.transitions.TransitionUtil;
import com.google.devtools.build.lib.analysis.configuredtargets.RuleConfiguredTarget;
import com.google.devtools.build.lib.causes.Cause;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.packages.RuleTransitionData;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.query2.cquery.TransitionResolver.ResolvedTransition;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.TargetAccessor;
import com.google.devtools.build.lib.skyframe.SkyframeExecutor;
import com.google.devtools.build.lib.util.OrderedSetMultimap;
import java.io.OutputStream;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * Output formatter that prints {@link ConfigurationTransition} information for rule configured
 * targets in the results of a cquery call.
 */
class TransitionsOutputFormatterCallback extends CqueryThreadsafeCallback {

  protected final BuildConfigurationValue hostConfiguration;

  private final HashMap<Label, Target> partialResultMap;
  @Nullable private final TransitionFactory<RuleTransitionData> trimmingTransitionFactory;

  @Override
  public String getName() {
    return "transitions";
  }

  /**
   * @param accessor provider of query result configured targets.
   * @param hostConfiguration host configuration for this query.
   */
  TransitionsOutputFormatterCallback(
      ExtendedEventHandler eventHandler,
      CqueryOptions options,
      OutputStream out,
      SkyframeExecutor skyframeExecutor,
      TargetAccessor<KeyedConfiguredTarget> accessor,
      BuildConfigurationValue hostConfiguration,
      @Nullable TransitionFactory<RuleTransitionData> trimmingTransitionFactory) {
    super(eventHandler, options, out, skyframeExecutor, accessor);
    this.hostConfiguration = hostConfiguration;
    this.trimmingTransitionFactory = trimmingTransitionFactory;
    this.partialResultMap = Maps.newHashMap();
  }

  @Override
  public void processOutput(Iterable<KeyedConfiguredTarget> partialResult)
      throws InterruptedException {
    CqueryOptions.Transitions verbosity = options.transitions;
    if (verbosity.equals(CqueryOptions.Transitions.NONE)) {
      eventHandler.handle(
          Event.error(
              "Instead of using --output=transitions, set the --transitions"
                  + " flag explicitly to 'lite' or 'full'"));
      return;
    }
    partialResult.forEach(kct -> partialResultMap.put(kct.getLabel(), accessor.getTarget(kct)));
    for (KeyedConfiguredTarget keyedConfiguredTarget : partialResult) {
      Target target = partialResultMap.get(keyedConfiguredTarget.getLabel());
      BuildConfigurationValue config =
          getConfiguration(keyedConfiguredTarget.getConfigurationKey());
      addResult(
          getRuleClassTransition(keyedConfiguredTarget.getConfiguredTarget(), target)
              + String.format(
                  "%s (%s)",
                  keyedConfiguredTarget.getConfiguredTarget().getOriginalLabel(), shortId(config)));
      KnownTargetsDependencyResolver knownTargetsDependencyResolver = new KnownTargetsDependencyResolver(partialResultMap);
      Iterable<ResolvedTransition> dependencies;
      try {
        dependencies = new TransitionResolver(eventHandler, knownTargetsDependencyResolver, accessor, this, trimmingTransitionFactory).dependencies(keyedConfiguredTarget);
      } catch (DependencyResolver.Failure | InconsistentAspectOrderException e) {
        // This is an abuse of InterruptedException.
        throw new InterruptedException(e.getMessage());
      }
        for (ResolvedTransition dep : dependencies) {
          addResult(
              "  "
                  .concat(dep.attributeName())
                  .concat("#")
                  .concat(dep.label().toString())
                  .concat("#")
                  .concat(dep.transitionName())
                  .concat(" -> ")
                  .concat(
                      dep.options().stream()
                          .map(
                              options -> {
                                String checksum = options.checksum();
                                return checksum.equals(hostConfiguration.checksum())
                                    ? "HOST"
                                    : shortId(checksum);
                              })
                          .collect(joining(", "))));
        if (verbosity == CqueryOptions.Transitions.LITE) {
          continue;
        }
        OptionsDiff diff = new OptionsDiff();
        for (BuildOptions options : dep.options()) {
          diff = BuildOptions.diff(diff, config.getOptions(), options);
        }
        diff.getPrettyPrintList().forEach(singleDiff -> addResult("    " + singleDiff));
      }
    }
  }

  private static String getRuleClassTransition(ConfiguredTarget ct, Target target) {
    String output = "";
    if (ct instanceof RuleConfiguredTarget) {
      TransitionFactory<RuleTransitionData> factory =
          target.getAssociatedRule().getRuleClassObject().getTransitionFactory();
      if (factory != null) {
        output =
            factory
                .create(RuleTransitionData.create(target.getAssociatedRule()))
                .getName()
                .concat(" -> ");
      }
    }
    return output;
  }
}
