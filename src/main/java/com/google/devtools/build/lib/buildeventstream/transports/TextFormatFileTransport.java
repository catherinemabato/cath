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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.devtools.build.lib.buildeventstream.ArtifactGroupNamer;
import com.google.devtools.build.lib.buildeventstream.BuildEvent;
import com.google.devtools.build.lib.buildeventstream.BuildEventContext;
import com.google.devtools.build.lib.buildeventstream.BuildEventProtocolOptions;
import com.google.devtools.build.lib.buildeventstream.BuildEventArtifactUploader;
import com.google.devtools.build.lib.buildeventstream.BuildEventTransport;
import com.google.devtools.build.lib.buildeventstream.PathConverter;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.protobuf.TextFormat;
import java.io.IOException;
import java.util.function.Consumer;

/**
 * A simple {@link BuildEventTransport} that writes the text representation of the protocol-buffer
 * representation of the events to a file.
 *
 * <p>This class is used for debugging.
 */
public final class TextFormatFileTransport extends FileTransport {
  private final BuildEventProtocolOptions options;

  TextFormatFileTransport(
      String path,
      BuildEventProtocolOptions options,
      BuildEventArtifactUploader uploader,
      Consumer<AbruptExitException> exitFunc)
      throws IOException {
    super(path, uploader, exitFunc);
    this.options = options;
  }

  @Override
  public String name() {
    return this.getClass().getSimpleName();
  }

  @Override
  public synchronized void sendBuildEvent(BuildEvent event, final ArtifactGroupNamer namer) {
    checkNotNull(event);
    PathConverter pathConverter = uploadReferencedArtifacts(event.referencedArtifacts());
    if (pathConverter == null) {
      return;
    }

    BuildEventContext converters =
        new BuildEventContext() {
          @Override
          public PathConverter pathConverter() {
            return pathConverter;
          }

          @Override
          public ArtifactGroupNamer artifactGroupNamer() {
            return namer;
          }

          @Override
          public BuildEventProtocolOptions getOptions() {
            return options;
          }
        };
    String protoTextRepresentation = TextFormat.printToString(event.asStreamProto(converters));
    write("event {\n" + protoTextRepresentation + "}\n\n");
  }

  @Override
  public TransportKind kind() {
    return TransportKind.BEP_FILE;
  }
}
