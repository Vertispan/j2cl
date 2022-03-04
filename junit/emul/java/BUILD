load("//build_defs:rules.bzl", "j2cl_library")
load("@bazel_common//tools/javadoc:javadoc.bzl", "javadoc_library")

package(
    licenses = ["reciprocal"],  # Eclipse Public License - v 1.0
)

java_library(
    name = "junit_emul-java",
    srcs = glob(["**/*.java"]),
    javacopts = [
        "-Xep:DoNotCall:OFF",
    ],
    visibility = [
        "//:__pkg__",
        "//jre:__subpackages__",
    ],
)

j2cl_library(
    name = "junit_emul",
    srcs = glob(["**/*.java"]),
    javacopts = [
        "-Xep:DoNotCall:OFF",
    ],
    visibility = [
        "//:__pkg__",
        "//jre:__subpackages__",
    ],
)
javadoc_library(
    name = "junit_emul-javadoc",
    srcs = glob(["**/*.java"]),
    deps = [
        ":junit_emul",
    ],
)
