// Copyright 2021 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.remote;

import static com.google.common.base.Throwables.throwIfInstanceOf;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static com.google.devtools.build.lib.remote.util.RxFutures.toCompletable;
import static com.google.devtools.build.lib.remote.util.RxFutures.toSingle;
import static com.google.devtools.build.lib.remote.util.RxUtils.mergeBulkTransfer;
import static com.google.devtools.build.lib.remote.util.RxUtils.toTransferResult;

import build.bazel.remote.execution.v2.Action;
import build.bazel.remote.execution.v2.ActionResult;
import build.bazel.remote.execution.v2.CacheCapabilities;
import build.bazel.remote.execution.v2.Command;
import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.Directory;
import build.bazel.remote.execution.v2.OutputSymlink;
import build.bazel.remote.execution.v2.SymlinkAbsolutePathStrategy;
import build.bazel.remote.execution.v2.Tree;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.devtools.build.lib.actions.ActionExecutionMetadata;
import com.google.devtools.build.lib.actions.ActionUploadFinishedEvent;
import com.google.devtools.build.lib.actions.ActionUploadStartedEvent;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.UserExecException;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.remote.common.RemoteActionExecutionContext;
import com.google.devtools.build.lib.remote.common.RemoteCacheClient;
import com.google.devtools.build.lib.remote.common.RemoteCacheClient.ActionKey;
import com.google.devtools.build.lib.remote.common.RemotePathResolver;
import com.google.devtools.build.lib.remote.options.RemoteOptions;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.remote.util.RxUtils;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.RemoteExecution;
import com.google.devtools.build.lib.server.FailureDetails.RemoteExecution.Code;
import com.google.devtools.build.lib.util.io.FileOutErr;
import com.google.devtools.build.lib.vfs.Dirent;
import com.google.devtools.build.lib.vfs.FileStatus;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.Timestamp;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/** UploadManifest adds output metadata to a {@link ActionResult}. */
public class UploadManifest {

  private final DigestUtil digestUtil;
  private final RemotePathResolver remotePathResolver;
  private final ActionResult.Builder result;
  private final boolean followSymlinks;
  private final boolean allowDanglingSymlinks;
  private final boolean allowAbsoluteSymlinks;
  private final Map<Digest, Path> digestToFile = new HashMap<>();
  private final Map<Digest, ByteString> digestToBlobs = new HashMap<>();
  @Nullable private ActionKey actionKey;
  private Digest stderrDigest;
  private Digest stdoutDigest;

  public static UploadManifest create(
      RemoteOptions remoteOptions,
      CacheCapabilities cacheCapabilities,
      DigestUtil digestUtil,
      RemotePathResolver remotePathResolver,
      ActionKey actionKey,
      Action action,
      Command command,
      Collection<Path> outputFiles,
      FileOutErr outErr,
      int exitCode,
      Instant startTime,
      int wallTimeInMs)
      throws ExecException, IOException {
    ActionResult.Builder result = ActionResult.newBuilder();
    result.setExitCode(exitCode);

    UploadManifest manifest =
        new UploadManifest(
            digestUtil,
            remotePathResolver,
            result,
            /* followSymlinks= */ !remoteOptions.incompatibleRemoteSymlinks,
            /* allowDanglingSymlinks= */ remoteOptions.incompatibleRemoteDanglingSymlinks,
            /* allowAbsoluteSymlinks= */ cacheCapabilities
                .getSymlinkAbsolutePathStrategy()
                .equals(SymlinkAbsolutePathStrategy.Value.ALLOWED));
    manifest.addFiles(outputFiles);
    manifest.setStdoutStderr(outErr);
    manifest.addAction(actionKey, action, command);
    if (manifest.getStderrDigest() != null) {
      result.setStderrDigest(manifest.getStderrDigest());
    }
    if (manifest.getStdoutDigest() != null) {
      result.setStdoutDigest(manifest.getStdoutDigest());
    }

    // if wallTime is zero, than it's not set
    if (startTime != null && wallTimeInMs != 0) {
      Timestamp startTimestamp = instantToTimestamp(startTime);
      Timestamp completedTimestamp = instantToTimestamp(startTime.plusMillis(wallTimeInMs));
      result
          .getExecutionMetadataBuilder()
          .setWorkerStartTimestamp(startTimestamp)
          .setExecutionStartTimestamp(startTimestamp)
          .setExecutionCompletedTimestamp(completedTimestamp)
          .setWorkerCompletedTimestamp(completedTimestamp);
    }

    return manifest;
  }

  private static Timestamp instantToTimestamp(Instant instant) {
    return Timestamp.newBuilder()
        .setSeconds(instant.getEpochSecond())
        .setNanos(instant.getNano())
        .build();
  }

  /**
   * Create an UploadManifest from an ActionResult builder and an exec root. The ActionResult
   * builder is populated through a call to {@link #addFiles(Collection)}.
   *
   * @param followSymlinks whether a non-dangling relative symlink should be transparently
   *     dereferenced and uploaded as the file or directory it points to; other forms of symlink are
   *     always uploaded as such.
   * @param allowDanglingSymlinks whether an uploaded symlink should be allowed to dangle.
   * @param allowAbsoluteSymlinks whether an uploaded symlink should be allowed to be absolute.
   */
  @VisibleForTesting
  public UploadManifest(
      DigestUtil digestUtil,
      RemotePathResolver remotePathResolver,
      ActionResult.Builder result,
      boolean followSymlinks,
      boolean allowDanglingSymlinks,
      boolean allowAbsoluteSymlinks) {
    this.digestUtil = digestUtil;
    this.remotePathResolver = remotePathResolver;
    this.result = result;
    this.followSymlinks = followSymlinks;
    this.allowDanglingSymlinks = allowDanglingSymlinks;
    this.allowAbsoluteSymlinks = allowAbsoluteSymlinks;
  }

  private void setStdoutStderr(FileOutErr outErr) throws IOException {
    if (outErr.getErrorPath().exists()) {
      stderrDigest = digestUtil.compute(outErr.getErrorPath());
      digestToFile.put(stderrDigest, outErr.getErrorPath());
    }
    if (outErr.getOutputPath().exists()) {
      stdoutDigest = digestUtil.compute(outErr.getOutputPath());
      digestToFile.put(stdoutDigest, outErr.getOutputPath());
    }
  }

  /**
   * Add a collection of files, directories or symlinks to the manifest.
   *
   * <p>Adding a directory has the effect of:
   *
   * <ol>
   *   <li>uploading a {@link Tree} protobuf message from which the whole structure of the
   *       directory, including the descendants, can be reconstructed.
   *   <li>uploading all of the non-directory descendant files.
   * </ol>
   *
   * <p>Note that the manifest describes the outcome of a spawn, not of an action. In particular,
   * it's possible for an output to be missing or to have been created with an unsuitable file type
   * for the corresponding {@link Artifact} (e.g., a directory where a file was expected, or a
   * non-symlink where a symlink was expected). Outputs are always uploaded according to the
   * filesystem state, possibly after applying the transformation implied by {@link followSymlinks}.
   * A type mismatch may later cause execution to fail, but that's an action-level concern.
   *
   * <p>All files are uploaded with the executable bit set, in accordance with input Merkle trees.
   * This does not affect correctness since we always set the output permissions to 0555 or 0755
   * after execution, both for cache hits and misses.
   */
  @VisibleForTesting
  void addFiles(Collection<Path> files) throws ExecException, IOException {
    // TODO(tjgq): Non-dangling absolute symlinks are uploaded as the file or directory they point
    // to even when followSymlinks is false. This is inconsistent with the treatment of relative
    // symlinks, but fixing it would require an incompatible change.
    for (Path file : files) {
      // TODO(ulfjack): Maybe pass in a SpawnResult here, add a list of output files to that, and
      // rely on the local spawn runner to stat the files, instead of statting here.
      FileStatus statNoFollow = file.statIfFound(Symlinks.NOFOLLOW);
      // TODO(#6547): handle the case where the parent directory of the output file is an
      // output symlink.
      if (statNoFollow == null) {
        // Ignore missing outputs.
        continue;
      }
      if (statNoFollow.isFile() && !statNoFollow.isSpecialFile()) {
        Digest digest = digestUtil.compute(file, statNoFollow.getSize());
        addFile(digest, file);
        continue;
      }
      if (statNoFollow.isDirectory()) {
        addDirectory(file);
        continue;
      }
      if (statNoFollow.isSymbolicLink()) {
        PathFragment target = file.readSymbolicLink();
        // Need to resolve the symbolic link to know what to add, file or directory.
        FileStatus statFollow = file.statIfFound(Symlinks.FOLLOW);
        if (statFollow == null) {
          // Symlink uploaded as a symlink. Report it as a file since we don't know any better.
          checkDanglingSymlinkAllowed(file, target);
          if (target.isAbsolute()) {
            checkAbsoluteSymlinkAllowed(file, target);
          }
          addFileSymbolicLink(file, target);
          continue;
        }
        if (statFollow.isFile() && !statFollow.isSpecialFile()) {
          if (followSymlinks || target.isAbsolute()) {
            // Symlink to file uploaded as a file.
            addFile(digestUtil.compute(file), file);
          } else {
            // Symlink to file uploaded as a symlink.
            if (target.isAbsolute()) {
              checkAbsoluteSymlinkAllowed(file, target);
            }
            addFileSymbolicLink(file, target);
          }
          continue;
        }
        if (statFollow.isDirectory()) {
          if (followSymlinks || target.isAbsolute()) {
            // Symlink to directory uploaded as a directory.
            addDirectory(file);
          } else {
            // Symlink to directory uploaded as a symlink.
            if (target.isAbsolute()) {
              checkAbsoluteSymlinkAllowed(file, target);
            }
            addDirectorySymbolicLink(file, target);
          }
          continue;
        }
      }
      // Special file or dereferenced symlink to special file.
      rejectSpecialFile(file);
    }
  }

  /**
   * Adds an action and command protos to upload. They need to be uploaded as part of the action
   * result.
   */
  private void addAction(RemoteCacheClient.ActionKey actionKey, Action action, Command command) {
    Preconditions.checkState(this.actionKey == null, "Already added an action");
    this.actionKey = actionKey;
    digestToBlobs.put(actionKey.getDigest(), action.toByteString());
    digestToBlobs.put(action.getCommandDigest(), command.toByteString());
  }

  /** Map of digests to file paths to upload. */
  public Map<Digest, Path> getDigestToFile() {
    return digestToFile;
  }

  /**
   * Map of digests to chunkers to upload. When the file is a regular, non-directory file it is
   * transmitted through {@link #getDigestToFile()}. When it is a directory, it is transmitted as a
   * {@link Tree} protobuf message through {@link #getDigestToBlobs()}.
   */
  public Map<Digest, ByteString> getDigestToBlobs() {
    return digestToBlobs;
  }

  @Nullable
  public Digest getStdoutDigest() {
    return stdoutDigest;
  }

  @Nullable
  public Digest getStderrDigest() {
    return stderrDigest;
  }

  private void addFileSymbolicLink(Path file, PathFragment target) {
    OutputSymlink outputSymlink =
        OutputSymlink.newBuilder()
            .setPath(remotePathResolver.localPathToOutputPath(file))
            .setTarget(target.toString())
            .build();
    result.addOutputFileSymlinks(outputSymlink);
    result.addOutputSymlinks(outputSymlink);
  }

  private void addDirectorySymbolicLink(Path file, PathFragment target) {
    OutputSymlink outputSymlink =
        OutputSymlink.newBuilder()
            .setPath(remotePathResolver.localPathToOutputPath(file))
            .setTarget(target.toString())
            .build();
    result.addOutputDirectorySymlinks(outputSymlink);
    result.addOutputSymlinks(outputSymlink);
  }

  private void addFile(Digest digest, Path file) throws IOException {
    result
        .addOutputFilesBuilder()
        .setPath(remotePathResolver.localPathToOutputPath(file))
        .setDigest(digest)
        .setIsExecutable(true);

    digestToFile.put(digest, file);
  }

  // Field numbers of the 'root' and 'directory' fields in the Tree message.
  private static final int TREE_ROOT_FIELD_NUMBER =
      Tree.getDescriptor().findFieldByName("root").getNumber();
  private static final int TREE_CHILDREN_FIELD_NUMBER =
      Tree.getDescriptor().findFieldByName("children").getNumber();

  private void addDirectory(Path dir) throws ExecException, IOException {
    LinkedHashSet<ByteString> directories = new LinkedHashSet<>();
    var ignored = computeDirectory(dir, directories);

    // Convert individual Directory messages to a Tree message. As we want the
    // records to be topologically sorted (parents before children), we iterate
    // over the directories in reverse insertion order.
    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
    CodedOutputStream codedOutputStream = CodedOutputStream.newInstance(byteArrayOutputStream);
    int fieldNumber = TREE_ROOT_FIELD_NUMBER;
    for (ByteString directory : Lists.reverse(new ArrayList<ByteString>(directories))) {
      codedOutputStream.writeBytes(fieldNumber, directory);
      fieldNumber = TREE_CHILDREN_FIELD_NUMBER;
    }
    codedOutputStream.flush();

    ByteString data = ByteString.copyFrom(byteArrayOutputStream.toByteArray());
    Digest digest = digestUtil.compute(data.toByteArray());

    if (result != null) {
      result
          .addOutputDirectoriesBuilder()
          .setPath(remotePathResolver.localPathToOutputPath(dir))
          .setTreeDigest(digest)
          .setIsTopologicallySorted(true);
    }

    digestToBlobs.put(digest, data);
  }

  /**
   * Computes the {@link Directory} message describing the transitive contents of a directory.
   *
   * @param path the directory path.
   * @param directories an output parameter accepting the wire-format {@link Directory} messages for
   *     every visited (sub-)directory of {@code path}, including {@code path} itself, in a
   *     deterministic topological order (children before parents).
   * @return the wire-format {@link Directory} message for {@code path}.
   */
  private ByteString computeDirectory(Path path, LinkedHashSet<ByteString> directories)
      throws ExecException, IOException {
    Directory.Builder b = Directory.newBuilder();

    List<Dirent> sortedDirent = new ArrayList<>(path.readdir(Symlinks.NOFOLLOW));
    sortedDirent.sort(Comparator.comparing(Dirent::getName));

    for (Dirent dirent : sortedDirent) {
      String name = dirent.getName();
      Path child = path.getRelative(name);
      if (dirent.getType() == Dirent.Type.FILE) {
        Digest digest = digestUtil.compute(child);
        b.addFilesBuilder().setName(name).setDigest(digest).setIsExecutable(true);
        digestToFile.put(digest, child);
        continue;
      }
      if (dirent.getType() == Dirent.Type.DIRECTORY) {
        ByteString dir = computeDirectory(child, directories);
        b.addDirectoriesBuilder().setName(name).setDigest(digestUtil.compute(dir.toByteArray()));
        continue;
      }
      if (dirent.getType() == Dirent.Type.SYMLINK) {
        PathFragment target = child.readSymbolicLink();
        FileStatus statFollow = child.statIfFound(Symlinks.FOLLOW);
        if (statFollow == null || (!followSymlinks && !target.isAbsolute())) {
          // Symlink uploaded as a symlink.
          if (statFollow == null) {
            checkDanglingSymlinkAllowed(child, target);
          }
          if (target.isAbsolute()) {
            checkAbsoluteSymlinkAllowed(child, target);
          }
          b.addSymlinksBuilder().setName(name).setTarget(target.toString());
          continue;
        }
        if (statFollow.isFile() && !statFollow.isSpecialFile()) {
          // Symlink to file uploaded as a file.
          Digest digest = digestUtil.compute(child);
          b.addFilesBuilder().setName(name).setDigest(digest).setIsExecutable(true);
          digestToFile.put(digest, child);
          continue;
        }
        if (statFollow.isDirectory()) {
          // Symlink to directory uploaded as a directory.
          ByteString dir = computeDirectory(child, directories);
          b.addDirectoriesBuilder().setName(name).setDigest(digestUtil.compute(dir.toByteArray()));
          continue;
        }
      }
      // Special file or dereferenced symlink to special file.
      rejectSpecialFile(child);
    }

    ByteString directory = b.build().toByteString();
    directories.add(directory);
    return directory;
  }

  private void checkDanglingSymlinkAllowed(Path file, PathFragment target) throws IOException {
    if (!allowDanglingSymlinks) {
      throw new IOException(
          String.format(
              "Spawn output %s is a dangling symbolic link to %s, which is not allowed by"
                  + " --noincompatible_remote_dangling_symlinks",
              file, target));
    }
  }

  private void checkAbsoluteSymlinkAllowed(Path file, PathFragment target) throws IOException {
    if (!allowAbsoluteSymlinks) {
      throw new IOException(
          String.format(
              "Spawn output %s is an absolute symbolic link to %s, which is not allowed by"
                  + " the remote cache",
              file, target));
    }
  }

  private void rejectSpecialFile(Path path) throws ExecException {
    // TODO(tjgq): Consider treating special files as regular, following Skyframe.
    // (On the other hand, they seem to be only useful for testing purposes, so we might instead
    // want to forbid them entirely.)
    String message =
        String.format(
            "Spawn output %s is a special file. Only regular files, directories or symlinks may be "
                + "uploaded to a remote cache.",
            path);

    FailureDetail failureDetail =
        FailureDetail.newBuilder()
            .setMessage(message)
            .setRemoteExecution(RemoteExecution.newBuilder().setCode(Code.ILLEGAL_OUTPUT))
            .build();
    throw new UserExecException(failureDetail);
  }

  @VisibleForTesting
  ActionResult getActionResult() {
    return result.build();
  }

  /** Uploads outputs and action result (if exit code is 0) to remote cache. */
  public ActionResult upload(
      RemoteActionExecutionContext context, RemoteCache remoteCache, ExtendedEventHandler reporter)
      throws IOException, InterruptedException, ExecException {
    try {
      return uploadAsync(context, remoteCache, reporter).blockingGet();
    } catch (RuntimeException e) {
      Throwable cause = e.getCause();
      if (cause != null) {
        throwIfInstanceOf(cause, InterruptedException.class);
        throwIfInstanceOf(cause, IOException.class);
        throwIfInstanceOf(cause, ExecException.class);
      }
      throw e;
    }
  }

  private Completable upload(
      RemoteActionExecutionContext context, RemoteCache remoteCache, Digest digest) {
    Path file = digestToFile.get(digest);
    if (file != null) {
      return toCompletable(() -> remoteCache.uploadFile(context, digest, file), directExecutor());
    }

    ByteString blob = digestToBlobs.get(digest);
    if (blob == null) {
      String message = "FindMissingBlobs call returned an unknown digest: " + digest;
      return Completable.error(new IOException(message));
    }

    return toCompletable(() -> remoteCache.uploadBlob(context, digest, blob), directExecutor());
  }

  private static void reportUploadStarted(
      ExtendedEventHandler reporter,
      @Nullable ActionExecutionMetadata action,
      Store store,
      Iterable<Digest> digests) {
    if (action != null) {
      for (Digest digest : digests) {
        reporter.post(ActionUploadStartedEvent.create(action, store, digest));
      }
    }
  }

  private static void reportUploadFinished(
      ExtendedEventHandler reporter,
      @Nullable ActionExecutionMetadata action,
      Store store,
      Iterable<Digest> digests) {
    if (action != null) {
      for (Digest digest : digests) {
        reporter.post(ActionUploadFinishedEvent.create(action, store, digest));
      }
    }
  }

  /**
   * Returns a {@link Single} which upon subscription will upload outputs and action result (if exit
   * code is 0) to remote cache.
   */
  public Single<ActionResult> uploadAsync(
      RemoteActionExecutionContext context,
      RemoteCache remoteCache,
      ExtendedEventHandler reporter) {
    Collection<Digest> digests = new ArrayList<>();
    digests.addAll(digestToFile.keySet());
    digests.addAll(digestToBlobs.keySet());

    ActionExecutionMetadata action = context.getSpawnOwner();

    Flowable<RxUtils.TransferResult> bulkTransfers =
        toSingle(() -> remoteCache.findMissingDigests(context, digests), directExecutor())
            .doOnSubscribe(d -> reportUploadStarted(reporter, action, Store.CAS, digests))
            .doOnError(error -> reportUploadFinished(reporter, action, Store.CAS, digests))
            .doOnDispose(() -> reportUploadFinished(reporter, action, Store.CAS, digests))
            .doOnSuccess(
                missingDigests -> {
                  List<Digest> existedDigests =
                      digests.stream()
                          .filter(digest -> !missingDigests.contains(digest))
                          .collect(Collectors.toList());
                  reportUploadFinished(reporter, action, Store.CAS, existedDigests);
                })
            .flatMapPublisher(Flowable::fromIterable)
            .flatMapSingle(
                digest ->
                    toTransferResult(upload(context, remoteCache, digest))
                        .doFinally(
                            () ->
                                reportUploadFinished(
                                    reporter, action, Store.CAS, ImmutableList.of(digest))));
    Completable uploadOutputs = mergeBulkTransfer(bulkTransfers);

    ActionResult actionResult = result.build();
    Completable uploadActionResult = Completable.complete();
    if (actionResult.getExitCode() == 0 && actionKey != null) {
      uploadActionResult =
          toCompletable(
                  () -> remoteCache.uploadActionResult(context, actionKey, actionResult),
                  directExecutor())
              .doOnSubscribe(
                  d ->
                      reportUploadStarted(
                          reporter, action, Store.AC, ImmutableList.of(actionKey.getDigest())))
              .doFinally(
                  () ->
                      reportUploadFinished(
                          reporter, action, Store.AC, ImmutableList.of(actionKey.getDigest())));
    }

    return Completable.concatArray(uploadOutputs, uploadActionResult).toSingleDefault(actionResult);
  }
}
