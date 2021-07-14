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

package com.google.devtools.build.lib.actions.cache;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.io.BaseEncoding;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.cache.Protos.ActionCacheStatistics;
import com.google.devtools.build.lib.actions.cache.Protos.ActionCacheStatistics.MissReason;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadCompatible;
import com.google.devtools.build.lib.skyframe.TreeArtifactValue;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * An interface defining a cache of already-executed Actions.
 *
 * <p>This class' naming is misleading; it doesn't cache the actual actions, but it stores a
 * fingerprint of the action state (ie. a hash of the input and output files on disk), so
 * we can tell if we need to rerun an action given the state of the file system.
 *
 * <p>Each action entry uses one of its output paths as a key (after conversion
 * to the string).
 */
@ThreadCompatible
public interface ActionCache {

  /**
   * Updates the cache entry for the specified key.
   */
  void put(String key, ActionCache.Entry entry);

  /**
   * Returns the corresponding cache entry for the specified key, if any, or
   * null if not found.
   */
  ActionCache.Entry get(String key);

  /**
   * Removes entry from cache
   */
  void remove(String key);

  /**
   * An entry in the ActionCache that contains all action input and output
   * artifact paths and their metadata plus action key itself.
   *
   * Cache entry operates under assumption that once it is fully initialized
   * and getFileDigest() method is called, it becomes logically immutable (all methods
   * will continue to return same result regardless of internal data transformations).
   */
  final class Entry {
    /** Unique instance to represent a corrupted cache entry. */
    public static final ActionCache.Entry CORRUPTED =
        new ActionCache.Entry(null, ImmutableMap.<String, String>of(), false);

    private final String actionKey;
    @Nullable
    // Null iff the corresponding action does not do input discovery.
    private final List<String> files;
    // If null, digest is non-null and the entry is immutable.
    private Map<String, FileArtifactValue> mdMap;
    private byte[] digest;
    private final byte[] usedClientEnvDigest;
    private final Map<String, FileArtifactValue> outputFileMetadata;
    private final Map<String, SerializableTreeArtifactValue> outputTreeMetadata;

    /**
     * The metadata for output tree that can be serialized.
     *
     * <p>We can't serialize {@link TreeArtifactValue} directly as it contains some objects that can
     * not be serialized.
     */
    @AutoValue
    abstract static class SerializableTreeArtifactValue {
      public static SerializableTreeArtifactValue create(
          ImmutableMap<String, FileArtifactValue> childData) {
        return new AutoValue_ActionCache_Entry_SerializableTreeArtifactValue(childData);
      }

      // A map from parentRelativePath to the file metadata
      abstract ImmutableMap<String, FileArtifactValue> childData();
    }

    public Entry(String key, Map<String, String> usedClientEnv, boolean discoversInputs) {
      actionKey = key;
      this.usedClientEnvDigest = MetadataDigestUtils.fromEnv(usedClientEnv);
      files = discoversInputs ? new ArrayList<String>() : null;
      mdMap = new HashMap<>();
      outputFileMetadata = new HashMap<>();
      outputTreeMetadata = new HashMap<>();
    }

    public Entry(
        String key,
        byte[] usedClientEnvDigest,
        @Nullable List<String> files,
        byte[] digest,
        Map<String, FileArtifactValue> outputFileMetadata,
        Map<String, SerializableTreeArtifactValue> outputTreeMetadata) {
      actionKey = key;
      this.usedClientEnvDigest = usedClientEnvDigest;
      this.files = files;
      this.digest = digest;
      mdMap = null;
      this.outputFileMetadata = outputFileMetadata;
      this.outputTreeMetadata = outputTreeMetadata;
    }

    /** Adds metadata of an output file */
    public void addOutputFile(Artifact output, FileArtifactValue value) {
      checkArgument(
          !output.isTreeArtifact() && !output.isChildOfDeclaredDirectory(),
          "Must use addOutputTree to save tree artifacts and their children: %s",
          output);
      checkState(mdMap != null);
      checkState(!isCorrupted());
      checkState(digest == null);

      String execPath = output.getExecPathString();
      outputFileMetadata.put(execPath, value);
      mdMap.put(execPath, value);
    }

    /** Gets metadata of an output file */
    public FileArtifactValue getOutputFile(Artifact output) {
      checkState(!isCorrupted());
      return outputFileMetadata.get(output.getExecPathString());
    }

    Map<String, FileArtifactValue> getOutputFiles() {
      return outputFileMetadata;
    }

    /** Adds metadata of an output tree */
    public void addOutputTree(SpecialArtifact output, TreeArtifactValue value) {
      checkArgument(output.isTreeArtifact(), "artifact must be a tree artifact: %s", output);
      checkState(mdMap != null);
      checkState(!isCorrupted());
      checkState(digest == null);

      String execPath = output.getExecPathString();
      ImmutableMap.Builder<String, FileArtifactValue> childDataBuilder = ImmutableMap.builder();
      for (Map.Entry<TreeFileArtifact, FileArtifactValue> entry :
          value.getChildValues().entrySet()) {
        childDataBuilder.put(entry.getKey().getTreeRelativePathString(), entry.getValue());
      }

      outputTreeMetadata.put(
          execPath, SerializableTreeArtifactValue.create(childDataBuilder.build()));
      mdMap.put(execPath, value.getMetadata());
    }

    /** Gets metadata of an output tree */
    public TreeArtifactValue getOutputTree(SpecialArtifact output) {
      checkState(!isCorrupted());

      SerializableTreeArtifactValue value = outputTreeMetadata.get(output.getExecPathString());
      if (value == null) {
        return null;
      }

      TreeArtifactValue.Builder builder = TreeArtifactValue.newBuilder(output);
      for (Map.Entry<String, FileArtifactValue> entry : value.childData().entrySet()) {
        builder.putChild(
            TreeFileArtifact.createTreeOutput(output, entry.getKey()), entry.getValue());
      }
      return builder.build();
    }

    Map<String, SerializableTreeArtifactValue> getOutputTrees() {
      return outputTreeMetadata;
    }

    /** Adds metadata of an input file */
    public void addInputFile(Artifact input, FileArtifactValue value, boolean saveExecPath) {
      checkState(mdMap != null);
      checkState(!isCorrupted());
      checkState(digest == null);

      String execPath = input.getExecPathString();
      if (discoversInputs() && saveExecPath) {
        files.add(execPath);
      }
      mdMap.put(execPath, value);
    }

    /**
     * @return action key string.
     */
    public String getActionKey() {
      return actionKey;
    }

    /** @return the effectively used client environment */
    public byte[] getUsedClientEnvDigest() {
      return usedClientEnvDigest;
    }

    /**
     * Returns the combined digest of the action's inputs and outputs.
     *
     * <p>This may compress the data into a more compact representation, and makes the object
     * immutable.
     */
    public byte[] getFileDigest() {
      if (digest == null) {
        digest = MetadataDigestUtils.fromMetadata(mdMap);
        mdMap = null;
      }
      return digest;
    }

    /**
     * Returns true if this cache entry is corrupted and should be ignored.
     */
    public boolean isCorrupted() {
      return this == CORRUPTED;
    }

    /**
     * @return stored path strings, or null if the corresponding action does not discover inputs.
     */
    public Collection<String> getPaths() {
      return discoversInputs() ? files : ImmutableList.<String>of();
    }

    /**
     * @return whether the corresponding action discovers input files dynamically.
     */
    public boolean discoversInputs() {
      return files != null;
    }

    private static final String formatDigest(byte[] digest) {
      return BaseEncoding.base16().lowerCase().encode(digest);
    }

    @Override
    public String toString() {
      StringBuilder builder = new StringBuilder();
      builder.append("      actionKey = ").append(actionKey).append("\n");
      builder
          .append("      usedClientEnvKey = ")
          .append(formatDigest(usedClientEnvDigest))
          .append("\n");
      builder.append("      digestKey = ");
      if (digest == null) {
        builder
            .append(formatDigest(MetadataDigestUtils.fromMetadata(mdMap)))
            .append(" (from mdMap)\n");
      } else {
        builder.append(formatDigest(digest)).append("\n");
      }

      if (discoversInputs()) {
        List<String> fileInfo = Lists.newArrayListWithCapacity(files.size());
        fileInfo.addAll(files);
        Collections.sort(fileInfo);
        for (String info : fileInfo) {
          builder.append("      ").append(info).append("\n");
        }
      }

      for (Map.Entry<String, FileArtifactValue> entry : outputFileMetadata.entrySet()) {
        builder
            .append("      ")
            .append(entry.getKey())
            .append(" = ")
            .append(entry.getValue().toString())
            .append("\n");
      }

      for (Map.Entry<String, SerializableTreeArtifactValue> entry : outputTreeMetadata.entrySet()) {
        SerializableTreeArtifactValue metadata = entry.getValue();
        builder
            .append("      ")
            .append(entry.getKey())
            .append(" = ")
            .append(metadata.toString())
            .append("\n");
      }

      return builder.toString();
    }
  }

  /**
   * Give persistent cache implementations a notification to write to disk.
   * @return size in bytes of the serialized cache.
   */
  long save() throws IOException;

  /** Clear the action cache, closing all opened file handle. */
  void clear();

  /**
   * Dumps action cache content into the given PrintStream.
   */
  void dump(PrintStream out);

  /** Accounts one cache hit. */
  void accountHit();

  /** Accounts one cache miss for the given reason. */
  void accountMiss(MissReason reason);

  /**
   * Populates the given builder with statistics.
   *
   * <p>The extracted values are not guaranteed to be a consistent snapshot of the metrics tracked
   * by the action cache. Therefore, even if it is safe to call this function at any point in time,
   * this should only be called once there are no actions running.
   */
  void mergeIntoActionCacheStatistics(ActionCacheStatistics.Builder builder);

  /** Resets the current statistics to zero. */
  void resetStatistics();
}
