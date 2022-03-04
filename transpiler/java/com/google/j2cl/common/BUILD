load("@bazel_common//tools/javadoc:javadoc.bzl", "javadoc_library")

package(
    default_visibility = [
        "//tools:__subpackages__",
        "//transpiler:__subpackages__",
    ],
    licenses = ["notice"],
)

java_library(
    name = "common",
    srcs = glob(["*.java"]),
    deps = [
        "//third_party:args4j",
        "//third_party:auto_value",
        "//third_party:guava",
        "//third_party:jsr305_annotations",
    ],
)

javadoc_library(
    name = "common-javadoc",
    srcs = glob(["*.java"]),
    deps = [
        ":common"
    ],
)
