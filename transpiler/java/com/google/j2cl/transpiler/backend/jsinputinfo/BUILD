package(
    default_visibility = [
        "//transpiler:__subpackages__",
    ],
    licenses = ["notice"],
)

proto_library(
    name = "jsinput_info_proto",
    srcs = ["jsinput_info.proto"],
)

java_proto_library(
    name = "jsinput_info_java_proto",
    deps = [":jsinput_info_proto"],
)

java_library(
    name = "jsinputinfo",
    srcs = glob(["*.java"]),
    exports = [":jsinput_info_java_proto"],
    deps = [
        ":jsinput_info_java_proto",
        "//third_party:guava",
        "//third_party:jsr305_annotations",
        "//third_party:protobuf_util_json",
        "//transpiler/java/com/google/j2cl/common",
    ],
)
