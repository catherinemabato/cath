# Copyright 2018 The Bazel Authors. All rights reserved.
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
"""Utils for manipulating external repositories, once fetched.

### Setup

These utility are intended to be used by other repository rules. They
can be loaded as follows.

```python
load(
    "@bazel_tools//tools/build_defs/repo:utils.bzl",
    "workspace_and_buildfile",
    "patch",
    "update_attrs",
)
```
"""

def workspace_and_buildfile(ctx):
    """Utility function for writing WORKSPACE and, if requested, a BUILD file.

    This rule is inteded to be used in the implementation function of a
    repository rule.
    It assumes the parameters `name`, `build_file`, `build_file_contents`,
    `workspace_file`, and `workspace_file_content` to be
    present in `ctx.attr`, the latter four possibly with value None.

    Args:
      ctx: The repository context of the repository rule calling this utility
        function.
    """
    if ctx.attr.build_file and ctx.attr.build_file_content:
        ctx.fail("Only one of build_file and build_file_content can be provided.")

    if ctx.attr.workspace_file and ctx.attr.workspace_file_content:
        ctx.fail("Only one of workspace_file and workspace_file_content can be provided.")

    if ctx.attr.workspace_file:
        ctx.delete("WORKSPACE")
        ctx.symlink(ctx.attr.workspace_file, "WORKSPACE")
    elif ctx.attr.workspace_file_content:
        ctx.delete("WORKSPACE")
        ctx.file("WORKSPACE", ctx.attr.workspace_file_content)
    else:
        ctx.file("WORKSPACE", "workspace(name = \"{name}\")\n".format(name = ctx.name))

    if ctx.attr.build_file:
        ctx.delete("BUILD.bazel")
        ctx.symlink(ctx.attr.build_file, "BUILD.bazel")
    elif ctx.attr.build_file_content:
        ctx.delete("BUILD.bazel")
        ctx.file("BUILD.bazel", ctx.attr.build_file_content)

def _is_windows(ctx):
    return ctx.os.name.lower().find("windows") != -1

def _use_native_patch(patch_tool, patch_args):
    """If patch_tool is empty and patch_args only contains -p<NUM> options, we use the native patch implementation."""
    if patch_tool:
        return False
    for arg in patch_args:
        if not arg.startswith("-p"):
            return False
    return True

def patch(ctx, patches=None, patch_cmds=None, patch_cmds_win=None, patch_tool=None, patch_args=None):
    """Implementation of patching an already extracted repository.

    This rule is inteded to be used in the implementation function of
    a repository rule. Ifthe parameters `patches`, `patch_tool`,
    `patch_args`, `patch_cmds` and `patch_cmds_win` are not specified
    then they are taken from `ctx.attr`.

    Args:
      ctx: The repository context of the repository rule calling this utility
        function.
      patches: The patch files to apply. List of strings, Labels, or paths.
      patch_cmds: Bash commands to run for patching, passed one at a
        time to bash -c. List of strings
      patch_cmds_win: Powershell commands to run for patching, passed
        one at a time to powershell /c. List of strings.
      patch_tool: Path of the patch tool to execute for applying
        patches. String.
      patch_args: Arguments to pass to the patch tool. List of strings.

    """
    bash_exe = ctx.os.environ["BAZEL_SH"] if "BAZEL_SH" in ctx.os.environ else "bash"
    powershell_exe = ctx.os.environ["BAZEL_POWERSHELL"] if "BAZEL_POWERSHELL" in ctx.os.environ else "powershell.exe"
    if patches is None:
        patches = ctx.attr.patches
    if patch_cmds is None:
        patch_cmds = ctx.attr.patch_cmds
    if patch_cmds_win is None:
        patch_cmds_win = ctx.attr.patch_cmds_win
    if patch_tool is None:
        patch_tool = ctx.attr.patch_tool
    if patch_args is None:
        patch_args = ctx.attr.patch_args

    if len(patches) > 0 or len(patch_cmds) > 0:
        ctx.report_progress("Patching repository")

    if _use_native_patch(patch_tool, patch_args):
        if patch_args:
            strip = int(patch_args[-1][2:])
        else:
            strip = 0
        for patchfile in patches:
            ctx.patch(patchfile, strip)
    else:
        for patchfile in patches:
            patch_tool = patch_tool
            if not patch_tool:
                patch_tool = "patch"
            command = "{patchtool} {patch_args} < {patchfile}".format(
                patchtool = patch_tool,
                patchfile = ctx.path(patchfile),
                patch_args = " ".join([
                    "'%s'" % arg
                    for arg in patch_args
                ]),
            )
            st = ctx.execute([bash_exe, "-c", command])
            if st.return_code:
                fail("Error applying patch %s:\n%s%s" %
                     (str(patchfile), st.stderr, st.stdout))

    if _is_windows(ctx) and hasattr(ctx.attr, "patch_cmds_win") and patch_cmds_win:
        for cmd in patch_cmds_win:
            st = ctx.execute([powershell_exe, "/c", cmd])
            if st.return_code:
                fail("Error applying patch command %s:\n%s%s" %
                     (cmd, st.stdout, st.stderr))
    else:
        for cmd in patch_cmds:
            st = ctx.execute([bash_exe, "-c", cmd])
            if st.return_code:
                fail("Error applying patch command %s:\n%s%s" %
                     (cmd, st.stdout, st.stderr))

def update_attrs(orig, keys, override):
    """Utility function for altering and adding the specified attributes to a particular repository rule invocation.

     This is used to make a rule reproducible.

    Args:
        orig: dict of actually set attributes (either explicitly or implicitly)
            by a particular rule invocation
        keys: complete set of attributes defined on this rule
        override: dict of attributes to override or add to orig

    Returns:
        dict of attributes with the keys from override inserted/updated
    """
    result = {}
    for key in keys:
        if getattr(orig, key) != None:
            result[key] = getattr(orig, key)
    result["name"] = orig.name
    result.update(override)
    return result

def maybe(repo_rule, name, **kwargs):
    """Utility function for only adding a repository if it's not already present.

    This is to implement safe repositories.bzl macro documented in
    https://docs.bazel.build/versions/master/skylark/deploying.html#dependencies.

    Args:
        repo_rule: repository rule function.
        name: name of the repository to create.
        **kwargs: remaining arguments that are passed to the repo_rule function.

    Returns:
        Nothing, defines the repository when needed as a side-effect.
    """
    if not native.existing_rule(name):
        repo_rule(name = name, **kwargs)
