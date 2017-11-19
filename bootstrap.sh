#!/usr/bin/env bash

# Creates a bootstrap.js.zip file in the current working directory, containing the various
# JS dependencies required in a jscomp build to produce functional JS.

# To use this, create primitives (requires patch to correctly emit java primtives!):
#   $ bazel build //jre/java/javaemul/internal/vmbootstrap/primitives:primitives

source config.sh

# Remove the old if it exists, track where we are creating it so we get nice paths inside and can cd around
rm bootstrap.js.zip
OUT_DIR=`pwd`

# Closure-library dependencies
pushd $CLOSURE_LIBRARY_REPO
zip $OUT_DIR/bootstrap.js.zip \
closure/goog/reflect/reflect.js \
closure/goog/math/long.js \
closure/goog/asserts/asserts.js \
closure/goog/debug/error.js \
closure/goog/dom/nodetype.js \
closure/goog/string/string.js \
closure/goog/base.js

popd

# vmbootstrap - ("not part of the jre", "references the jre")
pushd $J2CL_REPO/jre/java
zip $OUT_DIR/bootstrap.js.zip \
javaemul/internal/vmbootstrap/*.js \
javaemul/internal/vmbootstrap/primitives/*.js
popd

pushd $J2CL_REPO/bazel-genfiles/jre/java
zip $OUT_DIR/bootstrap.js.zip \
javaemul/internal/vmbootstrap/primitives/*.js
popd

# nativebootstrap - ("not part of the jre", "does not reference the jre")
pushd $J2CL_REPO/jre/java
zip $OUT_DIR/bootstrap.js.zip \
javaemul/internal/nativebootstrap/*.js
popd

# JRE config details, apparently not present in jre.js.zip
pushd $J2CL_REPO/jre/java
zip $OUT_DIR/bootstrap.js.zip \
java/lang/jre.js
popd
