#!/usr/bin/env bash

set -e
BAZEL=${BAZEL:-bazel}

${BAZEL} build //transpiler/java/com/google/j2cl/transpiler:*

${BAZEL} build //tools/java/com/google/j2cl/tools/gwtincompatible:*
${BAZEL} build //tools/java/com/google/j2cl/tools/minifier:*

${BAZEL} build //jre/java/javaemul/internal/vmbootstrap/primitives:primitives

${BAZEL} build //jre/java:*

${BAZEL} build //junit/emul/java:*
${BAZEL} build //jre/javatests/com/google/gwt/junit:*
${BAZEL} build //junit/generator/java/com/google/j2cl/junit/apt:*

${BAZEL} build @org_gwtproject_gwt//user:libgwt-javaemul-internal-annotations.jar
${BAZEL} build @org_gwtproject_gwt//user:libgwt-javaemul-internal-annotations-src.jar


# This must be the last line, or else some other operation will apparently remove these
# soft links that we rely on in the maven build
${BAZEL} build //third_party:jdt-core