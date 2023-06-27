# Copyright 2023 The Bazel Authors. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""Smoke test for packages_used."""

import json
import os
import unittest


def read_data_file(basename):
  path = os.path.join(
      os.getenv("TEST_SRCDIR"),
      "io_bazel/tools/compliance",
      basename)
  with open(path, "rt", encoding="utf-8") as f:
    return f.read()


class PackagesUsedTest(unittest.TestCase):

  def test_found_key_licenses(self):
    raw_json = read_data_file("bazel_packages.json")
    content = json.loads(raw_json)
    found_top_level_license = False
    found_zlib = False
    for l in content["licenses"]:
      if l["label"] == "//:license":
        found_top_level_license = True
      if l["label"] == "//third_party/zlib:license":
        found_zlib = True
    self.assertTrue(found_top_level_license)
    self.assertTrue(found_zlib)

  def test_found_remote_packages(self):
    raw_json = read_data_file("bazel_packages.json")
    content = json.loads(raw_json)
    self.assertIn(
        "@remoteapis//:build_bazel_remote_execution_v2_remote_execution_proto",
        content["packages"])

  def test_package_info(self):
    # Placeholder: There are no package_info declarations yet.
    pass

if __name__ == "__main__":
  unittest.main()
