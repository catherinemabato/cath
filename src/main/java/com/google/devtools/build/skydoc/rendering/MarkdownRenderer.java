// Copyright 2018 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.skydoc.rendering;

import com.google.devtools.build.skydoc.rendering.proto.StardocOutputProtos.ProviderInfo;
import com.google.devtools.build.skydoc.rendering.proto.StardocOutputProtos.RuleInfo;
import com.google.devtools.build.skydoc.rendering.proto.StardocOutputProtos.UserDefinedFunctionInfo;
import java.io.IOException;
import java.io.StringWriter;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.exception.MethodInvocationException;
import org.apache.velocity.exception.ParseErrorException;
import org.apache.velocity.exception.ResourceNotFoundException;
import org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader;
import org.apache.velocity.runtime.resource.loader.JarResourceLoader;

/**
 * Produces skydoc output in markdown form.
 */
public class MarkdownRenderer {

  private final String headerTemplateFilename;
  private final String ruleTemplateFilename;
  private final String providerTemplateFilename;
  private final String functionTemplateFilename;

  private final VelocityEngine velocityEngine;

  public MarkdownRenderer(
      String headerTemplate,
      String ruleTemplate,
      String providerTemplate,
      String functionTemplate) {
    this.headerTemplateFilename = headerTemplate;
    this.ruleTemplateFilename = ruleTemplate;
    this.providerTemplateFilename = providerTemplate;
    this.functionTemplateFilename = functionTemplate;

    this.velocityEngine = new VelocityEngine();
    velocityEngine.setProperty("resource.loader", "classpath, jar");
    velocityEngine.setProperty("classpath.resource.loader.class",
        ClasspathResourceLoader.class.getName());
    velocityEngine.setProperty("jar.resource.loader.class", JarResourceLoader.class.getName());
    velocityEngine.setProperty("input.encoding", "UTF-8");
    velocityEngine.setProperty("output.encoding", "UTF-8");
    velocityEngine.setProperty("runtime.references.strict", true);
  }

  /**
   * Returns a markdown header string that should appear at the top of all markdown files generated
   * by Stardoc.
   */
  public String renderMarkdownHeader() throws IOException {
    StringWriter stringWriter = new StringWriter();
    try {
      velocityEngine.mergeTemplate(
          headerTemplateFilename, "UTF-8", new VelocityContext(), stringWriter);
    } catch (ResourceNotFoundException | ParseErrorException | MethodInvocationException e) {
      throw new IOException(e);
    }
    return stringWriter.toString();
  }

  /**
   * Returns a markdown rendering of rule documentation for the given rule information object with
   * the given rule name.
   */
  public String render(String ruleName, RuleInfo ruleInfo) throws IOException {
    VelocityContext context = new VelocityContext();
    context.put("util", new MarkdownUtil());
    context.put("ruleName", ruleName);
    context.put("ruleInfo", ruleInfo);

    StringWriter stringWriter = new StringWriter();
    try {
      velocityEngine.mergeTemplate(ruleTemplateFilename, "UTF-8", context, stringWriter);
    } catch (ResourceNotFoundException | ParseErrorException | MethodInvocationException e) {
      throw new IOException(e);
    }
    return stringWriter.toString();
  }

  /**
   * Returns a markdown rendering of provider documentation for the given provider information
   * object with the given name.
   */
  public String render(String providerName, ProviderInfo providerInfo) throws IOException {
    VelocityContext context = new VelocityContext();
    context.put("util", new MarkdownUtil());
    context.put("providerName", providerName);
    context.put("providerInfo", providerInfo);

    StringWriter stringWriter = new StringWriter();
    try {
      velocityEngine.mergeTemplate(providerTemplateFilename, "UTF-8", context, stringWriter);
    } catch (ResourceNotFoundException | ParseErrorException | MethodInvocationException e) {
      throw new IOException(e);
    }
    return stringWriter.toString();
  }

  /**
   * Returns a markdown rendering of a user-defined function's documentation for the function info
   * object.
   */
  public String render(UserDefinedFunctionInfo functionInfo) throws IOException {
    VelocityContext context = new VelocityContext();
    context.put("util", new MarkdownUtil());
    context.put("funcInfo", functionInfo);

    StringWriter stringWriter = new StringWriter();
    try {
      velocityEngine.mergeTemplate(functionTemplateFilename, "UTF-8", context, stringWriter);
    } catch (ResourceNotFoundException | ParseErrorException | MethodInvocationException e) {
      throw new IOException(e);
    }
    return stringWriter.toString();
  }
}
