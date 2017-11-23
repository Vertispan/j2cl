package com.vertispan.j2cl;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.j2cl.common.Problems;
import com.google.j2cl.frontend.FrontendUtils;
import com.google.j2cl.generator.NativeJavaScriptFile;
import com.google.j2cl.tools.gwtincompatible.JavaPreprocessor;
import com.google.j2cl.transpiler.J2clTranspiler;
import com.google.j2cl.transpiler.J2clTranspiler.Result;
import com.google.javascript.jscomp.CommandLineRunner;
import com.google.javascript.jscomp.CompilationLevel;
import com.google.javascript.jscomp.Compiler;
import com.google.javascript.jscomp.CompilerOptions.DependencyMode;
import com.google.javascript.jscomp.PersistentInputStore;
import org.apache.commons.codec.digest.DigestUtils;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import javax.tools.*;
import javax.tools.JavaCompiler.CompilationTask;
import java.io.*;
import java.nio.file.FileSystem;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.google.common.io.Files.createTempDir;

/**
 * Simple "dev mode" for j2cl+closure, based on the existing bash script. Lots of room for improvement, this
 * isn't intended to be a proposal, just another experiment on the way to one.
 *
 * Assumptions:
 *   o The js-compatible JRE is already on the java classpath (need not be on js). Probably not a good one, but
 *     on the other hand, we may want to allow changing out the JRE (or skipping it) in favor of something else.
 *   o A JS entrypoint already exists. Probably safe, should get some APT going soon as discussed, at least to
 *     try it out.
 *
 * Things about this I like:
 *   o Treat both jars and jszips as classpaths (ease of dependency system integrations)
 *   o Annotation processors are (or should be) run as an IDE would do, so all kinds of changes are picked up. I
 *     think I got it right to pick up generated classes changes too...
 *
 * Not so good:
 *   o J2CL seems deliberately difficult to integrate (no public, uses threadlocals)
 *   o Not correctly recompiling classes that require it based on dependencies
 *   o Not at all convinced my javac wiring is correct
 *   o Polling for changes
 */
//NOTE: It appears a tmp directory for classes is not created on Fedora 27
public class DevMode {

    public static class Options {

        @Option(name = "-src", usage = "specify one or more java source directories", required = true)
        List<String> sourceDir = new ArrayList<>();
        @Option(name = "-classpath", usage = "specify java classpath", required = true)
        String bytecodeClasspath;

        @Option(name = "-jsClasspath",
                usage = "specify js archive classpath that won't be transpiled from sources or classpath. If nothing else, should include bootstrap.js.zip",
                required = true)
        String j2clClasspath;

        @Option(name = "-out",
                usage = "indicates where to write generated JS sources, sourcemaps, etc. Should be a directory specific to gwt, anything may be overwritten there.",
                required = true)
        String outputJsPathDir;

        @Option(name = "-classes",
                usage = "provide a directory to put compiled bytecode in. if not specified, a tmp dir will be used",
                required = false)
        String classesDir;

        @Option(name = "-entrypoint", usage = "The entrypoint class", required = true)
        List<String> entrypoint = new ArrayList<>();

        @Option(name = "-jsZipCache",
                usage = "directory to cache generated jszips in. Should be cleared when j2cl version changes")
        String jsZipCacheDir;

        //lifted straight from closure for consistency
        @Option(name = "--define",
                aliases = {"--D", "-D"},
                usage = "Override the value of a variable annotated @define. "
                        + "The format is <name>[=<val>], where <name> is the name of a @define "
                        + "variable and <val> is a boolean, number, or a single-quoted string "
                        + "that contains no single quotes. If [=<val>] is omitted, "
                        + "the variable is marked true")
        private List<String> define = new ArrayList<>();

    }

    private static PathMatcher javaMatcher = FileSystems.getDefault().getPathMatcher("glob:**/*.java");
    private static PathMatcher nativeJsMatcher = FileSystems.getDefault().getPathMatcher("glob:**/*.native.js");
    private static PathMatcher jsMatcher = FileSystems.getDefault().getPathMatcher("glob:**/*.js");

    public static void main(String[] args) throws IOException, InterruptedException, ExecutionException,
            NoSuchAlgorithmException {

        Options options = new Options();
        CmdLineParser parser = new CmdLineParser(options);
        try {
            parser.parseArgument(args);
        } catch (CmdLineException e) {
            e.printStackTrace();
            parser.printUsage(System.err);
            System.exit(1);
        }

        String intermediateJsPath = createDir(options.outputJsPathDir + "/sources").getPath();
        System.out.println("intermediate js from j2cl path " + intermediateJsPath);
        File generatedClassesPath = createTempDir();//TODO allow this to be configurable
        //        System.out.println("generated classes path " + generatedClassesPath);
        String sourcesNativeZipPath = File.createTempFile("proj-native", ".zip").getAbsolutePath();

        options.bytecodeClasspath += ":" + options.classesDir;
        List<File> classpath = new ArrayList<>();
        for (String path : options.bytecodeClasspath.split(File.pathSeparator)) {
            //            System.out.println(path);
            classpath.add(new File(path));
        }

        List<String> javacOptions = Arrays.asList("-implicit:none");
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null);
        fileManager.setLocation(StandardLocation.SOURCE_PATH, Collections.emptyList());
        fileManager.setLocation(StandardLocation.SOURCE_OUTPUT, Collections.singleton(generatedClassesPath));
        fileManager.setLocation(StandardLocation.CLASS_PATH, classpath);
        File classesDirFile;
        if (options.classesDir != null) {
            classesDirFile = createDir(options.classesDir);
        } else {
            classesDirFile = createTempDir();
        }
        fileManager.setLocation(StandardLocation.CLASS_OUTPUT, Collections.singleton(classesDirFile));

        // put all j2clClasspath items into a list, we'll copy each time and add generated js
        List<String> baseJ2clArgs = Arrays.asList("-cp", options.bytecodeClasspath, "-d", intermediateJsPath);

        String intermediateJsOutput = options.outputJsPathDir + "/app.js";
        CompilationLevel compilationLevel = CompilationLevel.BUNDLE;
        List<String> baseClosureArgs = new ArrayList<>(Arrays.asList(
                "--compilation_level", compilationLevel.name(), // fastest way to build, just smush everything together
                "--js_output_file", intermediateJsOutput, // temp file to write to before we insert the missing line at the top
                "--dependency_mode", DependencyMode.STRICT.name()// force STRICT mode so that the compiler at least orders the inputs
        ));
        if (compilationLevel == CompilationLevel.BUNDLE) {
            // support BUNDLE mode, with no remote fetching for dependencies)
            baseClosureArgs.add("--define");
            baseClosureArgs.add("goog.ENABLE_DEBUG_LOADER=false");
        }

        ResourceListener resourceListener = new ResourceListener();
        for (String srcDir : options.sourceDir) {
            resourceListener.registerChangeListener(Paths.get(srcDir));
        }

        for (String define : options.define) {
            baseClosureArgs.add("--define");
            baseClosureArgs.add(define);
        }
        for (String entrypoint : options.entrypoint) {
            baseClosureArgs.add("--entry_point");
            baseClosureArgs.add(entrypoint);
        }

        // configure a persistent input store - we'll reuse this and not the compiler for now, to cache the ASTs,
        // and still allow jscomp to be in modes other than BUNDLE
        PersistentInputStore persistentInputStore = new PersistentInputStore();

        for (String zipPath : options.j2clClasspath.split(File.pathSeparator)) {
            Preconditions.checkArgument(new File(zipPath).exists() && new File(zipPath).isFile(),
                    "jszip doesn't exist! %s", zipPath);

            baseClosureArgs.add("--jszip");
            baseClosureArgs.add(zipPath);

            // add JS zip file to the input store - no nice digest, since so far we don't support changes to the zip
            persistentInputStore.addInput(zipPath, "0");
        }
        baseClosureArgs.add("--js");
        baseClosureArgs.add(intermediateJsPath + "/**/*.js");//precludes default package

        //pre-transpile all dependency sources to our cache dir, add those cached items to closure args
        List<String> transpiledDependencies = handleDependencies(options, classpath, baseJ2clArgs,
                persistentInputStore);
        baseClosureArgs.addAll(transpiledDependencies);

        FileTime lastModified = FileTime.fromMillis(0);
        FileTime lastSuccess = FileTime.fromMillis(0);

        while (true) {
            // currently polling for changes.
            // block until changes instead? easy to replace with filewatcher, just watch out for java9/osx issues...

            List<String> modifiedJavaFiles = new ArrayList<>();
            long pollStarted = System.currentTimeMillis();

            resourceListener.resetEvents();
            try {
                resourceListener.blockAndCollectEvents(false);
            } catch (Exception e) {
                continue;
            }
            final FileTime LAST_MODIFIED = lastModified;
            if (!(resourceListener.getModifiedFiles().stream()
                    .anyMatch((p) -> {
                        try {
                            return (javaMatcher.matches(p) || nativeJsMatcher.matches(p)) &&
                                    Files.getLastModifiedTime(p).compareTo(LAST_MODIFIED) > 0;
                        } catch (Exception e2) {
                            return false;
                        }
                    }) ||
                    resourceListener.getCreatedFiles().stream()
                            .anyMatch((p) -> {
                                try {
                                    return (javaMatcher.matches(p) || nativeJsMatcher.matches(p)) &&
                                            Files.getLastModifiedTime(p).compareTo(LAST_MODIFIED) > 0;
                                } catch (Exception e2) {
                                    return false;
                                }
                            }))) {
                continue;
            }
            HashSet<Path> allModifiedFiles = new HashSet<Path>();
            allModifiedFiles.addAll(resourceListener.getModifiedFiles());
            allModifiedFiles.addAll(resourceListener.getCreatedFiles());

            modifiedJavaFiles.addAll(allModifiedFiles.stream()
                    .filter((p) -> javaMatcher.matches(p))
                    .map((p) -> p.toFile().getAbsolutePath())
                    .collect(Collectors.toSet()));

            FileTime newerThan = lastModified;

            long pollTime = System.currentTimeMillis() - pollStarted;

            // don't replace this until the loop finishes successfully, so we know the last time we started a successful compile
            FileTime nextModifiedIfSuccessful = FileTime.fromMillis(System.currentTimeMillis());

            //collect native files in zip, but only if that file is also present in the changed .java sources
            boolean anyNativeJs = false;
            try (ZipOutputStream zipOutputStream = new ZipOutputStream(new FileOutputStream(sourcesNativeZipPath))) {
                for (String dir : options.sourceDir) {
                    anyNativeJs |= Files.find(Paths.get(dir), Integer.MAX_VALUE, (path, attrs) -> shouldZip(path,
                            modifiedJavaFiles)).reduce(false, (ignore, file) -> {
                                try {
                                    zipOutputStream.putNextEntry(new ZipEntry(Paths.get(dir).toAbsolutePath()
                                            .relativize(file.toAbsolutePath()).toString()));
                                    zipOutputStream.write(Files.readAllBytes(file));
                                    zipOutputStream.closeEntry();
                                    return true;
                                } catch (IOException e) {
                                    throw new UncheckedIOException(e);
                                }
                            }, (a, b) -> a || b);
                }
            }

            System.out.println(modifiedJavaFiles.size() + " updated java files");
            //            modifiedJavaFiles.forEach(System.out::println);

            // compile java files with javac into classesDir
            Iterable<? extends JavaFileObject> modifiedFileObjects = fileManager.getJavaFileObjectsFromStrings(
                    modifiedJavaFiles);
            //TODO pass-non null for "classes" to properly kick apt?
            //TODO consider a different classpath for this tasks, so as to not interfere with everything else?

            long javacStarted = System.currentTimeMillis();
            CompilationTask task = compiler.getTask(null, fileManager, null, javacOptions, null, modifiedFileObjects);
            if (!task.call()) {
                //error occurred, should have been logged, skip the rest of this loop
                continue;
            }
            long javacTime = System.currentTimeMillis() - javacStarted;

            // add all modified Java files
            //TODO don't just use all generated classes, but look for changes maybe?

            Files.find(Paths.get(generatedClassesPath.getAbsolutePath()),
                    Integer.MAX_VALUE,
                    (filePath, fileAttr) -> !fileAttr.isDirectory()
                            && javaMatcher.matches(filePath)
            /*TODO check modified?*/
            ).forEach(file -> modifiedJavaFiles.add(file.toString()));

            // run preprocessor on changed files
            File processed = File.createTempFile("preprocessed", ".srcjar");
            try (FileSystem out = FrontendUtils.initZipOutput(processed.getAbsolutePath(), new Problems())) {
                JavaPreprocessor.preprocessFiles(modifiedJavaFiles, out, new Problems());
            }

            List<String> j2clArgs = new ArrayList<>(baseJ2clArgs);
            if (anyNativeJs) {
                j2clArgs.add("-nativesourcepath");
                j2clArgs.add(sourcesNativeZipPath);
            }
            j2clArgs.add(processed.getAbsolutePath());

            long j2clStarted = System.currentTimeMillis();
            Result transpileResult = transpile(j2clArgs);
            //            System.out.println(j2clArgs);

            processed.delete();

            if (transpileResult.getExitCode() != 0) {
                //print problems
                continue;
            }
            long j2clTime = System.currentTimeMillis() - j2clStarted;

            // TODO copy the generated .js files, so that we only feed the updated ones the jscomp, stop messing around with args...
            long jscompStarted = System.currentTimeMillis();
            if (!jscomp(baseClosureArgs, persistentInputStore, intermediateJsPath)) {
                continue;
            }
            long jscompTime = System.currentTimeMillis() - jscompStarted;

            System.out.println("Recompile of " + modifiedJavaFiles.size() + " source classes finished in " + (System
                    .currentTimeMillis() - nextModifiedIfSuccessful.to(TimeUnit.MILLISECONDS)) + "ms");
            System.out.println("poll: " + pollTime + "millis");
            System.out.println("javac: " + javacTime + "millis");
            System.out.println("j2cl: " + j2clTime + "millis");
            System.out.println("jscomp: " + jscompTime + "millis");
            lastModified = nextModifiedIfSuccessful;
        }
    }

    private static List<String> handleDependencies(Options options, List<File> classpath, List<String> baseJ2clArgs,
            PersistentInputStore persistentInputStore) throws IOException, InterruptedException, ExecutionException {
        List<String> additionalClosureArgs = new ArrayList<>();
        for (File file : classpath) {
            //TODO maybe skip certain files that have already been transpiled
            if (file.isDirectory()) {
                continue;//...hacky, but probably just classes dir
            }

            // hash the file, see if we already have one
            String hash = hash(file);
            String jszipOut = options.jsZipCacheDir + "/" + hash + "-" + file.getName() + ".js.zip";
            File jszipOutFile = new File(jszipOut);
            if (jszipOutFile.exists()) {
                additionalClosureArgs.add("--jszip");
                additionalClosureArgs.add(jszipOut);

                persistentInputStore.addInput(jszipOut, "0");
                continue;//already exists, we'll use it
            }

            // run preprocessor
            File processed = File.createTempFile("preprocessed", ".srcjar");
            try (FileSystem out = FrontendUtils.initZipOutput(processed.getAbsolutePath(), new Problems())) {
                ImmutableList<String> allSources = FrontendUtils.getAllSources(Collections.singletonList(file
                        .getAbsolutePath()), new Problems());
                if (allSources.isEmpty()) {
                    System.out.println("no sources in file " + file);
                    continue;
                }
                JavaPreprocessor.preprocessFiles(allSources, out, new Problems());
            }

            List<String> pretranspile = new ArrayList<>(baseJ2clArgs);
            pretranspile.addAll(Arrays.asList("-cp", options.bytecodeClasspath, "-d", jszipOut, processed
                    .getAbsolutePath()));
            Result result = transpile(pretranspile);

            processed.delete();
            if (result.getExitCode() == 0) {
                additionalClosureArgs.add("--jszip");
                additionalClosureArgs.add(jszipOut);

                persistentInputStore.addInput(jszipOut, "0");
            } else {
                jszipOutFile.delete();
                // ignoring failure for now, TODO don't!
                // This is actually slightly tricky - we can't cache failure, since the user might stop and fix the classpath
                // and then the next build will work, but on the other hand we don't want to fail building jsinterop-base
                // over and over again either.
                System.out.println("Failed compiling " + file);
            }
        }
        return additionalClosureArgs;
    }

    private static String hash(File file) {
        try (FileInputStream stream = new FileInputStream(file)) {
            return DigestUtils.md5Hex(stream);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Result transpile(List<String> j2clArgs) throws InterruptedException, ExecutionException {
        //recompile java->js
        //            System.out.println(j2clArgs);

        // Sadly, can't do this, each run of the transpiler MUST be in its own thread, since it
        // can't seem to clean up its threadlocals.
        //            Result transpileResult = transpiler.transpile(j2clArgs.toArray(new String[0]));
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        Future<Result> futureResult = executorService.submit(() -> {
            J2clTranspiler transpiler = new J2clTranspiler();
            return transpiler.transpile(j2clArgs.toArray(new String[0]));
        });
        Result transpileResult = futureResult.get();
        transpileResult.getProblems().report(System.err);
        executorService.shutdownNow();//technically the finalizer will call shutdown, but we can cleanup now
        return transpileResult;
    }

    private static File createDir(String path) {
        File f = new File(path);
        if (f.exists()) {
            Preconditions.checkState(f.isDirectory(), "path already exists but is not a directory " + path);
        } else if (!f.mkdirs()) {
            throw new IllegalStateException("Failed to create directory " + path);
        }
        return f;
    }

    private static boolean shouldZip(Path path, List<String> modifiedJavaFiles) {
        return nativeJsMatcher.matches(path) && matchesChangedJavaFile(path, modifiedJavaFiles);
    }

    private static boolean matchesChangedJavaFile(Path path, List<String> modifiedJavaFiles) {
        String pathString = path.toString();
        String nativeFilePath = pathString.substring(0, pathString.lastIndexOf(NativeJavaScriptFile.NATIVE_EXTENSION));
        return modifiedJavaFiles.stream().anyMatch(javaPath -> javaPath.startsWith(nativeFilePath));
    }

    private static boolean jscomp(List<String> baseClosureArgs, PersistentInputStore persistentInputStore,
            String updatedJsDirectories) throws IOException {
        // collect all js into one artifact (currently jscomp, but it would be wonderful to not pay quite so much for this...)
        List<String> jscompArgs = new ArrayList<>(baseClosureArgs);
        //        System.out.println(jscompArgs);

        // Build a new compiler for this run, but share the cached js ASTs
        Compiler jsCompiler = new Compiler(System.err);
        jsCompiler.setPersistentInputStore(persistentInputStore);

        // sanity check args
        CommandLineRunner jscompRunner = new InProcessJsCompRunner(jscompArgs.toArray(new String[0]), jsCompiler);
        if (!jscompRunner.shouldRunCompiler()) {
            return false;
        }

        // for each file in the updated dir
        long timestamp = System.currentTimeMillis();
        Files.find(Paths.get(updatedJsDirectories), Integer.MAX_VALUE, (path, attrs) -> jsMatcher.matches(path))
                .forEach((Path path) -> {
                    // add updated JS file to the input store with timestamp instead of digest for now
                    persistentInputStore.addInput(path.toString(), timestamp + "");
                });
        //TODO how do we handle deleted files? If they are truly deleted, nothing should reference them, and the module resolution should shake them out, at only the cost of a little memory?

        jscompRunner.run();

        if (jscompRunner.hasErrors()) {
            return false;
        }
        if (jsCompiler.getModules() != null) {
            // clear out the compiler input for the next goaround
            jsCompiler.resetCompilerInput();
        }
        return true;
    }

    static class InProcessJsCompRunner extends CommandLineRunner {

        private final Compiler compiler;

        InProcessJsCompRunner(String[] args, Compiler compiler) {
            super(args);
            this.compiler = compiler;
            setExitCodeReceiver(ignore -> null);
        }

        @Override
        protected Compiler createCompiler() {
            return compiler;
        }
    }

}
