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

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.SpawnMetrics;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.clock.Clock;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadCompatible;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * A component of the graph over which the critical path is computed. This may be identical to the
 * action graph, but does not have to be - it may also take into account individual spawns run as
 * part of an action.
 */
@ThreadCompatible
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

  // These two fields are values of BlazeClock.nanoTime() at the relevant points in time.
  private long startNanos;
  private long finishNanos = 0;
  private volatile boolean isRunning = true;

  /** We keep here the critical path time for the most expensive child. */
  private long childAggregatedElapsedTime = 0;

  private final Action action;
  private final Artifact primaryOutput;

  /** Spawn metrics for this action. */
  private SpawnMetrics phaseMaxMetrics = SpawnMetrics.EMPTY;

  private SpawnMetrics totalSpawnMetrics = SpawnMetrics.EMPTY;
  private Duration longestRunningTotalDuration = Duration.ZERO;
  private boolean phaseChange;

  /** Name of the runner used for the spawn. */
  @Nullable private String longestPhaseSpawnRunnerName;
  /** An unique identifier of the component for one build execution */
  private final int id;

  /** Child with the maximum critical path. */
  @Nullable private CriticalPathComponent child;

  public CriticalPathComponent(int id, Action action, long startNanos) {
    this.id = id;
    this.action = Preconditions.checkNotNull(action);
    this.primaryOutput = action.getPrimaryOutput();
    this.startNanos = startNanos;
    this.phaseChange = false;
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
  public synchronized void finishActionExecution(long startNanos, long finishNanos) {
    if (isRunning || finishNanos - startNanos > getElapsedTimeNanos()) {
      this.startNanos = startNanos;
      this.finishNanos = finishNanos;
      isRunning = false;
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

  public boolean isRunning() {
    return isRunning;
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
   * An action can run multiple spawns. Those calls can be sequential or parallel. If it is a
   * sequence of calls we should aggregate the metrics by collecting all the SpawnResults, if they
   * are run in parallel we should keep the maximum runtime spawn. We will also set the
   * longestPhaseSpawnRunnerName to the longest running spawn runner name across all phases.
   */
  void addSpawnResult(SpawnResult spawnResult) {
    if (this.phaseChange) {
      this.totalSpawnMetrics =
          SpawnMetrics.aggregateMetrics(
              ImmutableList.of(this.totalSpawnMetrics, this.phaseMaxMetrics), true);
      this.phaseMaxMetrics = spawnResult.getMetrics();
      this.phaseChange = false;
    } else if (spawnResult.getMetrics().totalTime().compareTo(this.phaseMaxMetrics.totalTime())
        > 0) {
      this.phaseMaxMetrics = spawnResult.getMetrics();
    }

    if (spawnResult.getMetrics().totalTime().compareTo(this.longestRunningTotalDuration) > 0) {
      this.longestPhaseSpawnRunnerName = spawnResult.getRunnerName();
      this.longestRunningTotalDuration = spawnResult.getMetrics().totalTime();
    }
  }

  /** Set the phaseChange flag as true so we will aggregate incoming spawnMetrics. */
  void changePhase() {
    this.phaseChange = true;
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
   * Add statistics for one dependency of this action. Caller should ensure {@code dep} not running.
   */
  synchronized void addDepInfo(CriticalPathComponent dep) {
    long childAggregatedWallTime = dep.getAggregatedElapsedTimeNanos();
    // Replace the child if its critical path had the maximum elapsed time.
    if (child == null || childAggregatedWallTime > this.childAggregatedElapsedTime) {
      this.childAggregatedElapsedTime = childAggregatedWallTime;
      child = dep;
    }
  }

  public long getStartTimeNanos() {
    return startNanos;
  }

  public long getStartTimeMillisSinceEpoch(NanosToEpochConverter converter) {
    return converter.toEpoch(startNanos);
  }

  public Duration getElapsedTime() {
    return Duration.ofNanos(getElapsedTimeNanos());
  }

  long getElapsedTimeNanos() {
    if (isRunning) {
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
  private Duration getElapsedTimeNoCheck() {
    return Duration.ofNanos(getElapsedTimeNanosNoCheck());
  }

  private long getElapsedTimeNanosNoCheck() {
    return finishNanos - startNanos;
  }

  /**
   * Returns the current critical path for the action.
   *
   * <p>Critical path is defined as : action_execution_time + max(child_critical_path).
   */
  Duration getAggregatedElapsedTime() {
    return Duration.ofNanos(getAggregatedElapsedTimeNanos());
  }

  private long getAggregatedElapsedTimeNanos() {
    Preconditions.checkState(!isRunning, "Still running %s", this);
    return getElapsedTimeNanos() + childAggregatedElapsedTime;
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
    if (!isRunning) {
      currentTime = String.format("%.2f", getElapsedTimeNoCheck().toMillis() / 1000.0) + "s";
    }
    sb.append(currentTime);
    sb.append(", Remote ");
    sb.append(getSpawnMetrics().toString(getElapsedTimeNoCheck(), /* summary= */ false));
    sb.append(" ");
    sb.append(getActionString());
    return sb.toString();
  }
}

