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

package com.google.devtools.build.lib.query2.query.output;

import com.google.devtools.build.lib.packages.DependencyFilter;
import com.google.devtools.build.lib.packages.InputFile;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.query2.common.CommonQueryOptions;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import java.util.Comparator;
import javax.annotation.Nullable;
import net.starlark.java.syntax.Location;

/**
 * Given a set of query options, returns a BinaryPredicate suitable for passing to {@link
 * Rule#getLabels()}, {@link XmlOutputFormatter}, etc.
 */
class FormatUtils {

  private FormatUtils() {}

  static DependencyFilter getDependencyFilter(CommonQueryOptions queryOptions) {
    if (queryOptions.includeToolDeps) {
      return queryOptions.includeImplicitDeps
          ? DependencyFilter.ALL_DEPS
          : DependencyFilter.NO_IMPLICIT_DEPS;
    }
    return queryOptions.includeImplicitDeps
        ? DependencyFilter.ONLY_TARGET_DEPS
        : DependencyFilter.and(
            DependencyFilter.NO_IMPLICIT_DEPS, DependencyFilter.ONLY_TARGET_DEPS);
  }

  /** An ordering of Targets based on the ordering of their labels. */
  static class TargetOrdering implements Comparator<Target> {
    @Override
    public int compare(Target o1, Target o2) {
      return o1.getLabel().compareTo(o2.getLabel());
    }
  }

  /**
   * Returns the target location string, optionally relative to its package's source root directory.
   */
  static String getLocation(Target target, boolean relative) {
    Location loc = target.getLocation();

    if (relative) {
      loc = getRootRelativeLocation(loc, target.getPackage());
    }
    return loc.toString();
  }

  /**
   * Returns the target location string, optionally relative to its package's source root directory
   * and optionally to display the location of source files.
   *
   * @param relative flag to display the location relative to its package's source root directory.
   * @param displaySourceFileLocation flag to display the location of line 1 of the actual source
   *    file instead of its location in the BUILD file.
   */
  static String getLocation(Target target, boolean relative, boolean displaySourceFileLocation) {
    Location loc = target.getLocation();
    if (target instanceof InputFile && displaySourceFileLocation) {
      PathFragment packageDir = target.getPackage().getPackageDirectory().asFragment();
      loc = Location.fromFileLineColumn(packageDir.getRelative(target.getName()).toString(), 1, 1);
    }
    if (relative) {
      loc = getRootRelativeLocation(loc, target.getPackage());
    }
    return loc.toString();
  }

  /**
   * Returns the specified location relative to the optional package's source root directory, if
   * available.
   */
  static Location getRootRelativeLocation(Location location, @Nullable Package base) {
    if (base != null
        && base.getSourceRoot().isPresent()) { // !isPresent => WORKSPACE pseudo-package
      Root root = base.getSourceRoot().get();
      PathFragment file = PathFragment.create(location.file());
      if (root.contains(file)) {
        PathFragment rel = root.relativize(file);
        location = Location.fromFileLineColumn(rel.toString(), location.line(), location.column());
      }
    }
    return location;
  }
}
