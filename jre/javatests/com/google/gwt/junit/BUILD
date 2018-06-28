licenses([
    "notice",  # Apache
])

load("//build_defs:rules.bzl", "j2cl_library")

j2cl_library(
    name = "gwttestcase_emul",
    srcs = glob(["**/*.java"]),
    deps = ["//junit/emul/java:junit_emul"]
)