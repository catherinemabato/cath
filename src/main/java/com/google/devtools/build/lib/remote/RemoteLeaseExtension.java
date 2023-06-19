package com.google.devtools.build.lib.remote;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.devtools.build.lib.remote.util.Utils.getFromFuture;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.RequestMetadata;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterators;
import com.google.common.flogger.GoogleLogger;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionCacheUtils;
import com.google.devtools.build.lib.actions.ActionLookupData;
import com.google.devtools.build.lib.actions.ActionLookupValue;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.FileArtifactValue.RemoteFileArtifactValue;
import com.google.devtools.build.lib.actions.cache.ActionCache;
import com.google.devtools.build.lib.actions.cache.ActionCache.Entry;
import com.google.devtools.build.lib.actions.cache.ActionCache.Entry.SerializableTreeArtifactValue;
import com.google.devtools.build.lib.concurrent.ExecutorUtil;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.remote.LeaseService.LeaseExtension;
import com.google.devtools.build.lib.remote.common.RemoteActionExecutionContext;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.remote.util.TracingMetadataUtils;
import com.google.devtools.build.lib.skyframe.ActionExecutionValue;
import com.google.devtools.build.lib.skyframe.SkyFunctions;
import com.google.devtools.build.skyframe.MemoizingEvaluator;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import javax.annotation.Nullable;

public class RemoteLeaseExtension implements LeaseExtension {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();
  private static final Predicate<SkyKey> ACTION_FILTER =
      SkyFunctionName.functionIs(SkyFunctions.ACTION_EXECUTION);

  private final ScheduledExecutorService scheduledExecutor =
      Executors.newSingleThreadScheduledExecutor(
          new ThreadFactoryBuilder().setNameFormat("lease-extension-%d").build());

  private final MemoizingEvaluator memoizingEvaluator;
  @Nullable
  private final ActionCache actionCache;
  private final RemoteCache remoteCache;
  private final Duration remoteCacheTtl;
  private final RemoteActionExecutionContext context;

  public RemoteLeaseExtension(
      MemoizingEvaluator memoizingEvaluator,
      @Nullable ActionCache actionCache,
      String buildRequestId,
      String commandId,
      RemoteCache remoteCache,
      Duration remoteCacheTtl) {
    this.memoizingEvaluator = memoizingEvaluator;
    this.actionCache = actionCache;
    this.remoteCache = remoteCache;
    this.remoteCacheTtl = remoteCacheTtl;
    RequestMetadata requestMetadata =
        TracingMetadataUtils.buildMetadata(buildRequestId, commandId, "lease-extension", null);
    this.context = RemoteActionExecutionContext.create(requestMetadata);
  }

  @Override
  public void start() {
    // Immediately extend leases for outputs that are already known to skyframe. For clean build,
    // the set of outputs is empty. For incremental build, it contains outputs that were not
    // invalidated after skyframe's dirtiness check.
    var unused = scheduledExecutor.schedule(this::extendLeases, 0, MILLISECONDS);
  }

  private void extendLeases() {
    var valuesMap = memoizingEvaluator.getValues();
    // We will extend leases for all known outputs so the earliest time when one output could be
    // expired is (now + ttl).
    var earliestExpiration = Instant.now().plus(remoteCacheTtl);

    try (var silentCloseable = Profiler.instance().profile("Lease extension")) {
      for (var entry : valuesMap.entrySet()) {
        var key = entry.getKey();
        var value = entry.getValue();
        if (value != null && ACTION_FILTER.test(key)) {
          var action = getActionFromSkyKey(key);
          var actionExecutionValue = (ActionExecutionValue) value;
          if (actionHasExpiringOutputs(actionExecutionValue)) {
            var expireAtEpochMilli = Instant.now().toEpochMilli() + remoteCacheTtl.toMillis();
            try (var silentCloseable1 = Profiler.instance().profile(action.describe())) {
              extendLeaseForAction(action, actionExecutionValue, expireAtEpochMilli);
            }
          }
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return;
    } catch (Throwable e) {
      logger.atWarning().withCause(e).log("Failed to extend the lease");
    }

    // Only extend the leases again when one of the outputs is about to expire.
    var now = Instant.now();
    Duration delay;
    if (earliestExpiration.isAfter(now)) {
      delay = Duration.between(now, earliestExpiration);
    } else {
      delay = Duration.ZERO;
    }
    var unused = scheduledExecutor.schedule(this::extendLeases, delay.toMillis(), MILLISECONDS);
  }

  private static boolean isRemoteMetadataWithTTL(FileArtifactValue metadata) {
    return metadata.isRemote() && ((RemoteFileArtifactValue) metadata).getExpireAtEpochMilli() >= 0;
  }

  /** Returns {@code true} iff the outputs of the action */
  private boolean actionHasExpiringOutputs(ActionExecutionValue actionExecutionValue) {
    for (var metadata : actionExecutionValue.getAllFileValues().values()) {
      if (isRemoteMetadataWithTTL(metadata)) {
        return true;
      }
    }

    for (var treeMetadata : actionExecutionValue.getAllTreeArtifactValues().values()) {
      for (var metadata : treeMetadata.getChildValues().values()) {
        if (isRemoteMetadataWithTTL(metadata)) {
          return true;
        }
      }
    }

    return false;
  }

  private void extendLeaseForAction(
      Action action, ActionExecutionValue actionExecutionValue, long expireAtEpochMilli)
      throws IOException, InterruptedException {
    ImmutableSet<Digest> missingDigests ;
    try (var silentCloseable = Profiler.instance().profile("findMissingDigests")) {
      // We assume remote server will extend the leases for all referenced blobs by of a
      // FindMissingBlobs call.
      missingDigests =
          getFromFuture(
              remoteCache.findMissingDigests(
                  context, allExpiringOutputDigests(actionExecutionValue)));
    }

    var token = getActionCacheToken(action);
    for (var fileEntry : actionExecutionValue.getAllFileValues().entrySet()) {
      var artifact = fileEntry.getKey();
      var metadata = fileEntry.getValue();
      // Only extend the lease for the remote output if it is still alive remotely.
      if (metadata.isRemote()
          && !missingDigests.contains(
              DigestUtil.buildDigest(metadata.getDigest(), metadata.getSize()))) {
        ((RemoteFileArtifactValue) metadata).extendExpireAtEpochMilli(expireAtEpochMilli);
        if (token != null) {
          token.extendOutputFile(artifact, expireAtEpochMilli);
        }
      }
    }

    for (var treeEntry : actionExecutionValue.getAllTreeArtifactValues().entrySet()) {
      var tree = (SpecialArtifact) treeEntry.getKey();
      var treeMetadata = treeEntry.getValue();
      for (var treeFileEntry : treeMetadata.getChildValues().entrySet()) {
        var treeFile = treeFileEntry.getKey();
        var metadata = treeFileEntry.getValue();
        // Only extend the lease for the remote output if it is still alive remotely.
        if (metadata.isRemote()
            && !missingDigests.contains(
                DigestUtil.buildDigest(metadata.getDigest(), metadata.getSize()))) {
          ((RemoteFileArtifactValue) metadata).extendExpireAtEpochMilli(expireAtEpochMilli);
          if (token != null) {
            token.extendOutputTreeFile(tree, treeFile, expireAtEpochMilli);
          }
        }
      }
    }

    if (actionCache != null && token != null && token.dirty) {
      // Only update the action cache entry if the token was updated because it usually involves
      // serialization.
      actionCache.put(token.key, token.entry);
    }
  }

  @Override
  public void stop() {
    if (ExecutorUtil.uninterruptibleShutdownNow(scheduledExecutor)) {
      Thread.currentThread().interrupt();
    }
  }

  private static Digest buildDigest(FileArtifactValue metadata) {
    return DigestUtil.buildDigest(metadata.getDigest(), metadata.getSize());
  }

  private Iterable<Digest> allExpiringOutputDigests(ActionExecutionValue actionExecutionValue) {
    return () -> {
      var files =
          actionExecutionValue.getAllFileValues().values().stream()
              .filter(RemoteLeaseExtension::isRemoteMetadataWithTTL)
              .map(RemoteLeaseExtension::buildDigest)
              .iterator();
      var treeFiles =
          actionExecutionValue.getAllTreeArtifactValues().values().stream()
              .flatMap(
                  tree ->
                      tree.getChildValues().values().stream()
                          .filter(RemoteLeaseExtension::isRemoteMetadataWithTTL)
                          .map(RemoteLeaseExtension::buildDigest))
              .iterator();
      return Iterators.concat(files, treeFiles);
    };
  }

  private Action getActionFromSkyKey(SkyKey key) throws InterruptedException {
    var actionLookupData = (ActionLookupData) key.argument();
    var actionLookupValue =
        (ActionLookupValue)
            checkNotNull(
                memoizingEvaluator.getExistingValue(actionLookupData.getActionLookupKey().toKey()));
    return actionLookupValue.getAction(actionLookupData.getActionIndex());
  }

  @Nullable
  private ActionCacheToken getActionCacheToken(Action action) {
    if (actionCache != null) {
      var actionCacheEntryWithKey = ActionCacheUtils.getCacheEntryWithKey(actionCache, action);
      if (actionCacheEntryWithKey != null) {
        return new ActionCacheToken(
            actionCacheEntryWithKey.getKey(), actionCacheEntryWithKey.getValue());
      }
    }

    return null;
  }

  private static class ActionCacheToken {
    final String key;
    final ActionCache.Entry entry;
    private boolean dirty;

    ActionCacheToken(String key, Entry entry) {
      this.key = key;
      this.entry = entry;
    }

    void extendOutputFile(Artifact artifact, long expireAtEpochMilli) {
      var metadata = entry.getOutputFile(artifact);
      if (metadata != null) {
        metadata.extendExpireAtEpochMilli(expireAtEpochMilli);
        dirty = true;
      }
    }

    void extendOutputTreeFile(
        SpecialArtifact tree, TreeFileArtifact treeFile, long expireAtEpochMilli) {
      SerializableTreeArtifactValue treeMetadata = entry.getOutputTree(tree);
      if (treeMetadata != null) {
        var metadata = treeMetadata.childValues().get(treeFile.getTreeRelativePathString());
        if (metadata != null) {
          metadata.extendExpireAtEpochMilli(expireAtEpochMilli);
          dirty = true;
        }
      }
    }
  }
}
