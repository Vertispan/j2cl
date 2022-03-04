package(
    default_visibility = ["//visibility:public"], #TODO evaluate if still needed
    licenses = ["notice"]
)
load("//build_defs:rules.bzl", "j2cl_import")
load("@bazel_common//tools/javadoc:javadoc.bzl", "javadoc_library")

java_library(
    name = "visitor",
    exported_plugins = ["//transpiler/java/com/google/j2cl/common/visitor/generator:visitor-apt-processor"],
    visibility = [
        "@com_google_jsinterop_generator//:__subpackages__",
        "//transpiler:__subpackages__",
    ],
    exports = [":visitor-internal"],
)

java_library(
    name = "visitor-internal",
    srcs = glob(["*.java"]),
    visibility = ["//transpiler/java/com/google/j2cl/common/visitor/generator:__pkg__"],
)
javadoc_library(
    name = "visitor-internal-javadoc",
    srcs = glob(["*.java"]),
)

#j2cl_import(  # possibly no longer necessary
#    name = "visitor-j2cl",
#    jar = ":visitor",
#)
