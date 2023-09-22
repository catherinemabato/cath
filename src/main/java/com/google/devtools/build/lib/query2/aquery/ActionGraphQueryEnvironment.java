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
package com.google.devtools.build.lib.query2.aquery;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.ConfiguredTargetValue;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.config.transitions.TransitionFactory;
import com.google.devtools.build.lib.analysis.configuredtargets.RuleConfiguredTarget;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.TargetParsingException;
import com.google.devtools.build.lib.cmdline.TargetPattern;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.packages.LabelPrinter;
import com.google.devtools.build.lib.packages.RuleTransitionData;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.pkgcache.PackageManager;
import com.google.devtools.build.lib.pkgcache.PathPackageLocator;
import com.google.devtools.build.lib.query2.NamedThreadSafeOutputFormatterCallback;
import com.google.devtools.build.lib.query2.PostAnalysisQueryEnvironment;
import com.google.devtools.build.lib.query2.SkyQueryEnvironment;
import com.google.devtools.build.lib.query2.engine.Callback;
import com.google.devtools.build.lib.query2.engine.InputsFunction;
import com.google.devtools.build.lib.query2.engine.KeyExtractor;
import com.google.devtools.build.lib.query2.engine.MnemonicFunction;
import com.google.devtools.build.lib.query2.engine.OutputsFunction;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment;
import com.google.devtools.build.lib.query2.engine.QueryException;
import com.google.devtools.build.lib.query2.engine.QueryExpression;
import com.google.devtools.build.lib.query2.engine.QueryUtil.ThreadSafeMutableKeyExtractorBackedSetImpl;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetKey;
import com.google.devtools.build.lib.skyframe.SkyframeExecutor;
import com.google.devtools.build.lib.skyframe.actiongraph.v2.StreamedOutputHandler;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.WalkableGraph;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import net.starlark.java.eval.StarlarkSemantics;

/**
 * {@link QueryEnvironment} that is specialized for running action graph queries over the configured
 * target graph.
 */
public class ActionGraphQueryEnvironment
    extends PostAnalysisQueryEnvironment<KeyedConfiguredTargetValue> {

  public static final ImmutableList<QueryFunction> AQUERY_FUNCTIONS = populateAqueryFunctions();
  public static final ImmutableList<QueryFunction> FUNCTIONS = populateFunctions();
  private AqueryOptions aqueryOptions;

  private AqueryActionFilter actionFilters;
  private final KeyExtractor<KeyedConfiguredTargetValue, ConfiguredTargetKey>
      configuredTargetKeyExtractor;
  private final ConfiguredTargetValueAccessor accessor;

  public ActionGraphQueryEnvironment(
      boolean keepGoing,
      ExtendedEventHandler eventHandler,
      Iterable<QueryFunction> extraFunctions,
      TopLevelConfigurations topLevelConfigurations,
      BuildConfigurationValue hostConfiguration,
      TargetPattern.Parser mainRepoTargetParser,
      PathPackageLocator pkgPath,
      Supplier<WalkableGraph> walkableGraphSupplier,
      Set<Setting> settings,
      LabelPrinter labelPrinter) {
    super(
        keepGoing,
        eventHandler,
        extraFunctions,
        topLevelConfigurations,
        hostConfiguration,
        mainRepoTargetParser,
        pkgPath,
        walkableGraphSupplier,
        settings,
        labelPrinter);
    this.configuredTargetKeyExtractor = KeyedConfiguredTargetValue::getConfiguredTargetKey;
    this.accessor =
        new ConfiguredTargetValueAccessor(
            walkableGraphSupplier.get(), this::getTarget, this.configuredTargetKeyExtractor);
  }

  public ActionGraphQueryEnvironment(
      boolean keepGoing,
      ExtendedEventHandler eventHandler,
      Iterable<QueryFunction> extraFunctions,
      TopLevelConfigurations topLevelConfigurations,
      BuildConfigurationValue hostConfiguration,
      TargetPattern.Parser mainRepoTargetParser,
      PathPackageLocator pkgPath,
      Supplier<WalkableGraph> walkableGraphSupplier,
      AqueryOptions aqueryOptions,
      LabelPrinter labelPrinter) {
    this(
        keepGoing,
        eventHandler,
        extraFunctions,
        topLevelConfigurations,
        hostConfiguration,
        mainRepoTargetParser,
        pkgPath,
        walkableGraphSupplier,
        aqueryOptions.toSettings(),
        labelPrinter);
    this.aqueryOptions = aqueryOptions;
  }

  private static ImmutableList<QueryFunction> populateFunctions() {
    return ImmutableList.copyOf(QueryEnvironment.DEFAULT_QUERY_FUNCTIONS);
  }

  private static ImmutableList<QueryFunction> populateAqueryFunctions() {
    return ImmutableList.of(new InputsFunction(), new OutputsFunction(), new MnemonicFunction());
  }

  @Override
  public ConfiguredTargetValueAccessor getAccessor() {
    return accessor;
  }

  @Override
  public ImmutableList<NamedThreadSafeOutputFormatterCallback<KeyedConfiguredTargetValue>>
      getDefaultOutputFormatters(
          TargetAccessor<KeyedConfiguredTargetValue> accessor,
          ExtendedEventHandler eventHandler,
          OutputStream out,
          SkyframeExecutor skyframeExecutor,
          BuildConfigurationValue hostConfiguration,
          @Nullable TransitionFactory<RuleTransitionData> trimmingTransitionFactory,
          PackageManager packageManager,
          StarlarkSemantics starlarkSemantics) {
    return ImmutableList.of(
        new ActionGraphProtoOutputFormatterCallback(
            eventHandler,
            aqueryOptions,
            out,
            skyframeExecutor,
            accessor,
            StreamedOutputHandler.OutputType.BINARY,
            actionFilters),
        new ActionGraphProtoOutputFormatterCallback(
            eventHandler,
            aqueryOptions,
            out,
            skyframeExecutor,
            accessor,
            StreamedOutputHandler.OutputType.TEXT,
            actionFilters),
        new ActionGraphProtoOutputFormatterCallback(
            eventHandler,
            aqueryOptions,
            out,
            skyframeExecutor,
            accessor,
            StreamedOutputHandler.OutputType.JSON,
            actionFilters),
        new ActionGraphTextOutputFormatterCallback(
            eventHandler,
            aqueryOptions,
            out,
            skyframeExecutor,
            accessor,
            actionFilters,
            getLabelPrinter()),
        new ActionGraphSummaryOutputFormatterCallback(
            eventHandler, aqueryOptions, out, skyframeExecutor, accessor, actionFilters));
  }

  @Override
  public String getOutputFormat() {
    return aqueryOptions.outputFormat;
  }

  @Override
  protected KeyExtractor<KeyedConfiguredTargetValue, ConfiguredTargetKey>
      getConfiguredTargetKeyExtractor() {
    return configuredTargetKeyExtractor;
  }

  @Override
  public Label getCorrectLabel(KeyedConfiguredTargetValue keyedConfiguredTargetValue) {
    ConfiguredTarget target = keyedConfiguredTargetValue.getConfiguredTarget();
    // Dereference any aliases that might be present.
    return target.getOriginalLabel();
  }

  @Nullable
  private KeyedConfiguredTargetValue createKeyedConfiguredTargetValueFromKey(
      ConfiguredTargetKey key) throws InterruptedException {
    ConfiguredTargetValue configuredTargetValue = getConfiguredTargetValue(key);
    return configuredTargetValue == null
        ? null
        : KeyedConfiguredTargetValue.create(configuredTargetValue, key);
  }

  @Nullable
  @Override
  protected KeyedConfiguredTargetValue getHostConfiguredTarget(Label label)
      throws InterruptedException {
    return createKeyedConfiguredTargetValueFromKey(
        ConfiguredTargetKey.builder().setLabel(label).setConfiguration(hostConfiguration).build());
  }

  @Nullable
  @Override
  protected KeyedConfiguredTargetValue getTargetConfiguredTarget(Label label)
      throws InterruptedException {
    if (topLevelConfigurations.isTopLevelTarget(label)) {
      return createKeyedConfiguredTargetValueFromKey(
          ConfiguredTargetKey.builder()
              .setLabel(label)
              .setConfiguration(topLevelConfigurations.getConfigurationForTopLevelTarget(label))
              .build());
    } else {
      KeyedConfiguredTargetValue toReturn;
      for (BuildConfigurationValue configuration : topLevelConfigurations.getConfigurations()) {
        toReturn =
            createKeyedConfiguredTargetValueFromKey(
                ConfiguredTargetKey.builder()
                    .setLabel(label)
                    .setConfiguration(configuration)
                    .build());
        if (toReturn != null) {
          return toReturn;
        }
      }
      return null;
    }
  }

  @Nullable
  @Override
  protected KeyedConfiguredTargetValue getNullConfiguredTarget(Label label)
      throws InterruptedException {
    return createKeyedConfiguredTargetValueFromKey(
        ConfiguredTargetKey.builder().setLabel(label).build());
  }

  @Nullable
  @Override
  protected KeyedConfiguredTargetValue getValueFromKey(SkyKey key) throws InterruptedException {
    Preconditions.checkState(key instanceof ConfiguredTargetKey);
    return createKeyedConfiguredTargetValueFromKey((ConfiguredTargetKey) key);
  }

  @Nullable
  @Override
  protected RuleConfiguredTarget getRuleConfiguredTarget(
      KeyedConfiguredTargetValue keyedConfiguredTargetValue) {
    ConfiguredTarget configuredTarget = keyedConfiguredTargetValue.getConfiguredTarget();
    if (configuredTarget instanceof RuleConfiguredTarget) {
      return (RuleConfiguredTarget) configuredTarget;
    }
    return null;
  }

  @Nullable
  @Override
  protected BuildConfigurationValue getConfiguration(
      KeyedConfiguredTargetValue keyedConfiguredTargetValue) {
    ConfiguredTarget target = keyedConfiguredTargetValue.getConfiguredTarget();
    try {
      return target.getConfigurationKey() == null
          ? null
          : (BuildConfigurationValue) graph.getValue(target.getConfigurationKey());
    } catch (InterruptedException e) {
      throw new IllegalStateException("Unexpected interruption during aquery", e);
    }
  }

  @Override
  protected ConfiguredTargetKey getSkyKey(KeyedConfiguredTargetValue keyedConfiguredTargetValue) {
    return keyedConfiguredTargetValue.getConfiguredTargetKey();
  }

  @Override
  public QueryTaskFuture<Void> getTargetsMatchingPattern(
      QueryExpression owner, String pattern, Callback<KeyedConfiguredTargetValue> callback) {
    TargetPattern patternToEval;
    try {
      patternToEval = getPattern(pattern);
    } catch (TargetParsingException tpe) {
      try {
        handleError(owner, tpe.getMessage(), tpe.getDetailedExitCode());
      } catch (QueryException qe) {
        return immediateFailedFuture(qe);
      }
      return immediateSuccessfulFuture(null);
    }

    AsyncFunction<TargetParsingException, Void> reportBuildFileErrorAsyncFunction =
        exn -> {
          handleError(owner, exn.getMessage(), exn.getDetailedExitCode());
          return Futures.immediateFuture(null);
        };
    return QueryTaskFutureImpl.ofDelegate(
        Futures.catchingAsync(
            patternToEval.evalAdaptedForAsync(
                resolver,
                getIgnoredPackagePrefixesPathFragments(),
                /*excludedSubdirectories=*/ ImmutableSet.of(),
                (Callback<Target>)
                    partialResult -> {
                      List<KeyedConfiguredTargetValue> transformedResult = new ArrayList<>();
                      for (Target target : partialResult) {
                        KeyedConfiguredTargetValue keyedConfiguredTargetValue =
                            getKeyedConfiguredTargetValue(target.getLabel());
                        if (keyedConfiguredTargetValue != null) {
                          transformedResult.add(keyedConfiguredTargetValue);
                        }
                      }
                      callback.process(transformedResult);
                    },
                QueryException.class),
            TargetParsingException.class,
            reportBuildFileErrorAsyncFunction,
            MoreExecutors.directExecutor()));
  }

  private KeyedConfiguredTargetValue getKeyedConfiguredTargetValue(Label label)
      throws InterruptedException {
    // Try with target configuration.
    KeyedConfiguredTargetValue keyedConfiguredTargetValue = getTargetConfiguredTarget(label);
    if (keyedConfiguredTargetValue != null) {
      return keyedConfiguredTargetValue;
    }
    // Try with host configuration (even when --notool_deps is set in the case that top-level
    // targets are configured in the host configuration so we are doing a host-configuration-only
    // query).
    keyedConfiguredTargetValue = getHostConfiguredTarget(label);
    if (keyedConfiguredTargetValue != null) {
      return keyedConfiguredTargetValue;
    }
    // Last chance: source file.
    return getNullConfiguredTarget(label);
  }

  @Override
  public ThreadSafeMutableSet<KeyedConfiguredTargetValue> createThreadSafeMutableSet() {
    return new ThreadSafeMutableKeyExtractorBackedSetImpl<>(
        configuredTargetKeyExtractor,
        KeyedConfiguredTargetValue.class,
        SkyQueryEnvironment.DEFAULT_THREAD_COUNT);
  }

  public void setActionFilters(AqueryActionFilter actionFilters) {
    this.actionFilters = actionFilters;
  }
}
