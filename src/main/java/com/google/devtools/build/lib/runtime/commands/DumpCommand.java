// Copyright 2015 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.runtime.commands;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.collect.ConcurrentIdentitySet;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.profiler.memory.AllocationTracker;
import com.google.devtools.build.lib.profiler.memory.AllocationTracker.RuleBytes;
import com.google.devtools.build.lib.runtime.BlazeCommand;
import com.google.devtools.build.lib.runtime.BlazeCommandResult;
import com.google.devtools.build.lib.runtime.BlazeCommandUtils;
import com.google.devtools.build.lib.runtime.BlazeRuntime;
import com.google.devtools.build.lib.runtime.BlazeWorkspace;
import com.google.devtools.build.lib.runtime.Command;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.server.FailureDetails.DumpCommand.Code;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.skyframe.BzlLoadValue;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetKey;
import com.google.devtools.build.lib.skyframe.SkyFunctions;
import com.google.devtools.build.lib.skyframe.SkyKeyStats;
import com.google.devtools.build.lib.skyframe.SkyframeExecutor;
import com.google.devtools.build.lib.skyframe.SkyframeStats;
import com.google.devtools.build.lib.skyframe.config.BuildConfigurationKey;
import com.google.devtools.build.lib.util.MemoryAccountant;
import com.google.devtools.build.lib.util.MemoryAccountant.Stats;
import com.google.devtools.build.lib.util.ObjectGraphTraverser;
import com.google.devtools.build.lib.util.ObjectGraphTraverser.FieldCache;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.util.RegexFilter;
import com.google.devtools.build.lib.util.RegexFilter.RegexFilterConverter;
import com.google.devtools.build.skyframe.InMemoryGraph;
import com.google.devtools.build.skyframe.InMemoryNodeEntry;
import com.google.devtools.build.skyframe.MemoizingEvaluator;
import com.google.devtools.build.skyframe.NodeEntry;
import com.google.devtools.build.skyframe.QueryableGraph.Reason;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.common.options.Converter;
import com.google.devtools.common.options.EnumConverter;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionDocumentationCategory;
import com.google.devtools.common.options.OptionEffectTag;
import com.google.devtools.common.options.OptionsBase;
import com.google.devtools.common.options.OptionsParser;
import com.google.devtools.common.options.OptionsParsingException;
import com.google.devtools.common.options.OptionsParsingResult;
import com.google.gson.stream.JsonWriter;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import javax.annotation.Nullable;

/** Implementation of the dump command. */
@Command(
    mustRunInWorkspace = false,
    options = {DumpCommand.DumpOptions.class},
    help =
        "Usage: %{product} dump <options>\n"
            + "Dumps the internal state of the %{product} server process.  This command is provided"
            + " as an aid to debugging, not as a stable interface, so users should not try to parse"
            + " the output; instead, use 'query' or 'info' for this purpose.\n"
            + "%{options}",
    name = "dump",
    shortDescription = "Dumps the internal state of the %{product} server process.",
    binaryStdOut = true)
public class DumpCommand implements BlazeCommand {
  /** How to dump Skyframe memory. */
  private enum MemoryCollectionMode {
    /** Dump the objects owned by a single SkyValue */
    SHALLOW,
    /** Dump objects reachable from a single SkyValue */
    DEEP,
    /** Dump objects in the Skyframe transitive closure of a SkyValue */
    TRANSITIVE,
    /** Dump every object in Skyframe in "shallow" mode. */
    FULL,
  }

  /** How to display Skyframe memory use. */
  private enum MemoryDisplayMode {
    /** Just a summary line */
    SUMMARY,
    /** Object count by class */
    COUNT,
    /** Bytes by class */
    BYTES,
  }

  /** Whose memory use we should measure. */
  private enum MemorySubjectType {
    /** Starlark module */
    STARLARK_MODULE,
    /* Build package */
    PACKAGE,
    /* Configured target */
    CONFIGURED_TARGET,
  }

  private record MemoryMode(
      MemoryCollectionMode collectionMode,
      MemoryDisplayMode displayMode,
      MemorySubjectType type,
      String needle,
      boolean reportTransient,
      boolean reportConfiguration,
      boolean reportPrecomputed,
      boolean reportWorkspaceStatus,
      String subject) {}

  /** Converter for {@link MemoryCollectionMode}. */
  public static final class MemoryModeConverter extends Converter.Contextless<MemoryMode> {
    @Override
    public String getTypeDescription() {
      return "memory mode";
    }

    @Override
    public MemoryMode convert(String input) throws OptionsParsingException {
      // The SkyKey designator is frequently a Label, which usually contains a colon so we must not
      // split the argument into an unlimited number of elements
      String[] items = input.split(":", 3);
      if (items.length > 3) {
        throw new OptionsParsingException("Should contain at most three segments separated by ':'");
      }

      MemoryCollectionMode collectionMode = null;
      MemoryDisplayMode displayMode = null;
      String needle = null;
      boolean reportTransient = true;
      boolean reportConfiguration = true;
      boolean reportPrecomputed = true;
      boolean reportWorkspaceStatus = true;

      for (String word : Splitter.on(",").split(items[0])) {
        if (word.startsWith("needle=")) {
          needle = word.split("=", 2)[1];
          continue;
        }

        switch (word) {
          case "shallow" -> collectionMode = MemoryCollectionMode.SHALLOW;
          case "deep" -> collectionMode = MemoryCollectionMode.DEEP;
          case "transitive" -> collectionMode = MemoryCollectionMode.TRANSITIVE;
          case "full" -> collectionMode = MemoryCollectionMode.FULL;
          case "summary" -> displayMode = MemoryDisplayMode.SUMMARY;
          case "count" -> displayMode = MemoryDisplayMode.COUNT;
          case "bytes" -> displayMode = MemoryDisplayMode.BYTES;
          case "notransient" -> reportTransient = false;
          case "noconfig" -> reportConfiguration = false;
          case "noprecomputed" -> reportPrecomputed = false;
          case "noworkspacestatus" -> reportWorkspaceStatus = false;
          default -> throw new OptionsParsingException("Unrecognized word '" + word + "'");
        }
      }

      if (collectionMode == null) {
        throw new OptionsParsingException("No collection type specified");
      }

      if (displayMode == null) {
        throw new OptionsParsingException("No display mode specified");
      }

      if (collectionMode == MemoryCollectionMode.FULL) {
        return new MemoryMode(
            collectionMode,
            displayMode,
            null,
            needle,
            reportTransient,
            reportConfiguration,
            reportPrecomputed,
            reportWorkspaceStatus,
            null);
      }

      if (items.length != 3) {
        throw new OptionsParsingException("Should be in the form: <flags>:<node type>:<node>");
      }

      MemorySubjectType subjectType;

      try {
        subjectType = MemorySubjectType.valueOf(items[1].toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException e) {
        throw new OptionsParsingException("Invalid subject type", e);
      }

      return new MemoryMode(
          collectionMode,
          displayMode,
          subjectType,
          needle,
          reportTransient,
          reportConfiguration,
          reportPrecomputed,
          reportWorkspaceStatus,
          items[2]);
    }
  }

  /**
   * NB! Any changes to this class must be kept in sync with anyOutput variable value in the {@link
   * DumpCommand#exec} method below.
   */
  public static class DumpOptions extends OptionsBase {

    @Option(
        name = "packages",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.OUTPUT_SELECTION,
        effectTags = {OptionEffectTag.BAZEL_MONITORING},
        help = "Dump package cache content.")
    public boolean dumpPackages;

    @Option(
        name = "action_cache",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.OUTPUT_SELECTION,
        effectTags = {OptionEffectTag.BAZEL_MONITORING},
        help = "Dump action cache content.")
    public boolean dumpActionCache;

    @Option(
        name = "rule_classes",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.OUTPUT_SELECTION,
        effectTags = {OptionEffectTag.BAZEL_MONITORING},
        help = "Dump rule classes.")
    public boolean dumpRuleClasses;

    @Option(
        name = "rules",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.OUTPUT_SELECTION,
        effectTags = {OptionEffectTag.BAZEL_MONITORING},
        help = "Dump rules, including counts and memory usage (if memory is tracked).")
    public boolean dumpRules;

    @Option(
        name = "skylark_memory",
        defaultValue = "null",
        documentationCategory = OptionDocumentationCategory.OUTPUT_SELECTION,
        effectTags = {OptionEffectTag.BAZEL_MONITORING},
        help =
            "Dumps a pprof-compatible memory profile to the specified path. To learn more please"
                + " see https://github.com/google/pprof.")
    public String starlarkMemory;

    @Option(
        name = "skyframe",
        defaultValue = "off",
        converter = SkyframeDumpEnumConverter.class,
        documentationCategory = OptionDocumentationCategory.OUTPUT_SELECTION,
        effectTags = {OptionEffectTag.BAZEL_MONITORING},
        help =
            "Dump Skyframe graph: 'off', 'summary', 'count', 'value', 'deps', 'rdeps', or"
                + " 'function_graph'.")
    public SkyframeDumpOption dumpSkyframe;

    @Option(
        name = "skykey_filter",
        defaultValue = ".*",
        converter = RegexFilterConverter.class,
        documentationCategory = OptionDocumentationCategory.OUTPUT_SELECTION,
        effectTags = {OptionEffectTag.BAZEL_MONITORING},
        help =
            "Regex filter of SkyKey names to output. Only used with --skyframe=deps, rdeps,"
                + " function_graph.")
    public RegexFilter skyKeyFilter;

    @Option(
        name = "memory",
        defaultValue = "null",
        converter = MemoryModeConverter.class,
        documentationCategory = OptionDocumentationCategory.OUTPUT_SELECTION,
        effectTags = {OptionEffectTag.BAZEL_MONITORING},
        help = "Dump the memory use of the given Skyframe node.")
    public MemoryMode memory;
  }

  /** Different ways to dump information about Skyframe. */
  public enum SkyframeDumpOption {
    OFF,
    SUMMARY,
    COUNT,
    VALUE,
    DEPS,
    RDEPS,
    FUNCTION_GRAPH,
  }

  /** Enum converter for SkyframeDumpOption. */
  public static class SkyframeDumpEnumConverter extends EnumConverter<SkyframeDumpOption> {
    public SkyframeDumpEnumConverter() {
      super(SkyframeDumpOption.class, "Skyframe Dump option");
    }
  }

  public static final String WARNING_MESSAGE =
      "This information is intended for consumption by developers "
          + "only, and may change at any time. Script against it at your own risk!";

  @Override
  public BlazeCommandResult exec(CommandEnvironment env, OptionsParsingResult options) {
    BlazeRuntime runtime = env.getRuntime();
    DumpOptions dumpOptions = options.getOptions(DumpOptions.class);

    boolean anyOutput =
        dumpOptions.dumpPackages
            || dumpOptions.dumpActionCache
            || dumpOptions.dumpRuleClasses
            || dumpOptions.dumpRules
            || dumpOptions.starlarkMemory != null
            || dumpOptions.dumpSkyframe != SkyframeDumpOption.OFF
            || dumpOptions.memory != null;
    if (!anyOutput) {
      Collection<Class<? extends OptionsBase>> optionList = new ArrayList<>();
      optionList.add(DumpOptions.class);

      env.getReporter()
          .getOutErr()
          .printErrLn(
              BlazeCommandUtils.expandHelpTopic(
                  getClass().getAnnotation(Command.class).name(),
                  getClass().getAnnotation(Command.class).help(),
                  getClass(),
                  optionList,
                  OptionsParser.HelpVerbosity.LONG,
                  runtime.getProductName()));
      return createFailureResult("no output specified", Code.NO_OUTPUT_SPECIFIED);
    }
    PrintStream out =
        new PrintStream(
            new BufferedOutputStream(env.getReporter().getOutErr().getOutputStream(), 1024 * 1024));
    try {
      env.getReporter().handle(Event.warn(WARNING_MESSAGE));
      Optional<BlazeCommandResult> failure = Optional.empty();

      if (dumpOptions.dumpPackages) {
        env.getPackageManager().dump(out);
        out.println();
      }

      if (dumpOptions.dumpActionCache) {
        if (!dumpActionCache(env, out)) {
          failure =
              Optional.of(
                  createFailureResult("action cache dump failed", Code.ACTION_CACHE_DUMP_FAILED));
        }
        out.println();
      }

      if (dumpOptions.dumpRuleClasses) {
        dumpRuleClasses(runtime, out);
        out.println();
      }

      if (dumpOptions.dumpRules) {
        dumpRuleStats(env.getReporter(), env.getBlazeWorkspace(), env.getSkyframeExecutor(), out);
        out.println();
      }

      if (dumpOptions.starlarkMemory != null) {
        try {
          dumpStarlarkHeap(env.getBlazeWorkspace(), dumpOptions.starlarkMemory, out);
        } catch (IOException e) {
          String message = "Could not dump Starlark memory";
          env.getReporter().error(null, message, e);
          failure = Optional.of(createFailureResult(message, Code.STARLARK_HEAP_DUMP_FAILED));
        }
      }

      if (dumpOptions.memory != null) {
        failure = dumpSkyframeMemory(env, dumpOptions, out);
      }

      MemoizingEvaluator evaluator = env.getSkyframeExecutor().getEvaluator();
      switch (dumpOptions.dumpSkyframe) {
        case SUMMARY -> evaluator.dumpSummary(out);
        case COUNT -> evaluator.dumpCount(out);
        case VALUE -> evaluator.dumpValues(out, dumpOptions.skyKeyFilter);
        case DEPS -> evaluator.dumpDeps(out, dumpOptions.skyKeyFilter);
        case RDEPS -> evaluator.dumpRdeps(out, dumpOptions.skyKeyFilter);
        case FUNCTION_GRAPH -> evaluator.dumpFunctionGraph(out, dumpOptions.skyKeyFilter);
        case OFF -> {}
      }

      return failure.orElse(BlazeCommandResult.success());
    } catch (InterruptedException e) {
      env.getReporter().error(null, "Interrupted", e);
      return BlazeCommandResult.failureDetail(
          FailureDetail.newBuilder()
              .setInterrupted(
                  FailureDetails.Interrupted.newBuilder()
                      .setCode(FailureDetails.Interrupted.Code.INTERRUPTED))
              .build());
    } finally {
      out.flush();
    }
  }

  private static boolean dumpActionCache(CommandEnvironment env, PrintStream out) {
    Reporter reporter = env.getReporter();
    try {
      env.getBlazeWorkspace().getOrLoadPersistentActionCache(reporter).dump(out);
    } catch (IOException e) {
      reporter.handle(Event.error("Cannot dump action cache: " + e.getMessage()));
      return false;
    }
    return true;
  }

  private static void dumpRuleClasses(BlazeRuntime runtime, PrintStream out) {
    ImmutableMap<String, RuleClass> ruleClassMap = runtime.getRuleClassProvider().getRuleClassMap();
    List<String> ruleClassNames = new ArrayList<>(ruleClassMap.keySet());
    Collections.sort(ruleClassNames);
    for (String name : ruleClassNames) {
      if (name.startsWith("$")) {
        continue;
      }
      RuleClass ruleClass = ruleClassMap.get(name);
      out.print(ruleClass + "(");
      boolean first = true;
      for (Attribute attribute : ruleClass.getAttributes()) {
        if (attribute.isImplicit()) {
          continue;
        }
        if (first) {
          first = false;
        } else {
          out.print(", ");
        }
        out.print(attribute.getName());
      }
      out.println(")");
    }
  }

  private static void dumpRuleStats(
      ExtendedEventHandler eventHandler,
      BlazeWorkspace workspace,
      SkyframeExecutor executor,
      PrintStream out)
      throws InterruptedException {
    SkyframeStats skyframeStats = executor.getSkyframeStats(eventHandler);
    if (skyframeStats.ruleStats().isEmpty()) {
      out.print("No rules in Bazel server, please run a build command first.");
      return;
    }
    ImmutableList<SkyKeyStats> rules = skyframeStats.ruleStats();
    ImmutableList<SkyKeyStats> aspects = skyframeStats.aspectStats();
    Map<String, RuleBytes> ruleBytes = new HashMap<>();
    Map<String, RuleBytes> aspectBytes = new HashMap<>();
    AllocationTracker allocationTracker = workspace.getAllocationTracker();
    if (allocationTracker != null) {
      allocationTracker.getRuleMemoryConsumption(ruleBytes, aspectBytes);
    }
    printRuleStatsOfType(rules, "RULE", out, ruleBytes, allocationTracker != null, false);
    printRuleStatsOfType(aspects, "ASPECT", out, aspectBytes, allocationTracker != null, true);
  }

  private static void printRuleStatsOfType(
      ImmutableList<SkyKeyStats> ruleStats,
      String type,
      PrintStream out,
      Map<String, RuleBytes> ruleToBytes,
      boolean bytesEnabled,
      boolean trimKey) {
    if (ruleStats.isEmpty()) {
      return;
    }
    // ruleStats are already sorted.
    int longestName =
        ruleStats.stream().map(r -> r.getName().length()).max(Integer::compareTo).get();
    int maxNameWidth = 30;
    int nameColumnWidth = Math.min(longestName, maxNameWidth);
    int numberColumnWidth = 10;
    int bytesColumnWidth = 13;
    int eachColumnWidth = 11;
    printWithPadding(out, type, nameColumnWidth);
    printWithPaddingBefore(out, "COUNT", numberColumnWidth);
    printWithPaddingBefore(out, "ACTIONS", numberColumnWidth);
    if (bytesEnabled) {
      printWithPaddingBefore(out, "BYTES", bytesColumnWidth);
      printWithPaddingBefore(out, "EACH", eachColumnWidth);
    }
    out.println();
    for (SkyKeyStats ruleStat : ruleStats) {
      printWithPadding(
          out, truncateName(ruleStat.getName(), trimKey, maxNameWidth), nameColumnWidth);
      printWithPaddingBefore(out, formatLong(ruleStat.getCount()), numberColumnWidth);
      printWithPaddingBefore(out, formatLong(ruleStat.getActionCount()), numberColumnWidth);
      if (bytesEnabled) {
        RuleBytes ruleBytes = ruleToBytes.get(ruleStat.getKey());
        long bytes = ruleBytes != null ? ruleBytes.getBytes() : 0L;
        printWithPaddingBefore(out, formatLong(bytes), bytesColumnWidth);
        printWithPaddingBefore(out, formatLong(bytes / ruleStat.getCount()), eachColumnWidth);
      }
      out.println();
    }
    out.println();
  }

  private static String truncateName(String name, boolean trimKey, int maxNameWidth) {
    // If this is an aspect, we'll chop off everything except the aspect name
    if (trimKey) {
      int dividerIndex = name.lastIndexOf('%');
      if (dividerIndex >= 0) {
        name = name.substring(dividerIndex + 1);
      }
    }
    if (name.length() <= maxNameWidth) {
      return name;
    }
    int starti = name.length() - maxNameWidth + "...".length();
    return "..." + name.substring(starti);
  }

  private static void printWithPadding(PrintStream out, String str, int columnWidth) {
    out.print(str);
    pad(out, columnWidth + 2, str.length());
  }

  private static void printWithPaddingBefore(PrintStream out, String str, int columnWidth) {
    pad(out, columnWidth, str.length());
    out.print(str);
    pad(out, 2, 0);
  }

  private static void pad(PrintStream out, int columnWidth, int consumed) {
    for (int i = 0; i < columnWidth - consumed; ++i) {
      out.print(' ');
    }
  }

  private static String formatLong(long number) {
    return String.format("%,d", number);
  }

  @Nullable
  private static BuildConfigurationKey getConfigurationKey(CommandEnvironment env, String hash) {
    if (hash == null) {
      // Use the target configuration
      return env.getSkyframeBuildView().getBuildConfiguration().getKey();
    }

    ImmutableList<BuildConfigurationKey> candidates =
        env.getSkyframeExecutor().getEvaluator().getDoneValues().entrySet().stream()
            .filter(e -> e.getKey().functionName().equals(SkyFunctions.BUILD_CONFIGURATION))
            .map(e -> (BuildConfigurationKey) e.getKey())
            .filter(k -> k.getOptions().checksum().startsWith(hash))
            .collect(ImmutableList.toImmutableList());

    if (candidates.size() != 1) {
      env.getReporter().error(null, "ambiguous configuration, use 'blaze config' to list them");
      return null;
    }

    return candidates.get(0);
  }

  @Nullable
  private static SkyKey getMemoryDumpSkyKey(CommandEnvironment env, MemoryMode memoryMode) {
    try {
      switch (memoryMode.type()) {
        case PACKAGE -> {
          return PackageIdentifier.parse(memoryMode.subject);
        }
        case STARLARK_MODULE -> {
          return BzlLoadValue.keyForBuild(Label.parseCanonical(memoryMode.subject));
        }
        case CONFIGURED_TARGET -> {
          String[] labelAndConfig = memoryMode.subject.split("@", 2);
          BuildConfigurationKey configurationKey =
              getConfigurationKey(env, labelAndConfig.length == 2 ? labelAndConfig[1] : null);
          return ConfiguredTargetKey.builder()
              .setConfigurationKey(configurationKey)
              .setLabel(Label.parseCanonical(labelAndConfig[0]))
              .build();
        }
      }
    } catch (LabelSyntaxException e) {
      env.getReporter().error(null, "Cannot parse label: " + e.getMessage());
      return null;
    }

    throw new IllegalStateException();
  }

  private static String jsonQuote(String s) {
    try {
      StringWriter writer = new StringWriter();
      JsonWriter json = new JsonWriter(writer);
      json.value(s);
      json.flush();
      return writer.toString();
    } catch (IOException e) {
      // StringWriter does no I/O
      throw new IllegalStateException(e);
    }
  }

  private static void dumpRamByClass(String prefix, Map<String, Long> memory, PrintStream out) {
    out.print("{");

    ImmutableList<Map.Entry<String, Long>> sorted =
        memory.entrySet().stream()
            .sorted(Comparator.comparing(Map.Entry<String, Long>::getValue).reversed())
            .collect(ImmutableList.toImmutableList());

    boolean first = true;
    for (Map.Entry<String, Long> entry : sorted) {
      out.printf(
          "%s\n%s  %s: %d", first ? "" : ",", prefix, jsonQuote(entry.getKey()), entry.getValue());
      first = false;
    }

    out.printf("\n%s}", prefix);
  }

  private static void addBuiltins(
      ConcurrentIdentitySet set, CommandEnvironment env, FieldCache fieldCache) {
    ObjectGraphTraverser traverser =
        new ObjectGraphTraverser(
            fieldCache,
            /* countInternedObjects= */ false,
            /* reportTransientFields= */ true,
            set,
            /* collectContext= */ false,
            ObjectGraphTraverser.NOOP_OBJECT_RECEIVER,
            /* instanceId= */ null);
    traverser.traverse(env.getRuntime().getRuleClassProvider());
  }

  private static Stats dumpRamShallow(
      InMemoryGraph graph,
      NodeEntry nodeEntry,
      MemoryMode mode,
      FieldCache fieldCache,
      ImmutableList<MemoryAccountant.Measurer> measurers,
      ConcurrentIdentitySet seen)
      throws InterruptedException {
    // Mark all objects accessible from direct dependencies. This will mutate seen, but that's OK.
    for (SkyKey directDepKey : nodeEntry.getDirectDeps()) {
      NodeEntry directDepEntry = graph.get(null, Reason.OTHER, directDepKey);
      ObjectGraphTraverser depTraverser =
          new ObjectGraphTraverser(
              fieldCache,
              false,
              mode.reportTransient,
              seen,
              false,
              ObjectGraphTraverser.NOOP_OBJECT_RECEIVER,
              null);
      depTraverser.traverse(directDepEntry.getValue());
    }

    // Now traverse the objects reachable from the given SkyValue. Objects reachable from direct
    // dependencies are in "seen" and thus will not be counted.
    return dumpRamReachable(nodeEntry, mode, fieldCache, measurers, seen);
  }

  private static Stats dumpRamReachable(
      NodeEntry nodeEntry,
      MemoryMode mode,
      FieldCache fieldCache,
      ImmutableList<MemoryAccountant.Measurer> measurers,
      ConcurrentIdentitySet seen)
      throws InterruptedException {
    MemoryAccountant memoryAccountant =
        new MemoryAccountant(measurers, mode.displayMode != MemoryDisplayMode.SUMMARY);
    ObjectGraphTraverser traverser =
        new ObjectGraphTraverser(
            fieldCache,
            true,
            mode.reportTransient,
            seen,
            true,
            memoryAccountant,
            null,
            mode.needle);
    traverser.traverse(nodeEntry.getValue());
    return memoryAccountant.getStats();
  }

  private static ListenableFuture<Void> processTransitive(
      BiConsumer<SkyKey, SkyValue> processor,
      InMemoryGraph graph,
      SkyKey skyKey,
      Executor executor,
      Map<SkyKey, ListenableFuture<Void>> futureMap) {

    // This is awkward, but preferable to plumbing this through scheduleDeps and processDeps
    SkyValue[] value = new SkyValue[1];

    // First get the SkyValue and the direct deps from the Skyframe graph. This happens in a future
    // so that processTransitive() (which is called from computeIfAbsent()) doesn't throw a
    // checked exception.
    ListenableFuture<Iterable<SkyKey>> fetchNodeData =
        Futures.submit(
            () -> {
              NodeEntry entry = graph.get(null, Reason.OTHER, skyKey);
              value[0] = entry.getValue();
              return entry.getDirectDeps();
            },
            executor);

    // This returns a list of futures representing processing the direct deps of this node
    ListenableFuture<ImmutableList<ListenableFuture<Void>>> scheduleDeps =
        Futures.transform(
            fetchNodeData,
            directDeps -> {
              List<ListenableFuture<Void>> depFutures = new ArrayList<>();
              for (SkyKey dep : directDeps) {
                // If the processing of this dependency has not been scheduled, do so
                depFutures.add(
                    futureMap.computeIfAbsent(
                        dep, k -> processTransitive(processor, graph, dep, executor, futureMap)));
              }
              return ImmutableList.copyOf(depFutures);
            },
            executor);

    // This is a future that gets completed when the direct deps have all been processed...
    ListenableFuture<List<Void>> processDeps =
        Futures.transformAsync(scheduleDeps, Futures::allAsList, executor);

    // ...and when that's the case, we can proceed with processing this node in turn.
    return Futures.transform(
        processDeps,
        unused -> {
          processor.accept(skyKey, value[0]);
          return null;
        },
        executor);
  }

  private static ExecutorService createRamDumpExecutor() {
    return Executors.newFixedThreadPool(
        Runtime.getRuntime().availableProcessors(),
        new ThreadFactoryBuilder().setNameFormat("dump-ram-%d").build());
  }

  private static Stats dumpRamTransitive(
      InMemoryGraph graph,
      SkyKey skyKey,
      MemoryMode mode,
      FieldCache fieldCache,
      ImmutableList<MemoryAccountant.Measurer> measurers,
      ConcurrentIdentitySet seen)
      throws InterruptedException {

    MemoryAccountant memoryAccountant =
        new MemoryAccountant(measurers, mode.displayMode != MemoryDisplayMode.SUMMARY);
    BiConsumer<SkyKey, SkyValue> processor =
        (unused, skyValue) -> {
          ObjectGraphTraverser traverser =
              new ObjectGraphTraverser(
                  fieldCache,
                  false,
                  mode.reportTransient,
                  seen,
                  true,
                  memoryAccountant,
                  null,
                  mode.needle);
          traverser.traverse(skyValue);
        };

    try (ExecutorService executor = createRamDumpExecutor()) {
      ListenableFuture<Void> work =
          processTransitive(processor, graph, skyKey, executor, new ConcurrentHashMap<>());
      work.get();
    } catch (ExecutionException e) {
      throw new IllegalStateException(e);
    }

    return memoryAccountant.getStats();
  }

  private static Optional<BlazeCommandResult> dumpRamFull(
      CommandEnvironment env,
      DumpOptions dumpOptions,
      PrintStream out,
      InMemoryGraph graph,
      FieldCache fieldCache,
      ImmutableList<MemoryAccountant.Measurer> measurers)
      throws InterruptedException {

    ImmutableList<SkyKey> roots =
        graph.getAllNodeEntries().parallelStream()
            .filter(e -> Iterables.isEmpty(e.getReverseDepsForDoneEntry()))
            .map(InMemoryNodeEntry::getKey)
            .collect(ImmutableList.toImmutableList());

    // Profiling shows that the average object count for a Skyframe node is around 30-40. Let's
    // go with 48 to avoid a potentially costly resize.
    ConcurrentIdentitySet seenObjects =
        new ConcurrentIdentitySet(graph.getAllNodeEntries().size() * 48);
    addBuiltins(seenObjects, env, fieldCache);

    ConcurrentHashMap<SkyKey, Stats> nodeStats = new ConcurrentHashMap<>();

    BiConsumer<SkyKey, SkyValue> processor =
        (skyKey, skyValue) -> {
          MemoryAccountant memoryAccountant =
              new MemoryAccountant(
                  measurers, dumpOptions.memory.displayMode != MemoryDisplayMode.SUMMARY);
          ObjectGraphTraverser traverser =
              new ObjectGraphTraverser(
                  fieldCache,
                  true,
                  dumpOptions.memory.reportTransient,
                  seenObjects,
                  true,
                  memoryAccountant,
                  skyKey,
                  dumpOptions.memory.needle);
          traverser.traverse(skyValue);
          Stats stats = memoryAccountant.getStats();
          nodeStats.put(skyKey, stats);
        };

    try (ExecutorService executor = createRamDumpExecutor()) {
      ConcurrentHashMap<SkyKey, ListenableFuture<Void>> futureMap =
          new ConcurrentHashMap<>(128, 0.75f, Runtime.getRuntime().availableProcessors());
      ImmutableList<ListenableFuture<Void>> rootFutures =
          roots.stream()
              .map(l -> processTransitive(processor, graph, l, executor, futureMap))
              .collect(ImmutableList.toImmutableList());

      ListenableFuture<List<Void>> completion = Futures.allAsList(rootFutures);
      completion.get();
    } catch (ExecutionException e) {
      return Optional.of(
          createFailureResult(
              "Error during traversal: " + e.getMessage(), Code.SKYFRAME_MEMORY_DUMP_FAILED));
    }

    var sortedStats =
        nodeStats.entrySet().stream()
            .parallel()
            .map(e -> Pair.of(e.getKey().getCanonicalName(), e.getValue()))
            .sorted(Comparator.comparing(Pair::getFirst))
            .collect(ImmutableList.toImmutableList());

    out.print("{");
    boolean first = true;
    for (Pair<String, Stats> p : sortedStats) {
      out.printf("%s\n  %s: ", first ? "" : ",", jsonQuote(p.getFirst()));
      Stats v = p.getSecond();
      first = false;
      switch (dumpOptions.memory.displayMode) {
        case SUMMARY ->
            out.printf("{ \"objects\": %d, \"bytes\": %d }", v.getObjectCount(), v.getMemoryUse());
        case COUNT -> dumpRamByClass("  ", v.getObjectCountByClass(), out);
        case BYTES -> dumpRamByClass("  ", v.getMemoryByClass(), out);
      }
    }
    out.println("\n}");
    return Optional.empty();
  }

  private static Optional<BlazeCommandResult> dumpSkyframeMemory(
      CommandEnvironment env, DumpOptions dumpOptions, PrintStream out)
      throws InterruptedException {
    BuildObjectTraverser buildObjectTraverser =
        new BuildObjectTraverser(
            dumpOptions.memory.reportConfiguration,
            dumpOptions.memory.reportPrecomputed,
            dumpOptions.memory.reportWorkspaceStatus);
    CollectionObjectTraverser collectionObjectTraverser = new CollectionObjectTraverser();
    FieldCache fieldCache =
        new FieldCache(ImmutableList.of(buildObjectTraverser, collectionObjectTraverser));
    ImmutableList<MemoryAccountant.Measurer> measurers =
        ImmutableList.of(collectionObjectTraverser);
    InMemoryGraph graph = env.getSkyframeExecutor().getEvaluator().getInMemoryGraph();

    if (dumpOptions.memory.collectionMode == MemoryCollectionMode.FULL) {
      // FULL mode doesn't have SkyKey as an argument, nor does it need a NodeEntry.
      return dumpRamFull(env, dumpOptions, out, graph, fieldCache, measurers);
    }

    SkyKey skyKey = getMemoryDumpSkyKey(env, dumpOptions.memory);
    if (skyKey == null) {
      return Optional.of(
          createFailureResult("Cannot dump Skyframe memory", Code.SKYFRAME_MEMORY_DUMP_FAILED));
    }

    NodeEntry nodeEntry = graph.get(null, Reason.OTHER, skyKey);
    if (nodeEntry == null) {
      env.getReporter().error(null, "The requested node is not present.");
      return Optional.of(
          createFailureResult(
              "The requested node is not present", Code.SKYFRAME_MEMORY_DUMP_FAILED));
    }

    ConcurrentIdentitySet seen = new ConcurrentIdentitySet(1);
    addBuiltins(seen, env, fieldCache);
    Stats stats =
        switch (dumpOptions.memory.collectionMode) {
          case DEEP -> dumpRamReachable(nodeEntry, dumpOptions.memory, fieldCache, measurers, seen);
          case SHALLOW ->
              dumpRamShallow(graph, nodeEntry, dumpOptions.memory, fieldCache, measurers, seen);
          case TRANSITIVE ->
              dumpRamTransitive(graph, skyKey, dumpOptions.memory, fieldCache, measurers, seen);
          case FULL -> throw new IllegalStateException();
        };

    switch (dumpOptions.memory.displayMode) {
      case SUMMARY ->
          out.printf("%d objects, %d bytes retained", stats.getObjectCount(), stats.getMemoryUse());
      case COUNT -> dumpRamByClass("", stats.getObjectCountByClass(), out);
      case BYTES -> dumpRamByClass("", stats.getMemoryByClass(), out);
    }

    out.println();
    return Optional.empty();
  }

  private static void dumpStarlarkHeap(BlazeWorkspace workspace, String path, PrintStream out)
      throws IOException {
    AllocationTracker allocationTracker = workspace.getAllocationTracker();
    if (allocationTracker == null) {
      out.println(
          "Cannot dump Starlark heap without running in memory tracking mode. "
              + "Please refer to the user manual for the dump commnd "
              + "for information how to turn on memory tracking.");
      return;
    }
    out.println("Dumping Starlark heap to: " + path);
    allocationTracker.dumpStarlarkAllocations(path);
  }

  private static BlazeCommandResult createFailureResult(String message, Code detailedCode) {
    return BlazeCommandResult.failureDetail(
        FailureDetail.newBuilder()
            .setMessage(message)
            .setDumpCommand(FailureDetails.DumpCommand.newBuilder().setCode(detailedCode))
            .build());
  }
}
