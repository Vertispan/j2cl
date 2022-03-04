# Description:
#   This package contains frontend that uses Eclispe/JDT to parse, typecheck and resolve Java code.

load("@bazel_common//tools/javadoc:javadoc.bzl", "javadoc_library")

package(
    default_visibility = [
        "//tools/java/com/google/j2cl/tools/gwtincompatible:__pkg__",
        "//transpiler/java/com/google/j2cl/transpiler/frontend:__subpackages__",
    ],
    licenses = ["notice"],
)

java_library(
    name = "jdt",
    srcs = glob(["*.java"]),
    deps = [
        "//third_party:auto_value",
        "//third_party:guava",
        "//third_party:gwt-jsinterop-annotations",
        "//third_party:jdt-core",
        "//transpiler/java/com/google/j2cl/common",
        "//transpiler/java/com/google/j2cl/transpiler/ast",
        "//transpiler/java/com/google/j2cl/transpiler/frontend/common",
    ],
)

javadoc_library(
    name = "jdt-javadoc",
    srcs = glob(["*.java"]),
    deps = [
        ":jdt",
    ],
)
