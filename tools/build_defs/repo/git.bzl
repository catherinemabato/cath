# Copyright 2015 The Bazel Authors. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""Rules for cloning external git repositories."""

load("@bazel_tools//tools/build_defs/repo:utils.bzl", "patch", "update_attrs", "workspace_and_buildfile")
load("@bazel_tools//tools/build_defs/repo:utils.bzl", "remove_dir")
load("@bazel_tools//tools/build_defs/repo:git_worker.bzl", "git_repo")

def _clone_or_update(ctx):
    if ((not ctx.attr.tag and not ctx.attr.commit and not ctx.attr.branch) or
        (ctx.attr.tag and ctx.attr.commit) or
        (ctx.attr.tag and ctx.attr.branch) or
        (ctx.attr.commit and ctx.attr.branch)):
        fail("Exactly one of commit, tag, or branch must be provided")

    root = ctx.path(".")
    directory = str(root)
    if ctx.attr.strip_prefix:
        directory = directory + "-tmp"

    git_ = git_repo(ctx, directory)

    if ctx.attr.strip_prefix:
        dest_link = "{}/{}".format(directory, ctx.attr.strip_prefix)
        if not ctx.path(dest_link).exists:
            fail("strip_prefix at {} does not exist in repo".format(ctx.attr.strip_prefix))
        remove_dir(ctx, root)
        ctx.symlink(dest_link, root)

    return {"commit": git_.commit, "shallow_since": git_.shallow_since}

def _update_git_attrs(orig, keys, override):
    result = update_attrs(orig, keys, override)

    # if we found the actual commit, remove all other means of specifying it,
    # like tag or branch.
    if "commit" in result:
        result.pop("tag", None)
        result.pop("branch", None)
    return result

_common_attrs = {
    "remote": attr.string(mandatory = True),
    "commit": attr.string(default = ""),
    "shallow_since": attr.string(default = ""),
    "tag": attr.string(default = ""),
    "branch": attr.string(default = ""),
    "init_submodules": attr.bool(default = False),
    "verbose": attr.bool(default = False),
    "strip_prefix": attr.string(default = ""),
    "patches": attr.label_list(default = []),
    "patch_tool": attr.string(default = "patch"),
    "patch_args": attr.string_list(default = ["-p0"]),
    "patch_cmds": attr.string_list(default = []),
}

_new_git_repository_attrs = dict(_common_attrs.items() + {
    "build_file": attr.label(allow_single_file = True),
    "build_file_content": attr.string(),
    "workspace_file": attr.label(),
    "workspace_file_content": attr.string(),
}.items())

def _new_git_repository_implementation(ctx):
    if ((not ctx.attr.build_file and not ctx.attr.build_file_content) or
        (ctx.attr.build_file and ctx.attr.build_file_content)):
        fail("Exactly one of build_file and build_file_content must be provided.")
    update = _clone_or_update(ctx)
    workspace_and_buildfile(ctx)
    patch(ctx)
    remove_dir(ctx, ctx.path(".git"))
    return _update_git_attrs(ctx.attr, _new_git_repository_attrs.keys(), update)

def _git_repository_implementation(ctx):
    update = _clone_or_update(ctx)
    patch(ctx)
    remove_dir(ctx, ctx.path(".git"))
    return _update_git_attrs(ctx.attr, _common_attrs.keys(), update)

new_git_repository = repository_rule(
    implementation = _new_git_repository_implementation,
    attrs = _new_git_repository_attrs,
)
"""Clone an external git repository.

Clones a Git repository, checks out the specified tag, or commit, and
makes its targets available for binding. Also determine the id of the
commit actually checked out and its date, and return a dict with parameters
that provide a reproducible version of this rule (which a tag not necessarily
is).

Args:
  name: A unique name for this repository.

  build_file: The file to use as the BUILD file for this repository.
    Either build_file or build_file_content must be specified.

    This attribute is an absolute label (use '@//' for the main repo). The file
    does not need to be named BUILD, but can be (something like
    BUILD.new-repo-name may work well for distinguishing it from the
    repository's actual BUILD files.

  build_file_content: The content for the BUILD file for this repository.
    Either build_file or build_file_content must be specified.

  workspace_file: The file to use as the `WORKSPACE` file for this repository.

    Either `workspace_file` or `workspace_file_content` can be specified, or
    neither, but not both.
  workspace_file_content: The content for the WORKSPACE file for this repository.

    Either `workspace_file` or `workspace_file_content` can be specified, or
    neither, but not both.
  branch: branch in the remote repository to checked out

  tag: tag in the remote repository to checked out

  commit: specific commit to be checked out
    Precisely one of branch, tag, or commit must be specified.

  shallow_since: an optional date, not after the specified commit; the
    argument is not allowed if a tag is specified (which allows cloning
    with depth 1). Setting such a date close to the specified commit
    allows for a more shallow clone of the repository, saving bandwidth and
    wall-clock time.

  init_submodules: Whether to clone submodules in the repository.

  remote: The URI of the remote Git repository.

  strip_prefix: A directory prefix to strip from the extracted files.

  patches: A list of files that are to be applied as patches after extracting
    the archive.
  patch_tool: the patch(1) utility to use.
  patch_args: arguments given to the patch tool, defaults to ["-p0"]
  patch_cmds: sequence of commands to be applied after patches are applied.
"""

git_repository = repository_rule(
    implementation = _git_repository_implementation,
    attrs = _common_attrs,
)
"""Clone an external git repository.

Clones a Git repository, checks out the specified tag, or commit, and
makes its targets available for binding. Also determine the id of the
commit actually checked out and its date, and return a dict with parameters
that provide a reproducible version of this rule (which a tag not necessarily
is).


Args:
  name: A unique name for this repository.

  init_submodules: Whether to clone submodules in the repository.

  remote: The URI of the remote Git repository.

  branch: branch in the remote repository to checked out

  tag: tag in the remote repository to checked out

  commit: specific commit to be checked out
    Precisely one of branch, tag, or commit must be specified.

  shallow_since: an optional date in the form YYYY-MM-DD, not after
    the specified commit; the argument is not allowed if a tag is specified
    (which allows cloning with depth 1). Setting such a date close to the
    specified commit allows for a more shallow clone of the repository, saving
    bandwidth and wall-clock time.

  strip_prefix: A directory prefix to strip from the extracted files.

  patches: A list of files that are to be applied as patches after extracting
    the archive.
  patch_tool: the patch(1) utility to use.
  patch_args: arguments given to the patch tool, defaults to ["-p0"]
  patch_cmds: sequence of commands to be applied after patches are applied.
"""
