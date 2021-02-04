# Description:
#   Integration tests.
#
# The IntegrationTestCase base class makes it extremely easy to define and
# verify integration tests that need to assert about output std or err logs.
# Other integration tests will want to run transpiled output as JS and
# should make use of j2cl_library macro.

package(
    licenses = ["notice"],  # Apache 2.0
)

java_import(
    name = "jre",
    jars = ["//jre/java:libjre.jar"],
    srcjar = "//jre/java:libjre-src.jar",
    deps = ["//jre/java:jre_bootclasspath"],
)

# A java_binary to create a merged jar of all deps of the standard runtime.
java_binary(
    name = "jre_bundle",
    create_executable = 0,
    runtime_deps = [":jre"],
)

java_test(
    name = "IncrementalTest",
    srcs = ["IncrementalTest.java"],
    data = [":jre_bundle_deploy.jar"],
    deps = [
        ":TranspilerTester",
        "//transpiler/java/com/google/j2cl/transpiler/incremental",
        "//third_party:junit",
    ],
)

java_library(
    name = "jre_native",
    resources = ["//jre/java:jre_native_files"],
)

java_library(
    name = "TranspilerTester",
    testonly = 1,
    srcs = [
        "TranspilerTester.java", "JavacHelper.java", "AssertEquality.java"
    ],
    deps = [
        "//third_party:auto_value",
        "//third_party:guava",
        "//third_party:gwt-jsinterop-annotations",
        "//third_party:junit",
        "//third_party:truth",
        "//third_party:javac",
        "//transpiler/java/com/google/j2cl/common",
        "//transpiler/java/com/google/j2cl/transpiler/ast",
        "//transpiler/java/com/google/j2cl/transpiler/frontend",
        "//transpiler/java/com/google/j2cl/transpiler/backend",
        "//transpiler/java/com/google/j2cl/transpiler/incremental",
        "//transpiler/java/com/google/j2cl/transpiler:commandlinerunner_lib",
        "//transpiler/java/com/google/j2cl/transpiler:transpiler_options_lib",
    ],
)

