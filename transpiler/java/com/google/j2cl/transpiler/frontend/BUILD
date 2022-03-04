# Description:
#   This package contains frontend driver code.

load("@bazel_common//tools/javadoc:javadoc.bzl", "javadoc_library")

package(
    default_visibility = [
        "//transpiler:__subpackages__",
    ],
    licenses = ["notice"],
)

java_library(
    name = "frontend",
    srcs = glob(["*.java"]),
    deps = [
        "//third_party:guava",
        "//transpiler/java/com/google/j2cl/common",
        "//transpiler/java/com/google/j2cl/transpiler/ast",
        "//transpiler/java/com/google/j2cl/transpiler/frontend/common",
        "//transpiler/java/com/google/j2cl/transpiler/frontend/javac",
        "//transpiler/java/com/google/j2cl/transpiler/frontend/jdt",
    ],
)

javadoc_library(
    name = "frontend-javadoc",
    srcs = glob(["*.java"]),
    deps = [
        ":frontend",
    ],
)
