#!/bin/bash
#
# Copyright 2017 The Bazel Authors. All rights reserved.
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
#
# Tests for Bazel's C++ rules on Darwin

# Load the test setup defined in the parent directory
CURRENT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${CURRENT_DIR}/../integration_test_setup.sh" \
  || { echo "integration_test_setup.sh not found!" >&2; exit 1; }

function test_osx_cc_wrapper_rpaths_handling() {
  mkdir -p cpp/rpaths
  cat > cpp/rpaths/BUILD <<EOF
cc_library(
  name = "foo",
  srcs = ["foo.cc"],
)
cc_binary(
  name = "libbar.so",
  srcs = [ "bar.cc" ],
  linkshared = 1,
)
cc_binary(
  name = "libbaz.dylib",
  srcs = [ "baz.cc" ],
  linkshared = 1,
)
cc_test(
  name = "test",
  srcs = [ "test.cc", ":libbar.so", ":libbaz.dylib"  ],
  deps = [":foo"],
)
EOF
  cat > cpp/rpaths/foo.cc <<EOF
  int foo() { return 2; }
EOF
  cat > cpp/rpaths/bar.cc <<EOF
  int bar() { return 12; }
EOF
  cat > cpp/rpaths/baz.cc <<EOF
  int baz() { return 42; }
EOF
  cat > cpp/rpaths/test.cc <<EOF
  int foo();
  int bar();
  int baz();
  int main() {
    int result = foo() + bar() + baz();
    if (result == 56) {
      return 0;
    } else {
      return result;
    }
  }
EOF
  assert_build //cpp/rpaths:test >& $TEST_log || fail "//cpp/rpaths:test didn't build"
  # Paths originally hardcoded in the binary assume workspace directory. Let's change the
  # directory and execute the binary to test whether the paths in the binary have been
  # updated to use @loader_path.
  cd bazel-bin
  ./cpp/rpaths/test >& $TEST_log || \
      fail "//cpp/rpaths:test execution failed, expected to return 0, but got $?"
}

run_suite "Tests for Bazel's C++ rules on Darwin"

