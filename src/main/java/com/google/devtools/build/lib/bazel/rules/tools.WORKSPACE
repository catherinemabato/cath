local_repository(name = "bazel_tools", path = __embedded_dir__ + "/embedded_tools")
bind(name = "cc_toolchain", actual = "@bazel_tools//tools/cpp:default-toolchain")
bind(name = "java_toolchain", actual = "%java_toolchain%")

load("@bazel_tools//tools/bash:bash_def.bzl", "bash_repositories")
bash_repositories()
