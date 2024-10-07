# Copyright 2024 The Bazel Authors. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

load("@rules_cc//cc/toolchains:toolchain_config_utils.bzl", _MSVC_ENVVARS="MSVC_ENVVARS")
load("@rules_cc//cc/private/toolchain:cc_configure.bzl", _cc_configure="cc_configure")

MSVC_ENVVARS = _MSVC_ENVVARS
cc_configure = _cc_configure
