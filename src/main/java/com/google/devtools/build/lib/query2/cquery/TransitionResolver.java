package com.google.devtools.build.lib.query2.cquery;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
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
import com.google.devtools.build.lib.analysis.config.ConfigMatchingProvider;
import com.google.devtools.build.lib.analysis.config.transitions.NoTransition;
import com.google.devtools.build.lib.analysis.config.transitions.NullTransition;
import com.google.devtools.build.lib.analysis.config.transitions.TransitionFactory;
import com.google.devtools.build.lib.analysis.config.transitions.TransitionUtil;
import com.google.devtools.build.lib.analysis.configuredtargets.RuleConfiguredTarget;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.packages.RuleTransitionData;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.util.OrderedSetMultimap;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * TransitionResolver resolves the dependencies of a ConfiguredTarget, reporting which
 * configurations its dependencies are actually needed in according to the transitions applied to
 * them.
 */
public class TransitionResolver {

  /**
   * ResolvedTransition represents a single edge in the dependency graph, between some target
   * and a target it depends on, reachable via a single attribute.
   */
  @AutoValue
  @Immutable
  public abstract static class ResolvedTransition {

    static ResolvedTransition create(Label label, Collection<BuildOptions> configurationChecksum, String attributeName, String transitionName) {
      return new AutoValue_TransitionResolver_ResolvedTransition(label, configurationChecksum, attributeName, transitionName);
    }

    /**
     * The label of the target being depended on.
     */
    abstract Label label();

    /**
     * The configuration(s) this edge results in. This is a collection because a split transition
     * may lead to a single attribute requesting a dependency in multiple configurations.
     *
     * If a target is depended on via two attributes, separate ResolvedTransitions should be used,
     * rather than combining the two into a single ResolvedTransition with multiple options.
     *
     * If no transition was applied to an attribute, this collection will be empty.
     */
    abstract Collection<BuildOptions> options();

    /**
     * The name of the attribute via which the dependency was requested.
     */
    abstract String attributeName();

    /**
     * The name of the transition applied to the attribute.
     */
    abstract String transitionName();
  }

  private final ExtendedEventHandler eventHandler;
  private final DependencyResolver dependencyResolver;
  private final ConfiguredTargetAccessor accessor;
  private final CqueryThreadsafeCallback cqueryThreadsafeCallback;
  @Nullable private final TransitionFactory<RuleTransitionData> trimmingTransitionFactory;

  public TransitionResolver(
      ExtendedEventHandler eventHandler,
      DependencyResolver dependencyResolver,
      ConfiguredTargetAccessor accessor,
      CqueryThreadsafeCallback cqueryThreadsafeCallback,
      @Nullable TransitionFactory<RuleTransitionData> trimmingTransitionFactory) {
    this.eventHandler = eventHandler;
    this.dependencyResolver = dependencyResolver;
    this.accessor = accessor;
    this.cqueryThreadsafeCallback = cqueryThreadsafeCallback;
    this.trimmingTransitionFactory = trimmingTransitionFactory;
  }

  /**
   * Return the set of dependencies of a KeyedConfiguredTarget, including information about the
   * configuration transitions applied to the dependencies.
   *
   * @see ResolvedTransition for more details.
   *
   * @param keyedConfiguredTarget the configured target whose dependencies are being looked up.
   */
  public LinkedHashSet<ResolvedTransition> dependencies(KeyedConfiguredTarget keyedConfiguredTarget) throws DependencyResolver.Failure, InconsistentAspectOrderException, InterruptedException {
    LinkedHashSet<ResolvedTransition> resolved = new LinkedHashSet<>();

    if (!(keyedConfiguredTarget.getConfiguredTarget() instanceof RuleConfiguredTarget)) {
      return resolved;
    }

    Target target = accessor.getTarget(keyedConfiguredTarget);
    BuildConfigurationValue config =
        cqueryThreadsafeCallback.getConfiguration(keyedConfiguredTarget.getConfigurationKey());

    OrderedSetMultimap<DependencyKind, DependencyKey> deps;
    ImmutableMap<Label, ConfigMatchingProvider> configConditions =
        keyedConfiguredTarget.getConfigConditions();

    // Get a ToolchainContext to use for dependency resolution.
    ToolchainCollection<ToolchainContext> toolchainContexts =
        accessor.getToolchainContexts(target, config);
    // We don't actually use fromOptions in our implementation of
    // DependencyResolver but passing to avoid passing a null and since we have the information
    // anyway.
    deps =
        dependencyResolver
            .dependentNodeMap(
                new TargetAndConfiguration(target, config),
                /*aspect=*/ null,
                configConditions,
                toolchainContexts,
                DependencyResolver.shouldUseToolchainTransition(config, target),
                trimmingTransitionFactory);
    for (Map.Entry<DependencyKind, DependencyKey> attributeAndDep : deps.entries()) {
      DependencyKey dep = attributeAndDep.getValue();

      String dependencyName;
      if (DependencyKind.isToolchain(attributeAndDep.getKey())) {
        ToolchainDependencyKind tdk = (ToolchainDependencyKind) attributeAndDep.getKey();
        if (tdk.isDefaultExecGroup()) {
          dependencyName = "[toolchain dependency]";
        } else {
          dependencyName = String.format("[toolchain dependency: %s]", tdk.getExecGroupName());
        }
      } else {
        dependencyName = attributeAndDep.getKey().getAttribute().getName();
      }

      if (attributeAndDep.getValue().getTransition() == NoTransition.INSTANCE
          || attributeAndDep.getValue().getTransition() == NullTransition.INSTANCE) {
        resolved.add(ResolvedTransition.create(dep.getLabel(), ImmutableList.of(), dependencyName, attributeAndDep.getValue().getTransition().getName()));
        continue;
      }
      BuildOptions fromOptions = config.getOptions();
      // TODO(bazel-team): support transitions on Starlark-defined build flags. These require
      // Skyframe loading to get flag default values. See ConfigurationResolver.applyTransition
      // for an example of the required logic.
      Collection<BuildOptions> toOptions =
          dep.getTransition()
              .apply(TransitionUtil.restrict(dep.getTransition(), fromOptions), eventHandler)
              .values();
      resolved.add(ResolvedTransition.create(dep.getLabel(), toOptions, dependencyName, dep.getTransition().getName()));
    }
    return resolved;
  }
}
