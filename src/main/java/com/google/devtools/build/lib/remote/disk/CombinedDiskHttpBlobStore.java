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
package com.google.devtools.build.lib.remote.disk;

import build.bazel.remote.execution.v2.ActionResult;
import build.bazel.remote.execution.v2.Digest;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.devtools.build.lib.remote.Chunker;
import com.google.devtools.build.lib.remote.common.SimpleBlobStore;
import com.google.devtools.build.lib.remote.merkletree.MerkleTree;
import com.google.devtools.build.lib.vfs.Path;
import com.google.protobuf.Message;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A {@link SimpleBlobStore} implementation combining two blob stores. A local disk blob store and a
 * remote blob store. If a blob isn't found in the first store, the second store is used, and the
 * blob added to the first. Put puts the blob on both stores.
 */
public final class CombinedDiskHttpBlobStore implements SimpleBlobStore {
  private static final Logger logger = Logger.getLogger(CombinedDiskHttpBlobStore.class.getName());

  private final SimpleBlobStore remoteCache;
  private final OnDiskBlobStore diskCache;

  public CombinedDiskHttpBlobStore(OnDiskBlobStore diskCache, SimpleBlobStore remoteCache) {
    this.diskCache = Preconditions.checkNotNull(diskCache);
    this.remoteCache = Preconditions.checkNotNull(remoteCache);
  }

  @Override
  public boolean contains(String key) {
    return diskCache.contains(key);
  }

  @Override
  public boolean containsActionResult(String key) {
    return diskCache.containsActionResult(key);
  }

  @Override
  public void put(String key, Digest digest, long length, Chunker chunker, InputStream in)
      throws IOException, InterruptedException {
    diskCache.put(key, digest, length, chunker, in);
    try (InputStream inFile = diskCache.toPath(key, /* actionResult= */ false).getInputStream()) {
      remoteCache.put(key, digest, length, chunker, inFile);
    }
  }

  @Override
  public void putActionResult(Digest digest, ActionResult actionResult) throws IOException, InterruptedException {
    diskCache.putActionResult(digest, actionResult);
    remoteCache.putActionResult(digest, actionResult);
  }

  @Override
  public void close() {
    diskCache.close();
    remoteCache.close();
  }

  @Override
  public ListenableFuture<Boolean> get(String key, Digest digest, OutputStream out) {
    return get(key, digest, out, /* actionResult= */ false);
  }

  private ListenableFuture<Boolean> get(String key, Digest digest, OutputStream out, boolean actionResult) {
    boolean foundOnDisk =
        actionResult ? diskCache.containsActionResult(key) : diskCache.contains(key);

    if (foundOnDisk) {
      return getFromCache(diskCache, digest, out, actionResult);
    } else {
      return getFromRemoteAndSaveToDisk(digest, out, actionResult);
    }
  }

  @Override
  public ListenableFuture<Boolean> getActionResult(Digest digest, OutputStream out) {
    return get(digest.getHash(), digest, out, /* actionResult= */ true);
  }

  private ListenableFuture<Boolean> getFromRemoteAndSaveToDisk(
      Digest digest, OutputStream out, boolean actionResult) {
    // Write a temporary file first, and then rename, to avoid data corruption in case of a crash.
    Path temp = diskCache.toPath(UUID.randomUUID().toString(), /* actionResult= */ false);

    OutputStream tempOut;
    try {
      tempOut = temp.getOutputStream();
    } catch (IOException e) {
      return Futures.immediateFailedFuture(e);
    }
    ListenableFuture<Boolean> chained =
        Futures.transformAsync(
            getFromCache(remoteCache, digest, tempOut, actionResult),
            (found) -> {
              if (!found) {
                return Futures.immediateFuture(false);
              } else {
                saveToDiskCache(digest.getHash(), temp, actionResult);
                return getFromCache(diskCache, digest, out, actionResult);
              }
            },
            MoreExecutors.directExecutor());
    chained.addListener(
        () -> {
          try {
            tempOut.close();
          } catch (IOException e) {
            // not sure what to do here, we either are here because of another exception being
            // thrown, or we have successfully used the file we are trying (and failing) to close
            logger.log(Level.WARNING, "Failed to close temporary file on get", e);
          }
        },
        MoreExecutors.directExecutor());
    return chained;
  }

  private void saveToDiskCache(String key, Path temp, boolean actionResult) throws IOException {
    Path target = diskCache.toPath(key, actionResult);
    // TODO(ulfjack): Fsync temp here before we rename it to avoid data loss in the
    // case of machine crashes (the OS may reorder the writes and the rename).
    temp.renameTo(target);
  }

  private ListenableFuture<Boolean> getFromCache(
      SimpleBlobStore blobStore, Digest digest, OutputStream tempOut, boolean actionResult) {
    if (!actionResult) {
      return blobStore.get(digest.getHash(), digest, tempOut);
    } else {
      return blobStore.getActionResult(digest, tempOut);
    }
  }

  @Override
  public void ensureInputsPresent(
      MerkleTree merkleTree, Map<Digest, Message> additionalInputs, Path execRoot) {
    throw new UnsupportedOperationException("Combined Disk HTTP Caching does not use this method.");
  }

  @Override
  public ImmutableSet<Digest> getMissingDigests(Iterable<Digest> digests)
      throws IOException, InterruptedException {
    throw new UnsupportedOperationException("Combined Disk HTTP Caching does not use this method.");
  }
}
