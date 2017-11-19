#!/usr/bin/env bash
set -e

# First, build the transpiler from the bazel-build branch:
#   $ bazel build //transpiler/java/com/google/j2cl/transpiler:J2clTranspiler
# Then primitives (requires patch to correctly emit java primtives!):
#   $ bazel build //jre/java/javaemul/internal/vmbootstrap/primitives:primitives
# Then the full jre:
#   $ bazel build //jre/java:jre

source config.sh

# Finally, skip down to the end of the file to customize what dependencies you build, compile.


base_cp="$J2CL_REPO/bazel-bin/jre/java/jre.jar:$J2CL_REPO/bazel-bin/external/org_gwtproject_gwt/user/libgwt-javaemul-internal-annotations.jar"


# Call j2cl directly
J2clTranspiler() {
  echo $J2CL_REPO/bazel-bin/transpiler/java/com/google/j2cl/transpiler/J2clTranspiler "$@"
  time $J2CL_REPO/bazel-bin/transpiler/java/com/google/j2cl/transpiler/J2clTranspiler "$@"
}

GwtIncompatibleStripper() {
  $J2CL_REPO/bazel-bin/tools/java/com/google/j2cl/tools/gwtincompatible/GwtIncompatibleStripper "$@"
#  $J2CL_REPO/bazel-bin/internal_do_not_use/GwtIncompatibleStripper "$@"
}
# An apparently deprecated tool, first cut toward a "dev mode" sort of env. Currently this output is unused,
# but it is important to note that building and including it into the jszip doesn't negatively impact jscomp.
depswriter() {
 $CLOSURE_LIBRARY_REPO/closure/bin/build/depswriter.py "$@"
}

echo_and_run() { echo "$@" ; "$@" ; }

# First attempt at build tool for more-or-less j2cl compatible projects. This doesn't include jsinterop-base, because
# it is deliberately incompatible with j2cl. It is assumed that this has been invoked for ALL upstream deps, since
# my bash+mvn isn't good enough to skip things that don't need j2cl.
j2cl_compile_mvn_jar() {
    g=$1
    a=$2
    v=$3
    p=${4-jar}
    c=${5-}

    if [[ -z "$c" ]]; then
      jar=$g/$a-$v.$p
      gav=$g:$a:$v:$p
    else
      jar=$g/$a-$v-$c.$p
      gav=$g:$a:$v:$p:$c
    fi


    mkdir $g

    mvn dependency:copy -Dartifact=$gav -DoutputDirectory=$g
    mvn dependency:copy -Dartifact=$g:$a:$v:pom -DoutputDirectory=$g

    noprefix=${jar%.jar};
    srcjar="$noprefix.srcjar";
    strippedsrcjar="$noprefix-stripped.srcjar";
    cp "$jar" "$srcjar";

    GwtIncompatibleStripper -d $strippedsrcjar $srcjar

    mvn -f $g/$a-$v.pom dependency:build-classpath -Dmdep.outputFile=../cp.txt
    cp=`cat cp.txt`
    rm cp.txt

    mkdir out
    J2clTranspiler -cp "$base_cp:$cp" -d "out" "$strippedsrcjar"
    depswriter --root out/ > "out/$a-$v-deps.txt"

    pushd out
    zip -r ../$g/$a-$v.js.zip *
    popd
#    exit;

    mvn install:install-file -DrepositoryId=vertispan-releases -Durl=https://repo.vertispan.com/j2cl -Dpackaging=zip -Dclassifier=jszip -DgroupId=$g -DartifactId=$a -Dversion=$v -Dfile=$g/$a-$v.js.zip -Dpom=$g/pom.xml

    rm -rf out
    rm -rf $g
}

# Work around jsinterop-base, since it is incompatible with j2cl. Does not provide missing methods, only removes
# gwt-only code and re-inserts missing @JsMethods. Replacement for
#   $ j2cl_compile_mvn_jar com.google.jsinterop base 1.0.0-beta-1 jar
j2cl_compile_interop_base_bug() {
    v=1.0.0-beta-3

    mvn dependency:copy -Dartifact=com.google.jsinterop:base:$v:jar -DoutputDirectory=com.google.jsinterop
    mvn dependency:copy -Dartifact=com.google.jsinterop:base:$v:pom -DoutputDirectory=com.google.jsinterop

    pushd com.google.jsinterop
    unzip base-$v.jar

    pushd jsinterop/base/
    ls *.java | xargs sed -e 's/\/\/J2CL_ONLY //g' -i ''
#    ls *.java | xargs sed -e '/  @UncheckedCast/d' -i ''
#    ls *.java | xargs sed -e '/  @HasNoSideEffects/d' -i ''
    ls *.java | xargs sed -e '/  @UnsafeNativeLong/d' -i ''
#    ls *.java | xargs sed -e '/import javaemul.internal.annotations.UncheckedCast;/d' -i ''
#    ls *.java | xargs sed -e '/import javaemul.internal.annotations.HasNoSideEffects;/d' -i ''
    ls *.java | xargs sed -e '/import com.google.gwt.core.client.UnsafeNativeLong;/d' -i ''

    popd
    popd

    mvn -f com.google.jsinterop/base-$v.pom dependency:build-classpath -Dmdep.outputFile=../cp.txt
    cp=`cat cp.txt`
    rm cp.txt

    mkdir out
    J2clTranspiler -cp "$base_cp:$cp" -d "out" com.google.jsinterop/jsinterop/base/*.java
    depswriter --root out/ > out/base-$v-deps.txt
    
    pushd out
    zip -r ../com.google.jsinterop/base-$v.js.zip *
    popd

    mvn install:install-file -DrepositoryId=vertispan-releases -Durl=https://repo.vertispan.com/j2cl -Dpackaging=zip -Dclassifier=jszip -DgroupId=com.google.jsinterop -DartifactId=base -Dversion=$v -Dfile=com.google.jsinterop/base-$v.js.zip

    rm -rf out
    rm -rf com.google.jsinterop

}

# Build up classpath of jszips via mvn, and include various emul and jre details from j2cl itself.
# Assumes the entrypoint module is called "app", and that it actually does something to start things
# going.
jscomp() {
    g=$1
    a=$2
    v=$3
    entrypoint=$4
    out=$5
    level=$6

    mkdir $g
    mvn dependency:copy -Dartifact=$g:$a:$v:zip:jszip -DoutputDirectory=$g
    mvn dependency:copy -Dartifact=$g:$a:$v:pom -DoutputDirectory=$g

    mvn -f $g/$a-$v.pom dependency:build-classpath -Dmdep.outputFile=../cp.txt -Dclassifier=jszip -Dtype=zip
    cp=`cat cp.txt | sed -e 's/:/ --jszip /g'`
    rm cp.txt
    jszip="--jszip $cp --jszip $g/$a-$v-jszip.zip"

    echo_and_run time java -server -XX:+TieredCompilation -Xmx1g \
        -jar $CLOSURE_COMPILER_JAR \
        --entry_point 'app' \
        --js $entrypoint $jszip \
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
        --compilation_level $level \
        --js_output_file "$5"

    rm -rf $g
}



# my classpath trickery is messy, so this is needed, even though it generates a nearly empty zip.
j2cl_compile_mvn_jar com.google.jsinterop jsinterop-annotations 1.0.1 jar

j2cl_compile_interop_base_bug

j2cl_compile_mvn_jar com.google.elemental2 elemental2-core 1.0.0-beta-1 jar
j2cl_compile_mvn_jar com.google.elemental2 elemental2-promise 1.0.0-beta-1 jar
j2cl_compile_mvn_jar com.google.elemental2 elemental2-dom 1.0.0-beta-1 jar

#j2cl_compile_mvn_jar com.google.guava guava-gwt HEAD-jre-SNAPSHOT jar
#j2cl_compile_mvn_jar io.playn playn-html 2.1-SNAPSHOT jar sources
#j2cl_compile_mvn_jar superstore concurrent-trees 2.6.0 jar
#j2cl_compile_mvn_jar superstore cqengine 2.7.1 jar

#TODO add more as needed


j2cl_compile_mvn_jar com.vertispan.draw connected 0.1-SNAPSHOT jar sources

# Lastly, compile all deps from your project into JS
# Currently hardcoded to assume the entrypoint function name is exported as "app"
#      groupId            artifactId version      entrypoint outputname  compilationlevel
jscomp com.vertispan.draw connected  0.1-SNAPSHOT app.js     out.js      ADVANCED_OPTIMIZATIONS