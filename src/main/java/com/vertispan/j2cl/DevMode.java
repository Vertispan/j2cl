package com.vertispan.j2cl;

import com.google.common.base.Preconditions;
import com.google.j2cl.transpiler.J2clTranspiler;
import com.google.j2cl.transpiler.J2clTranspiler.Result;
import com.google.javascript.jscomp.*;
import com.google.javascript.jscomp.Compiler;
import com.google.javascript.jscomp.CompilerOptions.DependencyMode;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import javax.tools.*;
import javax.tools.JavaCompiler.CompilationTask;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.io.Files.createTempDir;

/**
 * Simple "dev mode" for j2cl+closure, based on the existing bash script. Lots of room for improvement, this
 * isn't intended to be a proposal, just another experiment on the way to one.
 *
 * Assumptions:
 *   o The js-compatible JRE is already on the classpath. Probably not a good one, but on the other hand, we
 *     may want to allow changing out the JRE (or skipping it) in favor of something else.
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
 *   o Haven't yet worked out how to get Closure to notice incremental changes, may be easier to do this by hand.
 *   o Not at all convinced my javac wiring is correct
 *   o Polling for changes
 */
public class DevMode {
    public static class Options {
        @Option(name = "-src", usage = "specify one or more java source directories", required = true)
        List<String> sourceDir;
        @Option(name = "-classes", usage = "provide a directory to put compiled bytecode in", required = true)
        String classesDir;
        @Option(name = "-jsClasspath", usage = "specify js archive classpath", required = true)
        String j2clClasspath;
        @Option(name = "-classpath", usage = "specify java classpath", required = true)
        String bytecodeClasspath;
        @Option(name = "-out", usage = "indicates where to write generated JS sources", required = true)
        String outputJsPath;

        @Option(name = "-entrypoint", usage = "The entrypoint class", required = true)
        String entrypoint;

    }

    private static PathMatcher javaMatcher = FileSystems.getDefault().getPathMatcher("glob:**/*.java");
    private static PathMatcher nativeJsMatcher = FileSystems.getDefault().getPathMatcher("glob:**/*.native.js");
    private static PathMatcher jsMatcher = FileSystems.getDefault().getPathMatcher("glob:**/*.js");

    public static void main(String[] args) throws IOException, InterruptedException, ExecutionException, NoSuchAlgorithmException {

        Options options = new Options();
        CmdLineParser parser = new CmdLineParser(options);
        try {
            parser.parseArgument(args);
        } catch (CmdLineException e) {
            e.printStackTrace();
            parser.printUsage(System.err);
            System.exit(1);
        }

        String intermediateJsPath = createTempDir().getAbsolutePath();//TODO allow this to be configurable
//        System.out.println("intermediate js path " + intermediateJsPath);
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
        File classesDirFile = new File(options.classesDir);
        classesDirFile.mkdirs();
        Preconditions.checkState(classesDirFile.exists() && classesDirFile.isDirectory(), "either -classes does not point at a directory, or we couldn't create it");
        fileManager.setLocation(StandardLocation.CLASS_OUTPUT, Collections.singleton(classesDirFile));

        //put all j2clClasspath items into a list, we'll copy each time and add generated js
        List<String> baseJ2clArgs = Arrays.asList("-cp", options.bytecodeClasspath, "-d", intermediateJsPath, "-nativesourcepath", sourcesNativeZipPath);

        String intermediateJsOutput = options.outputJsPath + "/temp.js";
        List<String> baseClosureArgs = new ArrayList<>(Arrays.asList(
                "--compilation_level", CompilationLevel.BUNDLE.name(),// fastest way to build, just smush everything together
                "--js_output_file", intermediateJsOutput,//temp file to write to before we insert the missing line at the top
                "--dependency_mode", DependencyMode.STRICT.name(),//force STRICT mode so that the compiler at least orders the inputs
                "--entry_point", options.entrypoint//indicate where in the project to start when ordering dependendencies
        ));

        Compiler jsCompiler = new Compiler(System.err);
        jsCompiler.setPersistentInputStore(new PersistentInputStore());

        for (String zipPath : options.j2clClasspath.split(File.pathSeparator)) {
            Preconditions.checkArgument(new File(zipPath).exists() && new File(zipPath).isFile(), "jszip doesn't exist! %s", zipPath);

            baseClosureArgs.add("--jszip");
            baseClosureArgs.add(zipPath);
            jsCompiler.getPersistentInputStore().addInput(zipPath, "0");

        }
        baseClosureArgs.add("--js");
        baseClosureArgs.add(intermediateJsPath + "/**/*.js");//precludes default package


        FileTime lastModified = FileTime.fromMillis(0);
        FileTime lastSuccess = FileTime.fromMillis(0);

        while (true) {
            // currently polling for changes.
            // block until changes instead? easy to replace with filewatcher, just watch out for java9/osx issues...

            List<String> modifiedJavaFiles = new ArrayList<>();
            FileTime newerThan = lastModified;
            long pollStarted = System.currentTimeMillis();

            //this isn't quite right - should check for _at least one_ newer than lastModified, and if so, recompile all
            //newer than lastSuccess
            //also, should look for .native.js too, but not collect them
            for (String dir : options.sourceDir) {
                Files.find(Paths.get(dir),
                        Integer.MAX_VALUE,
                        (filePath, fileAttr) -> {
                            return !fileAttr.isDirectory()
                                    && fileAttr.lastModifiedTime().compareTo(newerThan) > 0
                                    && javaMatcher.matches(filePath);
                        })
                        .forEach(file -> modifiedJavaFiles.add(file.toString()));
            }
            long pollTime = System.currentTimeMillis() - pollStarted;
            // don't replace this until the loop finishes successfully, so we know the last time we started a successful compile
            FileTime nextModifiedIfSuccessful = FileTime.fromMillis(System.currentTimeMillis());

            if (modifiedJavaFiles.isEmpty()) {
                Thread.sleep(100);
                continue;
            }

            //collect native files in zip
            try (ZipOutputStream zipOutputStream = new ZipOutputStream(new FileOutputStream(sourcesNativeZipPath))) {
                for (String dir : options.sourceDir) {
//                    System.out.println("looking for native.js in " + Paths.get(dir));
                    Files.find(Paths.get(dir), Integer.MAX_VALUE, (path, attrs) -> nativeJsMatcher.matches(path)).forEach(file -> {
                        try {
//                            System.out.println("  found, attaching " + file + " aka " + Paths.get(dir).toAbsolutePath().relativize(file.toAbsolutePath()));
                            zipOutputStream.putNextEntry(new ZipEntry(Paths.get(dir).toAbsolutePath().relativize(file.toAbsolutePath()).toString()));
                            zipOutputStream.write(Files.readAllBytes(file));
                            zipOutputStream.closeEntry();
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
                }
            }
            
            System.out.println(modifiedJavaFiles.size() + " updated java files");
//            modifiedJavaFiles.forEach(System.out::println);

            // compile java files with javac into classesDir
            Iterable<? extends JavaFileObject> modifiedFileObjects = fileManager.getJavaFileObjectsFromStrings(modifiedJavaFiles);
            //TODO pass-non null for "classes" to properly kick apt?
            //TODO consider a different classpath for this tasks, so as to not interfere with everything else?

            long javacStarted = System.currentTimeMillis();
            CompilationTask task = compiler.getTask(null, fileManager, null, javacOptions, null, modifiedFileObjects);
            if (!task.call()) {
                //error occurred, should have been logged, skip the rest of this loop
                continue;
            }
            long javacTime = System.currentTimeMillis() - javacStarted;

            List<String> j2clArgs = new ArrayList<>(baseJ2clArgs);
            // add all modified Java files
            //TODO don't just use all generated classes, but look for changes maybe?
            j2clArgs.addAll(modifiedJavaFiles);

            Files.find(Paths.get(generatedClassesPath.getAbsolutePath()),
                    Integer.MAX_VALUE,
                    (filePath, fileAttr) ->
                            !fileAttr.isDirectory()
                            && javaMatcher.matches(filePath)
                            /*TODO check modified?*/
                    ).forEach(file -> j2clArgs.add(file.toString()));


            //recompile java->js
//            System.out.println(j2clArgs);

            // Sadly, can't do this, each run of the transpiler MUST be in its own thread, since it
            // can't seem to clean up its threadlocals.
            long j2clStarted = System.currentTimeMillis();
//            Result transpileResult = transpiler.transpile(j2clArgs.toArray(new String[0]));
            ExecutorService executorService = Executors.newSingleThreadExecutor();
            Future<Result> futureResult = executorService.submit(() -> {
                J2clTranspiler transpiler = new J2clTranspiler();
                return transpiler.transpile(j2clArgs.toArray(new String[0]));
            });
            Result transpileResult = futureResult.get();
            long j2clTime = System.currentTimeMillis() - j2clStarted;
            transpileResult.getProblems().report(System.out, System.err);
            executorService.shutdownNow();//technically the finalizer will call shutdown, but we can cleanup now
            if (transpileResult.getExitCode() != 0) {
                //print problems
                continue;
            }

            // TODO copy the generated .js files, so that we only feed the updated ones the jscomp, stop messing around with args...
            long jscompStarted = System.currentTimeMillis();
            if (!jscomp(baseClosureArgs, jsCompiler, intermediateJsPath)) {
                continue;
            }
            long jscompTime = System.currentTimeMillis() - jscompStarted;

            // Insert a line at the top to allow the app to start correctly
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(intermediateJsOutput)))) {
                try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(options.outputJsPath + "/app.js")))) {
                    writer.append("this[\"CLOSURE_UNCOMPILED_DEFINES\"] = {\"goog.ENABLE_DEBUG_LOADER\": false};\n");
                    String line;
                    while ((line = reader.readLine()) != null) {
                        writer.append(line);
                        writer.newLine();
                    }
                }
            }

            System.out.println("Recompile of " + modifiedJavaFiles.size() + " source classes finished in " + (System.currentTimeMillis() - nextModifiedIfSuccessful.to(TimeUnit.MILLISECONDS)) + "ms");
            System.out.println("poll: " + pollTime + "millis");
            System.out.println("javac: " + javacTime + "millis");
            System.out.println("j2cl: " + j2clTime + "millis");
            System.out.println("jscomp: " + jscompTime + "millis");
            lastModified = nextModifiedIfSuccessful;
        }
    }

    private static boolean jscomp(List<String> baseClosureArgs, Compiler jsCompiler, String updatedJsDirectories) throws IOException {
        //collect all js into one artifact (currently jscomp, but it would be wonderful to not pay quite so much for this...)
        List<String> jscompArgs = new ArrayList<>(baseClosureArgs);
//            System.out.println(jscompArgs);

        if (jsCompiler.getModules() != null) {
            jsCompiler.resetCompilerInput();
        }


        //sanity check args anyway
        CommandLineRunner jscompRunner = new InProcessJsCompRunner(jscompArgs.toArray(new String[0]), jsCompiler);
        if (!jscompRunner.shouldRunCompiler()) {
            return false;
        }

        //TODO replace with PersistentInputStore, once it has been around a little longer

        //for each file in the updated dir
        long timestamp = System.currentTimeMillis();
        Files.find(Paths.get(updatedJsDirectories), Integer.MAX_VALUE, (path, attrs) -> jsMatcher.matches(path)).forEach((Path path) -> {
//            // this seems like a sketchy way to build inputs, but we can be confident that no one else is feeding input
//            // js except us, so we just have to make them consistently.
//            CompilerInput input = jsCompiler.getInput(new InputId(path.toAbsolutePath().toString()));
//            if (input == null) {
//                //new file, add as new ast
//                jsCompiler.addNewScript(new JsAst(SourceFile.fromFile(path.toAbsolutePath().toString(), Charsets.UTF_8)));
//            } else {
//                //updated file
//                jsCompiler.replaceScript(new JsAst(SourceFile.fromFile(path.toAbsolutePath().toString(), Charsets.UTF_8)));
//            }

            //so this isn't what they meant, but we can just mark with the timestamp of this run - we know it was updated,
            //and we'll only mark the updated files anyway
            jsCompiler.getPersistentInputStore().addInput(path.toString(), timestamp + "");
            


        });
        //TODO how do we handle deleted files? If they are truely deleted, nothing should reference them, and the module resolution should shake them out, at only the cost of a little memory?

        // instead of running the compiler (say, with the PersistentInputStore), we will do what
        //com.google.javascript.jscomp.AbstractCommandLineRunner.outputManifestOrBundle() would try to do
        jscompRunner.run();
//        try (Writer out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(outputJs), UTF_8))) {
//            for (JSModule jsModule : jsCompiler.getModules()) {
//                ClosureBundler bundler = new ClosureBundler();
//
//                List<CompilerInput> inputs = jsModule.getInputs();
//                for (CompilerInput input : inputs) {
//                    // Every module has an empty file in it. This makes it easier to implement
//                    // cross-module code motion.
//                    //
//                    // But it also leads to a weird edge case because
//                    // a) If we don't have a module spec, we create a singleton module, and
//                    // b) If we print a bundle file, we copy the original input files.
//                    //
//                    // This means that in the (rare) case where we have no inputs, and no
//                    // module spec, and we're printing a bundle file, we'll have a fake
//                    // input file that shouldn't be copied. So we special-case this, to
//                    // make all the other cases simpler.
//
//                    //Compiler.createFillFileName(Compiler.SINGLETON_MODULE_NAME)
//                    if (input.getName().equals(
//                            "$singleton$$fillFile")) {
//                        checkState(1 == Iterables.size(inputs));
//                        break;
//                    }
//
//                    String rootRelativePath = rootRelativePathsMap.get(input.getName());
//                    String displayName = rootRelativePath != null
//                            ? rootRelativePath
//                            : input.getName();
//                    out.append("//");
//                    out.append(displayName);
//                    out.append("\n");
//
//                    bundler.appendTo(out, input, input.getSourceFile().getCode());
//
//                    out.append("\n");
//                }
//
//            }
//        }


        if (jscompRunner.hasErrors()) {
            return false;
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