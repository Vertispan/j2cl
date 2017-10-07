#!/usr/bin/env bash

set -e

bazel build //transpiler/java/com/google/j2cl/transpiler:*

bazel build //tools/java/com/google/j2cl/tools/gwtincompatible:*
bazel build //tools/java/com/google/j2cl/tools/minifier:*

bazel build //jre/java/javaemul/internal/vmbootstrap/primitives:primitives

bazel build //jre/java:*

bazel build @org_gwtproject_gwt//user:libgwt-javaemul-internal-annotations.jar
bazel build @org_gwtproject_gwt//user:libgwt-javaemul-internal-annotations-src.jar
