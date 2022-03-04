licenses([
    "notice",  # Apache
])

load("//build_defs:rules.bzl", "j2cl_library")
load("@bazel_common//tools/javadoc:javadoc.bzl", "javadoc_library")

j2cl_library(
    name = "gwttestcase_emul",
    srcs = glob(["**/*.java"]),
    deps = ["//junit/emul/java:junit_emul"]
)

javadoc_library(
    name = "gwttestcase_emul-javadoc",
    srcs = glob(["**/*.java"]),
    deps = [
        "//junit/emul/java:junit_emul-java",
    ],
)
