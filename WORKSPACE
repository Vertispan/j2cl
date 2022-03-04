workspace(name = "com_google_j2cl")

load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
load("@bazel_tools//tools/build_defs/repo:git.bzl", "git_repository")

# Use the head version of JRE from GWT for development.
# For releases, update following instead:
# build_defs/internal_do_not_use/j2cl_workspace.bzl
http_archive(
    name = "org_gwtproject_gwt",
    url = "https://gwt.googlesource.com/gwt/+archive/master.tar.gz",
)

load("//build_defs:repository.bzl", "load_j2cl_repo_deps")
load_j2cl_repo_deps()

load("//build_defs:rules.bzl", "setup_j2cl_workspace")
setup_j2cl_workspace()


git_repository(
    name = "bazel_common",
    remote = "https://github.com/google/bazel-common.git",
    commit = "bf8e5ef",
)
