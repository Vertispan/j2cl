#!/usr/bin/env bash
set -e

# First, build the transpiler from the bazel-build branch:
#   $ bazel build //transpiler/java/com/google/j2cl/transpiler:J2clTranspiler
# Then primitives (requires patch to correctly emit java primtives!):
#   $ bazel build //jre/java/javaemul/internal/vmbootstrap/primitives:primitives
# Then the full jre:
#   $ bazel build //jre/java:jre

source config.sh

# location of jre emulation
base_cp="$J2CL_REPO/bazel-bin/jre/java/jre.jar"
base_zips="$J2CL_REPO/bazel-bin/jre/java/jre.js.zip"

# params from command line:
# $ devmode.sh [<source-dir> [<pom.xml> [<web-dir>]]]
sources=${1-.}
pom=${2-pom.xml}
serve_from=${3-out}
j2cl_cp=$base_cp
timestamp=$sources/.timestamp

# Call j2cl directly
J2clTranspiler() {
  $J2CL_REPO/bazel-bin/transpiler/java/com/google/j2cl/transpiler/J2clTranspiler "$@"
}

# An apparently deprecated tool, first cut toward a "dev mode" sort of env. Currently this output is unused,
# but it is important to note that building and including it into the jszip doesn't negatively impact jscomp.
depswriter() {
 $CLOSURE_LIBRARY_REPO/closure/bin/build/depswriter.py "$@"
}

echo_and_run() { echo "$@" ; "$@" ; }

compile_changed() {
    # if marker file doesn't exist, die
    if [[ ! -e "$timestamp" ]]
    then
        echo "$timestamp doesn't exist, please relaunch"
        exit -1;
    fi

    modified_list=$(find $sources -type f -newer $timestamp -name '*.java' | tr '\n' ' ')
#    echo $modified_list


    # if empty, sleep a second and return
    if [[ -z $modified_list ]]
    then
        sleep 1
    else
        touch $timestamp

        # else recompile all files
        # TODO on error, nuke the output dir, so we can start over from scratch
        javac -cp $j2cl_cp:target/classes -sourcepath '' -d target/classes $modified_list && echo "javac done"
        echo_and_run J2clTranspiler -cp $j2cl_cp:target/classes -d $serve_from/project $modified_list && echo "j2cl done"

        echo_and_run time java -server -XX:+TieredCompilation -Xmx1g \
            -jar $CLOSURE_COMPILER_JAR \
            --entry_point 'app' \
            --js "$serve_from/**/*.js" --jszip $jszip \
            --js "$J2CL_REPO/jre/java/javaemul/internal/nativebootstrap/*.js" \
            --js "$J2CL_REPO/jre/java/java/lang/jre.js" \
            --js "$CLOSURE_LIBRARY_REPO/closure/goog/reflect/reflect.js" \
            --jszip "$J2CL_REPO/bazel-bin/jre/java/jre.js.zip" \
            --js "$J2CL_REPO/jre/java/javaemul/internal/vmbootstrap/*.js" \
            --js "$J2CL_REPO/bazel-genfiles/jre/java/javaemul/internal/vmbootstrap/primitives/*.js" \
            --js "$CLOSURE_LIBRARY_REPO/closure/goog/math/long.js" \
            --js "$J2CL_REPO/jre/java/javaemul/internal/vmbootstrap/**/*.js" \
            --js "$CLOSURE_LIBRARY_REPO/closure/goog/asserts/asserts.js" \
            --js "$CLOSURE_LIBRARY_REPO/closure/goog/debug/error.js" \
            --js "$CLOSURE_LIBRARY_REPO/closure/goog/dom/nodetype.js" \
            --js "$CLOSURE_LIBRARY_REPO/closure/goog/string/string.js" \
            --js "$CLOSURE_LIBRARY_REPO/closure/goog/base.js" \
            --compilation_level BUNDLE \
            --define ENABLE_DEBUG_LOADER=false \
            --js_output_file "$serve_from/out.js" \
        && sed -e '1i\
this["CLOSURE_UNCOMPILED_DEFINES"] = {"goog.ENABLE_DEBUG_LOADER": false};\' -i '' "$serve_from/out.js" \
        && echo "jscomp done, ready to refresh"

    fi
}

# setup: verify pid file doesn't exist, or someone else is doing this
if [ -e "$timestamp" ]
then
  echo "$timestamp exists, delete it or use the existing dev mode"
  exit -1;
fi
# set up a trap to delete the pid, then create the pid with date 0 to compile everything
trap "rm -f $timestamp; exit 1" 0 1 2 3 13 15
touch -t "197001010000" "$timestamp"

mvn -f $pom dependency:build-classpath -Dmdep.outputFile=`pwd`/cp.txt
j2cl_cp=$j2cl_cp:`cat cp.txt`
rm cp.txt

mvn -f $pom dependency:build-classpath -Dmdep.outputFile=`pwd`/cp.txt -Dclassifier=jszip -Dtype=zip
jszip="$base_zips --jszip `cat cp.txt | sed -e 's/:/ --jszip /g'`"
rm cp.txt

echo js classpath
echo $jszip
echo java classpath
echo $j2cl_cp

# spin, looking for changed files and recompiling them
while true
do
    compile_changed
done