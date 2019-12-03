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
package com.google.devtools.build.lib.runtime;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableRangeSet;
import com.google.common.collect.Range;
import com.google.common.collect.RangeSet;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.SpawnMetrics;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.clock.Clock;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;

/**
 * A component of the graph over which the critical path is computed. This may be identical to the
 * action graph, but does not have to be - it may also take into account individual spawns run as
 * part of an action.
 */
public class CriticalPathComponent {
  /**
   * Converts from nanos to millis since the epoch. In particular, note that {@link System#nanoTime}
   * does not specify any particular time reference but only notes that returned values are only
   * meaningful when taking in relation to each other.
   */
  public interface NanosToEpochConverter {
    /** Converts from nanos to millis since the epoch. */
    long toEpoch(long timeNanos);
  }

  /**
   * Creates a {@link NanosToEpochConverter} from clock by taking the current time in millis and the
   * current time in nanos to compute the appropriate offset.
   */
  public static NanosToEpochConverter fromClock(Clock clock) {
    long nowInMillis = clock.currentTimeMillis();
    long nowInNanos = clock.nanoTime();
    return (startNanos) -> nowInMillis - TimeUnit.NANOSECONDS.toMillis((nowInNanos - startNanos));
  }

  private final Action action;
  private final Artifact primaryOutput;

  /**
   * This range is the start and finish time of the component in nanos. During initialization of the
   * component, this value is a point in time where the lower and upper bounds are the same. That
   * would represent an elapsed time of zero until the component completes and the run time range is
   * updated.
   */
  @GuardedBy("this")
  private Range<Long> startAndFinishNanos;

  private final AtomicBoolean isRunning = new AtomicBoolean(false);
  private final AtomicBoolean phaseChange = new AtomicBoolean(false);

  /** Keeps a timeline of the critical path. */
  @GuardedBy("this")
  private ImmutableRangeSet<Long> timelineNanos;

  /** Spawn metrics for this action. */
  private SpawnMetrics phaseMaxMetrics = SpawnMetrics.EMPTY;
  private SpawnMetrics totalSpawnMetrics = SpawnMetrics.EMPTY;
  private Duration longestRunningTotalDuration = Duration.ZERO;

  /** Name of the runner used for the spawn. */
  @Nullable private String longestPhaseSpawnRunnerName;
  /** An unique identifier of the component for one build execution */
  private final int id;

  /** Child with the maximum critical path. */
  @Nullable private CriticalPathComponent child;

  /** Indication that there is at least one remote spawn metrics received. */
  private boolean remote = false;

  public CriticalPathComponent(int id, Action action, long startNanos) {
    this.id = id;
    this.action = Preconditions.checkNotNull(action);
    this.primaryOutput = action.getPrimaryOutput();
    this.startAndFinishNanos = Range.closed(startNanos, startNanos);
    this.timelineNanos = ImmutableRangeSet.of();
  }

  /**
   * Record the elapsed time in case the new duration is greater. This method could be called
   * multiple times in the following cases:
   *
   * <ol>
   *   <li>Shared actions run concurrently, and the one that really gets executed takes more time to
   *       send the finish event and the one that was a cache hit manages to send the event before.
   *   <li>An action gets rewound, and is later reattempted.
   * </ol>
   *
   * <p>In both these cases we overwrite the components' times if the later call specifies a greater
   * duration.
   *
   * <p>In the former case the logic is known to be incorrect, as other actions that depend on this
   * action will not necessarily use the correct getElapsedTimeNanos(). But we do not want to block
   * action execution because of this. So in certain conditions we might see another path as the
   * critical path.
   *
   * <p>In addition, in the case of sequential spawns, Aggregate the last phase's duration values
   * with the total spawn metrics. To make sure not to add the last phase's duration multiple times,
   * only add if there is duration and reset the phase metrics once it has been aggregated.
   */
  public synchronized void finishActionExecution(Range<Long> range) {
    if (isRunning.getAndSet(false)
        || range.upperEndpoint() - range.lowerEndpoint() > getElapsedTimeNanos()) {
      this.startAndFinishNanos = range;
      this.timelineNanos =
          child == null
              ? ImmutableRangeSet.of(this.startAndFinishNanos)
              : mergeTimeline(child, this.startAndFinishNanos);
    }

    // If the phaseMaxMetrics has Duration, then we want to aggregate it to the total.
    if (!this.phaseMaxMetrics.totalTime().isZero()) {
      this.totalSpawnMetrics =
          SpawnMetrics.aggregateMetrics(
              ImmutableList.of(this.totalSpawnMetrics, this.phaseMaxMetrics), true);
      this.phaseMaxMetrics = SpawnMetrics.EMPTY;
    }
  }

  @SuppressWarnings("ReferenceEquality")
  boolean isPrimaryOutput(Artifact possiblePrimaryOutput) {
    // We know that the keys in the CriticalPathComputer are exactly the values returned from
    // action.getPrimaryOutput(), so pointer equality is safe here.
    return possiblePrimaryOutput == primaryOutput;
  }

  /** The action for which we are storing the stat. */
  public final Action getAction() {
    return action;
  }

  /**
   * This is called by {@link CriticalPathComputer#actionStarted} to start running the action. The
   * three scenarios where this would occur is:
   *
   * <ol>
   *   <li>A new CriticalPathComponent is created and should start running.
   *   <li>A CriticalPathComponent has been created with discover inputs and beginning to execute.
   *   <li>An action was rewound and starts again.
   * </ol>
   */
  void startRunning() {
    isRunning.set(true);
  }

  public boolean isRunning() {
    return isRunning.get();
  }

  public String prettyPrintAction() {
    return action.prettyPrint();
  }

  @Nullable
  public Label getOwner() {
    ActionOwner owner = action.getOwner();
    if (owner != null && owner.getLabel() != null) {
      return owner.getLabel();
    }
    return null;
  }

  public String getMnemonic() {
    return action.getMnemonic();
  }

  /** An unique identifier of the component for one build execution */
  public int getId() {
    return id;
  }

  /**
   * An action can run multiple spawns. Those calls can be sequential or parallel. If action is a
   * sequence of calls we aggregate the SpawnMetrics of all the SpawnResults. If there are multiples
   * of the same action run in parallel, we keep the maximum runtime SpawnMetrics. We will also set
   * the longestPhaseSpawnRunnerName to the longest running spawn runner name across all phases if
   * it exists.
   */
  void addSpawnResult(SpawnMetrics metrics, @Nullable String runnerName, boolean wasRemote) {
    if (wasRemote) {
      this.remote = true;
    }
    if (phaseChange.getAndSet(false)) {
      this.totalSpawnMetrics =
          SpawnMetrics.aggregateMetrics(
              ImmutableList.of(this.totalSpawnMetrics, this.phaseMaxMetrics), true);
      this.phaseMaxMetrics = metrics;
    } else if (metrics.totalTime().compareTo(this.phaseMaxMetrics.totalTime()) > 0) {
      this.phaseMaxMetrics = metrics;
    }

    if (runnerName != null && metrics.totalTime().compareTo(this.longestRunningTotalDuration) > 0) {
      this.longestPhaseSpawnRunnerName = runnerName;
      this.longestRunningTotalDuration = metrics.totalTime();
    }
  }

  /** Set the phaseChange flag as true so we will aggregate incoming spawnMetrics. */
  void changePhase() {
    phaseChange.set(true);
  }

  /**
   * Returns total spawn metrics of the maximum (longest running) spawn metrics of all phases for
   * the execution of the action.
   */
  public SpawnMetrics getSpawnMetrics() {
    return totalSpawnMetrics;
  }

  /**
   * Returns name of the maximum runner used for the finished spawn which took most time (see {@link
   * #addSpawnResult(SpawnResult)}), null if no spawns have finished for this action (either there
   * are no spawns or we asked before any have finished).
   */
  @Nullable
  public String getLongestPhaseSpawnRunnerName() {
    return longestPhaseSpawnRunnerName;
  }

  /**
   * Update the child component if new dep has a longer runtime than the current child. The runtime
   * is determined by the union of current component's runtime range and the child or dep's runtime
   * range. Caller should ensure dep not running.
   */
  synchronized void addDepInfo(CriticalPathComponent dep, Range<Long> componentRuntimeRange) {
    if (child == null) {
      child = dep;
      return;
    }
    long depRuntime = aggregateTimelineNanos(mergeTimeline(dep, componentRuntimeRange));
    long childRuntime = aggregateTimelineNanos(mergeTimeline(child, componentRuntimeRange));
    if (depRuntime > childRuntime) {
      child = dep;
    }
  }

  public synchronized long getStartTimeNanos() {
    return startAndFinishNanos.lowerEndpoint();
  }

  public synchronized long getStartTimeMillisSinceEpoch(NanosToEpochConverter converter) {
    return converter.toEpoch(startAndFinishNanos.lowerEndpoint());
  }

  public Duration getElapsedTime() {
    return Duration.ofNanos(getElapsedTimeNanos());
  }

  long getElapsedTimeNanos() {
    if (isRunning.get()) {
      // It can happen that we're being asked to compute a critical path even though the build was
      // interrupted. In that case, we may not have gotten an action completion event. We don't have
      // access to the clock from here, so we have to return 0.
      // Note that the critical path never includes interrupted actions, so getAggregatedElapsedTime
      // does not get called in this state.
      // If we want the critical path to contain partially executed actions in a case of interrupt,
      // then we need to tell the critical path computer that the build was interrupt, and let it
      // artifically mark all such actions as done.
      return 0;
    }
    return getElapsedTimeNanosNoCheck();
  }

  /** To be used only in debugging: skips state invariance checks to avoid crash-looping. */
  @VisibleForTesting
  Duration getElapsedTimeNoCheck() {
    return Duration.ofNanos(getElapsedTimeNanosNoCheck());
  }

  private synchronized long getElapsedTimeNanosNoCheck() {
    return startAndFinishNanos.upperEndpoint() - startAndFinishNanos.lowerEndpoint();
  }

  /**
   * Returns the current critical path for the action.
   *
   * <p>Critical path is defined as the longest running path of intersecting critical path
   * components.
   */
  synchronized Duration getAggregatedElapsedTime() {
    Preconditions.checkState(!isRunning.get(), "Still running %s", this);
    return Duration.ofNanos(aggregateTimelineNanos(timelineNanos));
  }

  /** Returns the total aggregated runtime based on the passed in timeline. */
  private static long aggregateTimelineNanos(RangeSet<Long> timeline) {
    long totalNanos = 0;
    for (Range<Long> timeRange : timeline.asRanges()) {
      totalNanos += timeRange.upperEndpoint() - timeRange.lowerEndpoint();
    }
    return totalNanos;
  }

  /** Returns the union of the dep timeline and range if dep exists, otherwise just return range. */
  private static ImmutableRangeSet<Long> mergeTimeline(
      CriticalPathComponent dep, Range<Long> range) {
    return dep.getTimeline().union(ImmutableRangeSet.of(range));
  }

  /** The timeline of the component is only ready once the component has completed running */
  private synchronized ImmutableRangeSet<Long> getTimeline() {
    Preconditions.checkState(!isRunning.get(), "Still running %s", this);
    return timelineNanos;
  }

  /**
   * Get the child critical path component.
   *
   * <p>The component dependency with the maximum total critical path time.
   */
  @Nullable
  public CriticalPathComponent getChild() {
    return child;
  }

  /** Returns a string representation of the action. Only for use in crash messages and the like. */
  private String getActionString() {
    return action.prettyPrint();
  }

  /** Returns a user readable representation of the critical path stats with all the details. */
  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    String currentTime = "still running";
    if (!isRunning.get()) {
      currentTime = String.format("%.2f", getElapsedTimeNoCheck().toMillis() / 1000.0) + "s";
    }
    sb.append(currentTime);
    if (remote) {
      sb.append(", Remote ");
      sb.append(getSpawnMetrics().toString(getElapsedTimeNoCheck(), /* summary= */ false));
    }
    sb.append(" ");
    sb.append(getActionString());
    return sb.toString();
  }
}

