# Copyright 2021 The Bazel Authors. All rights reserved.
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

"""Definition of proto_common module, together with bazel providers for proto rules."""

ProtoLangToolchainInfo = provider(
    doc = "Specifies how to generate language-specific code from .proto files. Used by LANG_proto_library rules.",
    fields = dict(
        out_replacement_format_flag = "(str) Format string used when passing output to the plugin used by proto compiler.",
        plugin_format_flag = "(str) Format string used when passing plugin to proto compiler.",
        plugin = "(FilesToRunProvider) Proto compiler plugin.",
        runtime = "(Target) Runtime.",
        provided_proto_sources = "(list[ProtoSource]) Proto sources provided by the toolchain.",
        proto_compiler = "(FilesToRunProvider) Proto compiler.",
        protoc_opts = "(list[str]) Options to pass to proto compiler.",
        progress_message = "(str) Progress message to set on the proto compiler action.",
        mnemonic = "(str) Mnemonic to set on the proto compiler action.",
    ),
)

def _proto_path_flag(path):
    if path == ".":
        return None
    return "--proto_path=%s" % path

def _Iimport_path_equals_fullpath(proto_source):
    return "-I%s=%s" % (proto_source.import_path(), proto_source.source_file().path)

def _compile(
        actions,
        proto_library_target,
        proto_lang_toolchain_info,
        generated_files,
        plugin_output = None,
        additional_args = None,
        additional_tools = [],
        additional_inputs = depset(),
        resource_set = None):
    """Creates proto compile action for compiling *.proto files to language specific sources.

    Args:
      actions: (ActionFactory)  Obtained by ctx.actions, used to register the actions.
      proto_library_target:  (Target) The proto_library to generate the sources for.
        Obtained as the `target` parameter from an aspect's implementation.
      proto_lang_toolchain_info: (ProtoLangToolchainInfo) The proto lang toolchain info.
        Obtained from a `proto_lang_toolchain` target or constructed ad-hoc..
      generated_files: (list[File]) The output files generated by the proto compiler.
        Callee needs to declare files using `ctx.actions.declare_file`.
        See also: `proto_common.declare_generated_files`.
      plugin_output: (File|str) The file or directory passed to the plugin.
        Formatted with `proto_lang_toolchain.out_replacement_format_flag`
      additional_args: (Args) Additional arguments to add to the action.
        Accepts an ctx.actions.args() object that is added at the beginning
        of the command line.
      additional_tools: (list[File]) Additional tools to add to the action.
      additional_inputs: (Depset[File]) Additional input files to add to the action.
      resource_set:
        (func) A callback function that is passed to the created action.
        See `ctx.actions.run`, `resource_set` parameter for full definition of
        the callback.
    """
    proto_info = proto_library_target[_builtins.toplevel.ProtoInfo]

    args = actions.args()
    args.use_param_file(param_file_arg = "@%s")
    args.set_param_file_format("multiline")
    tools = list(additional_tools)

    if plugin_output:
        args.add(plugin_output, format = proto_lang_toolchain_info.out_replacement_format_flag)
    if proto_lang_toolchain_info.plugin:
        tools.append(proto_lang_toolchain_info.plugin)
        args.add(proto_lang_toolchain_info.plugin.executable, format = proto_lang_toolchain_info.plugin_format_flag)

    args.add_all(proto_info.transitive_proto_path, map_each = _proto_path_flag)
    # Example: `--proto_path=--proto_path=bazel-bin/target/third_party/pkg/_virtual_imports/subpkg`

    args.add_all(proto_lang_toolchain_info.protoc_opts)

    # Include maps
    # For each import, include both the import as well as the import relativized against its
    # protoSourceRoot. This ensures that protos can reference either the full path or the short
    # path when including other protos.
    args.add_all(proto_info.transitive_proto_sources(), map_each = _Iimport_path_equals_fullpath)
    # Example: `-Ia.proto=bazel-bin/target/third_party/pkg/_virtual_imports/subpkg/a.proto`

    args.add_all(proto_info.direct_sources)

    if additional_args:
        additional_args.use_param_file(param_file_arg = "@%s")
        additional_args.set_param_file_format("multiline")

    actions.run(
        mnemonic = proto_lang_toolchain_info.mnemonic,
        progress_message = proto_lang_toolchain_info.progress_message,
        executable = proto_lang_toolchain_info.proto_compiler,
        arguments = [additional_args, args] if additional_args else [args],
        inputs = depset(transitive = [proto_info.transitive_sources, additional_inputs]),
        outputs = generated_files,
        tools = tools,
        use_default_shell_env = True,
        resource_set = resource_set,
    )

proto_common_do_not_use = struct(
    compile = _compile,
    ProtoLangToolchainInfo = ProtoLangToolchainInfo,
)
