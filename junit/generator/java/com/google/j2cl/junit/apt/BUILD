load("//build_defs:rules.bzl", "j2cl_library")
load("@bazel_common//tools/javadoc:javadoc.bzl", "javadoc_library")

package(
    licenses = ["notice"],  # Apache 2.0
)

j2cl_library(
    name = "internal_junit_annotations",
    testonly = 1,
    srcs = ["J2clTestInput.java"],
    exported_plugins = [":junit_processor_plugin"],
    visibility = ["//build_defs/internal_do_not_use:__pkg__"],
)
javadoc_library(
    name = "internal_junit_annotations-javadoc",
    srcs = ["J2clTestInput.java"],
)

java_plugin(
    name = "junit_processor_plugin",
    testonly = 1,
    processor_class = "com.google.j2cl.junit.apt.J2clTestingProcessor",
    deps = [":junit_processor"],
)

java_library(
    name = "junit_processor",
    testonly = 1,
    srcs = glob(["*.java"]),
    resources = glob(["*.vm"]),
    visibility = ["//junit/generator/javatests/com/google/j2cl/junit/apt:__pkg__"],
    deps = [
        "//junit/generator/java/com/google/j2cl/junit/async",
        "//third_party:auto_common",
        "//third_party:auto_service",
        "//third_party:auto_value",
        "//third_party:guava",
        "//third_party:gwt-jsinterop-annotations",
        "//third_party:jakarta_velocity",
        "//third_party:jsr305_annotations",
        "//third_party:junit",
    ],
    plugins = [":autoservice_plugin"],
)
javadoc_library(
    name = "junit_processor-javadoc",
    testonly = 1,
    srcs = glob(["*.java"]),
    deps = [
        ":junit_processor",
    ],
)

java_plugin(
    name = "autoservice_plugin",
    processor_class = "com.google.auto.service.processor.AutoServiceProcessor",
    deps = [
        "//third_party:auto_service",
        "//third_party:guava",
        "//third_party:auto_common",
    ],
)