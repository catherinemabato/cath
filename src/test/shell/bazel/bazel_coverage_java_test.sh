#!/bin/bash
#
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

set -eu

# Load the test setup defined in the parent directory
CURRENT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${CURRENT_DIR}/../integration_test_setup.sh" \
  || { echo "integration_test_setup.sh not found!" >&2; exit 1; }

JAVA_TOOLCHAIN="$1"; shift
add_to_bazelrc "build --java_toolchain=${JAVA_TOOLCHAIN}"
add_to_bazelrc "build --host_java_toolchain=${JAVA_TOOLCHAIN}"

JAVA_TOOLS_ZIP="$1"; shift
if [[ "${JAVA_TOOLS_ZIP}" != "released" ]]; then
    if [[ "${JAVA_TOOLS_ZIP}" == file* ]]; then
        JAVA_TOOLS_ZIP_FILE_URL="${JAVA_TOOLS_ZIP}"
    else
        JAVA_TOOLS_ZIP_FILE_URL="file://$(rlocation io_bazel/$JAVA_TOOLS_ZIP)"
    fi
fi
JAVA_TOOLS_ZIP_FILE_URL=${JAVA_TOOLS_ZIP_FILE_URL:-}

if [[ $# -gt 0 ]]; then
    JAVABASE_VALUE="$1"; shift
    add_to_bazelrc "build --javabase=${JAVABASE_VALUE}"
    add_to_bazelrc "build --host_javabase=${JAVABASE_VALUE}"
fi

function set_up() {
    cat >>WORKSPACE <<EOF
load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
# java_tools versions only used to test Bazel with various JDK toolchains.
EOF

    if [[ ! -z "${JAVA_TOOLS_ZIP_FILE_URL}" ]]; then
    cat >>WORKSPACE <<EOF
http_archive(
    name = "local_java_tools",
    urls = ["${JAVA_TOOLS_ZIP_FILE_URL}"]
)
EOF
    fi

    cat $(rlocation io_bazel/src/test/shell/bazel/testdata/jdk_http_archives) >> WORKSPACE
}

# Asserts if the given expected coverage result is included in the given output
# file.
#
# - expected_coverage The expected result that must be included in the output.
# - output_file       The location of the coverage output file.
function assert_coverage_result() {
    local expected_coverage="${1}"; shift
    local output_file="${1}"; shift

    # Replace newlines with commas to facilitate the assertion.
    local expected_coverage_no_newlines="$( echo "$expected_coverage" | tr '\n' ',' )"
    local output_file_no_newlines="$( cat "$output_file" | tr '\n' ',' )"

    (echo "$output_file_no_newlines" \
        | grep -F "$expected_coverage_no_newlines") \
        || fail "Expected coverage result
<$expected_coverage>
was not found in actual coverage report:
<$( cat "$output_file" )>"
}

# Returns the path of the code coverage report that was generated by Bazel by
# looking at the current $TEST_log. The method fails if TEST_log does not
# contain any coverage report for a passed test.
function get_coverage_file_path_from_test_log() {
  local ending_part="$(sed -n -e '/PASSED/,$p' "$TEST_log")"

  local coverage_file_path=$(grep -Eo "/[/a-zA-Z0-9\.\_\-]+\.dat$" <<< "$ending_part")
  [[ -e "$coverage_file_path" ]] || fail "Coverage output file does not exist!"
  echo "$coverage_file_path"
}

function test_java_test_coverage() {
  cat <<EOF > BUILD
java_test(
    name = "test",
    srcs = glob(["src/test/**/*.java"]),
    test_class = "com.example.TestCollatz",
    deps = [":collatz-lib"],
)

java_library(
    name = "collatz-lib",
    srcs = glob(["src/main/**/*.java"]),
)
EOF

  mkdir -p src/main/com/example
  cat <<EOF > src/main/com/example/Collatz.java
package com.example;

public class Collatz {

  public static int getCollatzFinal(int n) {
    if (n == 1) {
      return 1;
    }
    if (n % 2 == 0) {
      return getCollatzFinal(n / 2);
    } else {
      return getCollatzFinal(n * 3 + 1);
    }
  }

}
EOF

  mkdir -p src/test/com/example
  cat <<EOF > src/test/com/example/TestCollatz.java
package com.example;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class TestCollatz {

  @Test
  public void testGetCollatzFinal() {
    assertEquals(Collatz.getCollatzFinal(1), 1);
    assertEquals(Collatz.getCollatzFinal(5), 1);
    assertEquals(Collatz.getCollatzFinal(10), 1);
    assertEquals(Collatz.getCollatzFinal(21), 1);
  }

}
EOF

  bazel coverage --test_output=all //:test &>$TEST_log || fail "Coverage for //:test failed"
  cat $TEST_log
  local coverage_file_path="$( get_coverage_file_path_from_test_log )"

  local expected_result="SF:src/main/com/example/Collatz.java
FN:3,com/example/Collatz::<init> ()V
FN:6,com/example/Collatz::getCollatzFinal (I)I
FNDA:0,com/example/Collatz::<init> ()V
FNDA:1,com/example/Collatz::getCollatzFinal (I)I
FNF:2
FNH:1
BA:6,2
BA:9,2
BRF:2
BRH:2
DA:3,0
DA:6,3
DA:7,2
DA:9,4
DA:10,5
DA:12,7
LH:5
LF:6
end_of_record"

assert_coverage_result "$expected_result" "$coverage_file_path"
}

function test_java_test_coverage_combined_report() {

  cat <<EOF > BUILD
java_test(
    name = "test",
    srcs = glob(["src/test/**/*.java"]),
    test_class = "com.example.TestCollatz",
    deps = [":collatz-lib"],
)

java_library(
    name = "collatz-lib",
    srcs = glob(["src/main/**/*.java"]),
)
EOF

  mkdir -p src/main/com/example
  cat <<EOF > src/main/com/example/Collatz.java
package com.example;

public class Collatz {

  public static int getCollatzFinal(int n) {
    if (n == 1) {
      return 1;
    }
    if (n % 2 == 0) {
      return getCollatzFinal(n / 2);
    } else {
      return getCollatzFinal(n * 3 + 1);
    }
  }

}
EOF

  mkdir -p src/test/com/example
  cat <<EOF > src/test/com/example/TestCollatz.java
package com.example;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class TestCollatz {

  @Test
  public void testGetCollatzFinal() {
    assertEquals(Collatz.getCollatzFinal(1), 1);
    assertEquals(Collatz.getCollatzFinal(5), 1);
    assertEquals(Collatz.getCollatzFinal(10), 1);
    assertEquals(Collatz.getCollatzFinal(21), 1);
  }

}
EOF

  bazel coverage --test_output=all //:test --coverage_report_generator=@bazel_tools//tools/test:coverage_report_generator --combined_report=lcov &>$TEST_log \
   || echo "Coverage for //:test failed"

  local expected_result="SF:src/main/com/example/Collatz.java
FN:3,com/example/Collatz::<init> ()V
FN:6,com/example/Collatz::getCollatzFinal (I)I
FNDA:0,com/example/Collatz::<init> ()V
FNDA:1,com/example/Collatz::getCollatzFinal (I)I
FNF:2
FNH:1
BA:6,2
BA:9,2
BRF:2
BRH:2
DA:3,0
DA:6,3
DA:7,2
DA:9,4
DA:10,5
DA:12,7
LH:5
LF:6
end_of_record"

  assert_coverage_result "$expected_result" "./bazel-out/_coverage/_coverage_report.dat"
}

function test_java_test_java_import_coverage() {

  cat <<EOF > BUILD
java_test(
    name = "test",
    srcs = glob(["src/test/**/*.java"]),
    test_class = "com.example.TestCollatz",
    deps = [":collatz-import"],
)

java_import(
    name = "collatz-import",
    jars = [":libcollatz-lib.jar"],
)

java_library(
    name = "collatz-lib",
    srcs = glob(["src/main/**/*.java"]),
)
EOF

  mkdir -p src/main/com/example
  cat <<EOF > src/main/com/example/Collatz.java
package com.example;

public class Collatz {

  public static int getCollatzFinal(int n) {
    if (n == 1) {
      return 1;
    }
    if (n % 2 == 0) {
      return getCollatzFinal(n / 2);
    } else {
      return getCollatzFinal(n * 3 + 1);
    }
  }

}
EOF

  mkdir -p src/test/com/example
  cat <<EOF > src/test/com/example/TestCollatz.java
package com.example;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class TestCollatz {

  @Test
  public void testGetCollatzFinal() {
    assertEquals(Collatz.getCollatzFinal(1), 1);
    assertEquals(Collatz.getCollatzFinal(5), 1);
    assertEquals(Collatz.getCollatzFinal(10), 1);
    assertEquals(Collatz.getCollatzFinal(21), 1);
  }

}
EOF

  bazel coverage --test_output=all //:test &>$TEST_log || fail "Coverage for //:test failed"
  local coverage_file_path="$( get_coverage_file_path_from_test_log )"

  local expected_result="SF:src/main/com/example/Collatz.java
FN:3,com/example/Collatz::<init> ()V
FN:6,com/example/Collatz::getCollatzFinal (I)I
FNDA:0,com/example/Collatz::<init> ()V
FNDA:1,com/example/Collatz::getCollatzFinal (I)I
FNF:2
FNH:1
BA:6,2
BA:9,2
BRF:2
BRH:2
DA:3,0
DA:6,3
DA:7,2
DA:9,4
DA:10,5
DA:12,7
LH:5
LF:6
end_of_record"

  assert_coverage_result "$expected_result" "$coverage_file_path"
}

function test_run_jar_in_subprocess_empty_env() {
  mkdir -p java/cov
  mkdir -p javatests/cov
  cat >java/cov/BUILD <<EOF
package(default_visibility=['//visibility:public'])
java_binary(name = 'Cov',
            main_class = 'cov.Cov',
            srcs = ['Cov.java'])
EOF

  cat >java/cov/Cov.java <<EOF
package cov;
public class Cov {
  public static void main(String[] args) {
    if (args.length == 1) {
      if (Boolean.parseBoolean(args[0])) {
        System.out.println("Boolean.parseBoolean returned true");  // line 6
      } else {
        System.out.println("Boolean.parseBoolean returned false"); // line 8
      }
    }
  }
}
EOF

  cat >javatests/cov/BUILD <<EOF
java_test(name = 'CovTest',
          srcs = ['CovTest.java'],
          data = ['//java/cov:Cov_deploy.jar'],
          test_class = 'cov.CovTest')
EOF

  cat >javatests/cov/CovTest.java <<EOF
package cov;
import junit.framework.TestCase;
import java.io.*;
import java.nio.channels.*;
import java.net.InetAddress;
public class CovTest extends TestCase {
  private static Process startSubprocess(String arg) throws Exception {
   String path = System.getenv("TEST_SRCDIR") + "/main/java/cov/Cov_deploy.jar";
    String[] command = {
      // Run the deploy jar by invoking JVM because the integration tests
      // cannot use the java launcher (b/29388516).
      System.getProperty("java.home") + "/bin/java", "-jar", path, arg
    };
    ProcessBuilder pb = new ProcessBuilder(command);
    pb.environment().clear();
    return pb.start();
  }
  public void testTrivial() throws Exception {
    Process subprocessTrue = startSubprocess("true");
    Process subprocessFalse = startSubprocess("false");
    subprocessTrue.waitFor();
    subprocessFalse.waitFor();
    String line;
    BufferedReader input = new BufferedReader(new InputStreamReader(subprocessTrue.getInputStream()));
    while ((line = input.readLine()) != null) {
      System.out.println(line);
     }
    input.close();
    BufferedReader err = new BufferedReader(new InputStreamReader(subprocessTrue.getErrorStream()));
    while ((line = err.readLine()) != null) {
      System.out.println(line);
     }
    err.close();

    input = new BufferedReader(new InputStreamReader(subprocessFalse.getInputStream()));
    while ((line = input.readLine()) != null) {
      System.out.println(line);
     }
    input.close();
    err = new BufferedReader(new InputStreamReader(subprocessFalse.getErrorStream()));
    while ((line = err.readLine()) != null) {
      System.out.println(line);
     }
    err.close();
  }
}
EOF

  # Only assess that the coverage run was successful.
  # --nooutputredirect is needed for blaze to print the output of the jar
  bazel coverage --test_output=all --test_arg=--nooutputredirect \
    javatests/cov:CovTest >"${TEST_log}" || fail "Expected success"
  expect_not_log "JACOCO_METADATA_JAR/JACOCO_MAIN_CLASS environment variables not set"
  expect_log "Boolean.parseBoolean returned true"
  expect_log "Boolean.parseBoolean returned false"
}

function test_runtime_deploy_jar() {
  mkdir -p java/cov
  mkdir -p javatests/cov
  cat >java/cov/BUILD <<EOF
package(default_visibility=['//visibility:public'])
java_binary(
    name = 'RandomBinary',
    main_class = 'cov.RandomBinary',
    srcs = ['RandomBinary.java'],
)

java_library(
    name = 'Cov',
    srcs = ['Cov.java']
)
EOF

  cat >java/cov/RandomBinary.java <<EOF
package cov;
public class RandomBinary {
  public static void main(String[] args) throws Exception {
    throw new Exception("RandomBinary should not be run!");
  }
}
EOF

  cat >java/cov/Cov.java <<EOF
package cov;
public class Cov {
  public static void main(String[] args) {
    if (args.length == 1) {
      if (Boolean.parseBoolean(args[0])) {
        System.out.println("Boolean.parseBoolean returned true");  // line 6
      } else {
        System.out.println("Boolean.parseBoolean returned false"); // line 8
      }
    }
  }
}
EOF

  cat >javatests/cov/BUILD <<EOF
java_test(name = 'CovTest',
          srcs = ['CovTest.java'],
          deps = ['//java/cov:Cov'],
          runtime_deps = ['//java/cov:RandomBinary_deploy.jar'],
          test_class = 'cov.CovTest')
EOF

  cat >javatests/cov/CovTest.java <<EOF
package cov;
import junit.framework.TestCase;
import java.io.*;
import java.nio.channels.*;
import java.net.InetAddress;
public class CovTest extends TestCase {
  public void testTrivial() throws Exception {
    Cov.main(new String[] {"true"});
    Cov.main(new String[] {"false"});
  }
}
EOF

  bazel coverage --test_output=all --instrumentation_filter=//java/cov \
      javatests/cov:CovTest >"${TEST_log}"
  local coverage_file_path="$( get_coverage_file_path_from_test_log )"
  assert_coverage_result "java/cov/Cov.java" ${coverage_file_path}
}

function test_runtime_and_data_deploy_jars() {
  mkdir -p java/cov
  mkdir -p javatests/cov
  cat >java/cov/BUILD <<EOF
package(default_visibility=['//visibility:public'])
java_binary(
    name = 'RandomBinary',
    main_class = 'cov.RandomBinary',
    srcs = ['RandomBinary.java'],
)

java_binary(
    name = 'Cov',
    srcs = ['Cov.java'],
    main_class = 'cov.Cov'
)
EOF

  cat >java/cov/RandomBinary.java <<EOF
package cov;
public class RandomBinary {
  public static void main(String[] args) throws Exception {
    throw new Exception("RandomBinary should not be run!");
  }
}
EOF

  cat >java/cov/Cov.java <<EOF
package cov;
public class Cov {
  public static void main(String[] args) {
    if (args.length == 1) {
      if (Boolean.parseBoolean(args[0])) {
        System.out.println("Boolean.parseBoolean returned true");  // line 6
      } else {
        System.out.println("Boolean.parseBoolean returned false"); // line 8
      }
    }
  }
}
EOF

  cat >javatests/cov/BUILD <<EOF
java_test(name = 'CovTest',
          srcs = ['CovTest.java'],
          data = ['//java/cov:Cov_deploy.jar'],
          runtime_deps = ['//java/cov:RandomBinary_deploy.jar'],
          test_class = 'cov.CovTest')
EOF

  cat >javatests/cov/CovTest.java <<EOF
package cov;
import junit.framework.TestCase;
import java.io.*;
import java.nio.channels.*;
import java.net.InetAddress;
public class CovTest extends TestCase {
  private static Process startSubprocess(String arg) throws Exception {
   String path = System.getenv("TEST_SRCDIR") + "/main/java/cov/Cov_deploy.jar";
    String[] command = {
      // Run the deploy jar by invoking JVM because the integration tests
      // cannot use the java launcher (b/29388516).
      System.getProperty("java.home") + "/bin/java", "-jar", path, arg
    };
    return new ProcessBuilder(command).start();
  }
  public void testTrivial() throws Exception {
    Process subprocessTrue = startSubprocess("true");
    Process subprocessFalse = startSubprocess("false");
    subprocessTrue.waitFor();
    subprocessFalse.waitFor();
    String line;
    BufferedReader input = new BufferedReader(new InputStreamReader(subprocessTrue.getInputStream()));
    while ((line = input.readLine()) != null) {
      System.out.println(line);
     }
    input.close();
    BufferedReader err = new BufferedReader(new InputStreamReader(subprocessTrue.getErrorStream()));
    while ((line = err.readLine()) != null) {
      System.out.println(line);
     }
    err.close();

    input = new BufferedReader(new InputStreamReader(subprocessFalse.getInputStream()));
    while ((line = input.readLine()) != null) {
      System.out.println(line);
     }
    input.close();
    err = new BufferedReader(new InputStreamReader(subprocessFalse.getErrorStream()));
    while ((line = err.readLine()) != null) {
      System.out.println(line);
     }
    err.close();
  }
}
EOF

  # --nooutputredirect is needed for blaze to print the output of the deploy jar
  bazel coverage --test_output=all --test_arg=--nooutputredirect \
      --instrumentation_filter=//java/cov javatests/cov:CovTest >"${TEST_log}"
  local coverage_file_path="$( get_coverage_file_path_from_test_log )"
  assert_coverage_result "java/cov/Cov.java" ${coverage_file_path}
}

run_suite "test tests"
