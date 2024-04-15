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

package com.google.devtools.build.lib.sandbox;

import static com.google.devtools.build.lib.sandbox.LinuxSandboxCommandLineBuilder.NetworkNamespace.NETNS_WITH_LOOPBACK;
import static com.google.devtools.build.lib.sandbox.LinuxSandboxCommandLineBuilder.NetworkNamespace.NO_NETNS;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.io.ByteStreams;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.ExecutionRequirements;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.FileContentsProxy;
import com.google.devtools.build.lib.actions.ForbiddenActionInputException;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.Spawns;
import com.google.devtools.build.lib.actions.UserExecException;
import com.google.devtools.build.lib.actions.cache.VirtualActionInput;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.exec.TreeDeleter;
import com.google.devtools.build.lib.exec.local.LocalEnvProvider;
import com.google.devtools.build.lib.exec.local.LocalExecutionOptions;
import com.google.devtools.build.lib.exec.local.PosixLocalEnvProvider;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.sandbox.SandboxHelpers.SandboxInputs;
import com.google.devtools.build.lib.sandbox.SandboxHelpers.SandboxOutputs;
import com.google.devtools.build.lib.shell.Command;
import com.google.devtools.build.lib.shell.CommandException;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.vfs.FileStatus;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.Symlinks;
import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;

/** Spawn runner that uses linux sandboxing APIs to execute a local subprocess. */
final class LinuxSandboxedSpawnRunner extends AbstractSandboxSpawnRunner {
  private static final PathFragment SLASH_TMP = PathFragment.create("/tmp");

  // Since checking if sandbox is supported is expensive, we remember what we've checked.
  private static final Map<Path, Boolean> isSupportedMap = new HashMap<>();

  private static final AtomicBoolean warnedAboutUnsupportedModificationCheck = new AtomicBoolean();

  /**
   * Returns whether the linux sandbox is supported on the local machine by running a small command
   * in it.
   */
  public static boolean isSupported(final CommandEnvironment cmdEnv) throws InterruptedException {
    if (OS.getCurrent() != OS.LINUX) {
      return false;
    }
    if (!LinuxSandboxUtil.isSupported(cmdEnv.getBlazeWorkspace())) {
      return false;
    }
    Path linuxSandbox = LinuxSandboxUtil.getLinuxSandbox(cmdEnv.getBlazeWorkspace());
    Boolean isSupported;
    synchronized (isSupportedMap) {
      isSupported = isSupportedMap.get(linuxSandbox);
      if (isSupported != null) {
        return isSupported;
      }
      isSupported = computeIsSupported(cmdEnv, linuxSandbox);
      isSupportedMap.put(linuxSandbox, isSupported);
    }
    return isSupported;
  }

  private static boolean computeIsSupported(CommandEnvironment cmdEnv, Path linuxSandbox)
      throws InterruptedException {
    LocalExecutionOptions options = cmdEnv.getOptions().getOptions(LocalExecutionOptions.class);
    ImmutableList<String> linuxSandboxArgv =
        LinuxSandboxCommandLineBuilder.commandLineBuilder(linuxSandbox)
            .setTimeout(options.getLocalSigkillGraceSeconds())
            .buildForCommand(ImmutableList.of("/bin/true"));
    ImmutableMap<String, String> env = ImmutableMap.of();
    Path execRoot = cmdEnv.getExecRoot();
    File cwd = execRoot.getPathFile();

    Command cmd = new Command(linuxSandboxArgv.toArray(new String[0]), env, cwd);
    try (SilentCloseable c = Profiler.instance().profile("LinuxSandboxedSpawnRunner.isSupported")) {
      cmd.execute(ByteStreams.nullOutputStream(), ByteStreams.nullOutputStream());
    } catch (CommandException e) {
      return false;
    }

    return true;
  }

  private final SandboxHelpers helpers;
  private final Path execRoot;
  private final boolean allowNetwork;
  private final Path linuxSandbox;
  private final Path sandboxBase;
  private final Path inaccessibleHelperFile;
  private final Path inaccessibleHelperDir;
  private final LocalEnvProvider localEnvProvider;
  private final Duration timeoutKillDelay;
  private final TreeDeleter treeDeleter;
  private final Reporter reporter;
  private final ImmutableList<Root> packageRoots;
  private String cgroupsDir;

  /**
   * Creates a sandboxed spawn runner that uses the {@code linux-sandbox} tool.
   *
   * @param helpers common tools and state across all spawns during sandboxed execution
   * @param cmdEnv the command environment to use
   * @param sandboxBase path to the sandbox base directory
   * @param inaccessibleHelperFile path to a file that is (already) inaccessible
   * @param inaccessibleHelperDir path to a directory that is (already) inaccessible
   * @param timeoutKillDelay an additional grace period before killing timing out commands
   */
  LinuxSandboxedSpawnRunner(
      SandboxHelpers helpers,
      CommandEnvironment cmdEnv,
      Path sandboxBase,
      Path inaccessibleHelperFile,
      Path inaccessibleHelperDir,
      Duration timeoutKillDelay,
      TreeDeleter treeDeleter) {
    super(cmdEnv);
    this.helpers = helpers;
    this.execRoot = cmdEnv.getExecRoot();
    this.allowNetwork = helpers.shouldAllowNetwork(cmdEnv.getOptions());
    this.linuxSandbox = LinuxSandboxUtil.getLinuxSandbox(cmdEnv.getBlazeWorkspace());
    this.sandboxBase = sandboxBase;
    this.inaccessibleHelperFile = inaccessibleHelperFile;
    this.inaccessibleHelperDir = inaccessibleHelperDir;
    this.timeoutKillDelay = timeoutKillDelay;
    this.localEnvProvider = new PosixLocalEnvProvider(cmdEnv.getClientEnv());
    this.treeDeleter = treeDeleter;
    this.reporter = cmdEnv.getReporter();
    this.packageRoots = cmdEnv.getPackageLocator().getPathEntries();
  }

  private boolean useHermeticTmp() {
    if (!getSandboxOptions().sandboxHermeticTmp) {
      // No hermetic /tmp requested, so let's not do it
      return false;
    }

    if (getSandboxOptions().useHermetic) {
      // The hermetic sandbox is, well, already hermetic. Also, it creates an empty /tmp by default
      // so nothing needs to be done to achieve a /tmp that is also hermetic.
      return false;
    }

    boolean tmpExplicitlyBindMounted =
        getSandboxOptions().sandboxAdditionalMounts.stream()
            .anyMatch(e -> e.getKey().equals("/tmp"));
    if (tmpExplicitlyBindMounted) {
      // An explicit mount on /tmp is likely an explicit way to make it non-hermetic.
      return false;
    }

    if (getSandboxOptions().sandboxTmpfsPath.contains(SLASH_TMP)) {
      // A tmpfs path under /tmp is as hermetic as "hermetic /tmp".
      return false;
    }

    return true;
  }

  @Override
  protected SandboxedSpawn prepareSpawn(Spawn spawn, SpawnExecutionContext context)
      throws IOException, ForbiddenActionInputException, ExecException, InterruptedException {

    // Each invocation of "exec" gets its own sandbox base.
    // Note that the value returned by context.getId() is only unique inside one given SpawnRunner,
    // so we have to prefix our name to turn it into a globally unique value.
    Path sandboxPath =
        sandboxBase.getRelative(getName()).getRelative(Integer.toString(context.getId()));
    sandboxPath.createDirectoryAndParents();

    // b/64689608: The execroot of the sandboxed process must end with the workspace name, just like
    // the normal execroot does.
    String workspaceName = execRoot.getBaseName();
    Path sandboxExecRoot = sandboxPath.getRelative("execroot").getRelative(workspaceName);
    sandboxExecRoot.createDirectoryAndParents();

    SandboxInputs inputs =
        helpers.processInputFiles(
            context.getInputMapping(PathFragment.EMPTY_FRAGMENT, /* willAccessRepeatedly= */ true),
            execRoot,
            execRoot,
            packageRoots,
            null);

    SandboxOutputs outputs = helpers.getOutputs(spawn);
    ImmutableMap<String, String> environment =
        localEnvProvider.rewriteLocalEnv(spawn.getEnvironment(), binTools, "/tmp");
    ImmutableSet<Path> writableDirs = getWritableDirs(sandboxExecRoot, environment);
    Duration timeout = context.getTimeout();
    SandboxOptions sandboxOptions = getSandboxOptions();

    boolean createNetworkNamespace =
        !(allowNetwork || Spawns.requiresNetwork(spawn, sandboxOptions.defaultSandboxAllowNetwork));
    LinuxSandboxCommandLineBuilder commandLineBuilder =
        LinuxSandboxCommandLineBuilder.commandLineBuilder(linuxSandbox)
            .addExecutionInfo(spawn.getExecutionInfo())
            .setWritableFilesAndDirectories(writableDirs)
            .setTmpfsDirectories(ImmutableSet.copyOf(getSandboxOptions().sandboxTmpfsPath))
            .setBindMounts(getBindMounts(sandboxExecRoot))
            .setUseFakeHostname(getSandboxOptions().sandboxFakeHostname)
            .setEnablePseudoterminal(getSandboxOptions().sandboxExplicitPseudoterminal)
            .setCreateNetworkNamespace(createNetworkNamespace ? NETNS_WITH_LOOPBACK : NO_NETNS)
            .setKillDelay(timeoutKillDelay);

    Path sandboxDebugPath = null;
    if (sandboxOptions.sandboxDebug) {
      sandboxDebugPath = sandboxPath.getRelative("debug.out");
      commandLineBuilder.setSandboxDebugPath(sandboxDebugPath.getPathString());
    }

    if (sandboxOptions.memoryLimitMb > 0) {
      // We put the sandbox inside a unique subdirectory using the context's ID. This ID is
      // unique per spawn run by this spawn runner.
      CgroupsInfo sandboxCgroup =
          CgroupsInfo.getBlazeSpawnsCgroup()
              .createIndividualSpawnCgroup(
                  "sandbox_" + context.getId(), sandboxOptions.memoryLimitMb);
      if (sandboxCgroup.exists()) {
        commandLineBuilder.setCgroupsDir(sandboxCgroup.getCgroupDir().toString());
      }
    }

    if (useHermeticTmp()) {
      // The base dir for the upperdir and workdir of an overlayfs on /tmp.
      Path sandboxTmp = sandboxPath.getRelative("_hermetic_tmp");
      Path sandboxTmpUpperdir = sandboxTmp.getRelative("upperdir");
      sandboxTmpUpperdir.createDirectoryAndParents();
      Path sandboxTmpWorkdir  = sandboxTmp.getRelative("workdir");
      sandboxTmpWorkdir.createDirectoryAndParents();
      commandLineBuilder.mountOverlayfsOnTmp(sandboxTmpUpperdir.getPathString(),
          sandboxTmpWorkdir.getPathString());
    }

    if (!timeout.isZero()) {
      commandLineBuilder.setTimeout(timeout);
    }
    if (spawn.getExecutionInfo().containsKey(ExecutionRequirements.REQUIRES_FAKEROOT)) {
      commandLineBuilder.setUseFakeRoot(true);
    } else if (sandboxOptions.sandboxFakeUsername) {
      commandLineBuilder.setUseFakeUsername(true);
    }
    Path statisticsPath = sandboxPath.getRelative("stats.out");
    commandLineBuilder.setStatisticsPath(statisticsPath);
    if (sandboxOptions.useHermetic) {
      commandLineBuilder.setHermeticSandboxPath(sandboxPath);
      return new HardlinkedSandboxedSpawn(
          sandboxPath,
          sandboxExecRoot,
          commandLineBuilder.buildForCommand(spawn.getArguments()),
          environment,
          inputs,
          outputs,
          writableDirs,
          treeDeleter,
          sandboxDebugPath,
          statisticsPath,
          sandboxOptions.sandboxDebug,
          spawn.getMnemonic());
    } else {
      return new SymlinkedSandboxedSpawn(
          sandboxPath,
          sandboxExecRoot,
          commandLineBuilder.buildForCommand(spawn.getArguments()),
          environment,
          inputs,
          outputs,
          writableDirs,
          treeDeleter,
          sandboxDebugPath,
          statisticsPath,
          makeInteractiveDebugArguments(commandLineBuilder, sandboxOptions),
          spawn.getMnemonic());
    }
  }

  @Override
  public String getName() {
    return "linux-sandbox";
  }

  @Override
  protected ImmutableSet<Path> getWritableDirs(Path sandboxExecRoot, Map<String, String> env)
      throws IOException {
    Set<Path> writableDirs = new TreeSet<>();
    writableDirs.addAll(super.getWritableDirs(sandboxExecRoot, env));
    FileSystem fs = sandboxExecRoot.getFileSystem();
    writableDirs.add(fs.getPath("/dev/shm").resolveSymbolicLinks());
    writableDirs.add(fs.getPath("/tmp"));
    return ImmutableSet.copyOf(writableDirs);
  }

  private SortedMap<Path, Path> getBindMounts(Path sandboxExecRoot) throws UserExecException {
    final SortedMap<Path, Path> bindMounts = Maps.newTreeMap();
    SandboxHelpers.mountAdditionalPaths(
        getSandboxOptions().sandboxAdditionalMounts, sandboxExecRoot, bindMounts);

    for (Path inaccessiblePath : getInaccessiblePaths()) {
      if (inaccessiblePath.isDirectory(Symlinks.NOFOLLOW)) {
        bindMounts.put(inaccessiblePath, inaccessibleHelperDir);
      } else {
        bindMounts.put(inaccessiblePath, inaccessibleHelperFile);
      }
    }

    LinuxSandboxUtil.validateBindMounts(bindMounts);
    return bindMounts;
  }

  @Override
  public void verifyPostCondition(
      Spawn originalSpawn, SandboxedSpawn sandbox, SpawnExecutionContext context)
      throws IOException, ForbiddenActionInputException {
    if (getSandboxOptions().useHermetic) {
      checkForConcurrentModifications(context);
    }
  }

  private void checkForConcurrentModifications(SpawnExecutionContext context)
      throws IOException, ForbiddenActionInputException {
    for (ActionInput input :
        context
            .getInputMapping(PathFragment.EMPTY_FRAGMENT, /* willAccessRepeatedly= */ true)
            .values()) {
      if (input instanceof VirtualActionInput) {
        // Virtual inputs are not existing in file system and can't be tampered with via sandbox. No
        // need to check them.
        continue;
      }

      FileArtifactValue metadata = context.getInputMetadataProvider().getInputMetadata(input);
      if (metadata == null) {
        // This can happen if we are executing a spawn in an action that has multiple spawns and
        // the output of one is the input of another. In this case, we assume that no one modifies
        // an output of the first spawn before the action is completed (which requires the
        // the completion of the second spawn, which happens after this point is reached in the
        // code)
        continue;
      }
      if (!metadata.getType().isFile()) {
        // The hermetic sandbox creates hardlinks from files inside sandbox to files outside
        // sandbox. The content of the files outside the sandbox could have been tampered with via
        // the hardlinks. Therefore files are checked for modifications. But directories and
        // unresolved symlinks are not represented as hardlinks in sandbox and don't need to be
        // checked. By continue and not checking them, we avoid UnsupportedOperationException and
        // IllegalStateException.
        continue;
      }

      Path path = execRoot.getRelative(input.getExecPath());
      try {
        if (wasModifiedSinceDigest(metadata.getContentsProxy(), path)) {
          throw new IOException("input dependency " + path + " was modified during execution.");
        }
      } catch (UnsupportedOperationException e) {
        // Some FileArtifactValue implementations are ignored safely and silently already by the
        // isFile check above. The remaining ones should probably be checked, but some are not
        // supporting necessary operations.
        if (warnedAboutUnsupportedModificationCheck.compareAndSet(false, true)) {
          reporter.handle(
              Event.warn(
                  String.format(
                      "Input dependency %s of type %s could not be checked for modifications during"
                          + " execution. Suppressing similar warnings.",
                      path, metadata.getClass().getSimpleName())));
        }
      }
    }
  }

  private boolean wasModifiedSinceDigest(FileContentsProxy proxy, Path path) throws IOException {
    if (proxy == null) {
      return false;
    }
    FileStatus stat = path.statIfFound(Symlinks.FOLLOW);
    return stat == null || !stat.isFile() || proxy.isModified(FileContentsProxy.create(stat));
  }

  @Override
  public void cleanupSandboxBase(Path sandboxBase, TreeDeleter treeDeleter) throws IOException {
    if (cgroupsDir != null) {
      new File(cgroupsDir).delete();
    }
    // Delete the inaccessible files synchronously, bypassing the treeDeleter. They are only a
    // couple of files that can be deleted fast, and ensuring they are gone at the end of every
    // build avoids annoying permission denied errors if the user happens to run "rm -rf" on the
    // output base. (We have some tests that do that.)
    if (inaccessibleHelperDir.exists()) {
      inaccessibleHelperDir.chmod(0700);
      inaccessibleHelperDir.deleteTree();
    }
    if (inaccessibleHelperFile.exists()) {
      inaccessibleHelperFile.chmod(0600);
      inaccessibleHelperFile.delete();
    }

    super.cleanupSandboxBase(sandboxBase, treeDeleter);
  }

  @Nullable
  private ImmutableList<String> makeInteractiveDebugArguments(
      LinuxSandboxCommandLineBuilder commandLineBuilder, SandboxOptions sandboxOptions) {
    if (!sandboxOptions.sandboxDebug) {
      return null;
    }
    return commandLineBuilder.buildForCommand(ImmutableList.of("/bin/sh", "-i"));
  }
}
