# Description:
#  Public targets available externally. Also see build_defs/rules.bzl for the provided rules.

load("@io_bazel_rules_closure//closure:defs.bzl", "closure_js_library")

package(default_visibility = ["//visibility:public"])

# Apache2
licenses(["notice"])

exports_files(["LICENSE"])

# JRE emulation
# This is not an alias since it is intended only for js targets not j2cl targets
closure_js_library(
    name = "jre",
    exports = ["//jre/java:jre"],
)

# JUnit library emulation
alias(
    name = "junit",
    actual = "//junit/emul/java:junit_emul",
)

# Optional minifier library for development servers
alias(
    name = "minifier",
    actual = "//tools/java/com/google/j2cl/tools/minifier",
)