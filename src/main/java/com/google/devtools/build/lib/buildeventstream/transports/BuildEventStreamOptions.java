// Copyright 2016 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.buildeventstream.transports;

import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionDocumentationCategory;
import com.google.devtools.common.options.OptionEffectTag;
import com.google.devtools.common.options.OptionsBase;

/** Options used to configure BuildEventStreamer and its BuildEventTransports. */
public class BuildEventStreamOptions extends OptionsBase {

  @Option(
      name = "build_event_text_file",
      oldName = "experimental_build_event_text_file",
      defaultValue = "",
      documentationCategory = OptionDocumentationCategory.LOGGING,
      effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
      help =
          "If non-empty, write a textual representation of the build event protocol to that file")
  public String buildEventTextFile;

  @Option(
      name = "build_event_binary_file",
      oldName = "experimental_build_event_binary_file",
      defaultValue = "",
      documentationCategory = OptionDocumentationCategory.LOGGING,
      effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
      help =
          "If non-empty, write a varint delimited binary representation of representation of the"
              + " build event protocol to that file.")
  public String buildEventBinaryFile;

  @Option(
      name = "build_event_json_file",
      oldName = "experimental_build_event_json_file",
      defaultValue = "",
      documentationCategory = OptionDocumentationCategory.LOGGING,
      effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
      help = "If non-empty, write a JSON serialisation of the build event protocol to that file.")
  public String buildEventJsonFile;

  @Option(
      name = "build_event_text_file_path_conversion",
      oldName = "experimental_build_event_text_file_path_conversion",
      defaultValue = "true",
      deprecationWarning =
          "The flag is no longer supported and will be removed in a future release.",
      documentationCategory = OptionDocumentationCategory.LOGGING,
      effectTags = {OptionEffectTag.NO_OP},
      help = "This flag has no effect and will be deprecated in a future release.")
  public boolean buildEventTextFilePathConversion;

  @Option(
      name = "build_event_binary_file_path_conversion",
      oldName = "experimental_build_event_binary_file_path_conversion",
      defaultValue = "true",
      deprecationWarning =
          "The flag is no longer supported and will be removed in a future release.",
      documentationCategory = OptionDocumentationCategory.LOGGING,
      effectTags = {OptionEffectTag.NO_OP},
      help = "This flag has no effect and will be deprecated in a future release.")
  public boolean buildEventBinaryFilePathConversion;

  @Option(
      name = "experimental_build_event_json_file_path_conversion",
      oldName = "build_event_json_file_path_conversion",
      defaultValue = "true",
      deprecationWarning =
          "The flag is no longer supported and will be removed in a future release.",
      documentationCategory = OptionDocumentationCategory.LOGGING,
      effectTags = {OptionEffectTag.NO_OP},
      help = "This flag has no effect and will be deprecated in a future release.")
  public boolean buildEventJsonFilePathConversion;

  @Option(
      name = "build_event_publish_all_actions",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.LOGGING,
      effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
      help = "Whether all actions should be published.")
  public boolean publishAllActions;

  @Option(
      name = "build_event_max_named_set_of_file_entries",
      defaultValue = "-1",
      documentationCategory = OptionDocumentationCategory.LOGGING,
      effectTags = {OptionEffectTag.AFFECTS_OUTPUTS},
      help =
          "The maximum number of entries for a single named_set_of_files event; values smaller "
              + "than 2 are ignored and no event splitting is performed. This is intended for limiting "
              + "the maximum event size in the build event protocol, although it does not directly "
              + "control event size. The total event size is a function of the structure of the set "
              + "as well as the file and uri lengths, which may in turn depend on the hash function.")
  public int maxNamedSetEntries;

  // TODO(ruperts): Remove these public getter methods for consistency with other options classes?
  public String getBuildEventTextFile() {
    return buildEventTextFile;
  }

  public String getBuildEventBinaryFile() {
    return buildEventBinaryFile;
  }

  public String getBuildEventJsonFile() {
    return buildEventJsonFile;
  }
}
