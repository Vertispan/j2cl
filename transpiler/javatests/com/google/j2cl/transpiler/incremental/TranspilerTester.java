/*
 * Copyright 2017 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.j2cl.transpiler.incremental;

import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.google.common.base.Joiner;
import com.google.common.base.Predicates;
import com.google.common.base.Splitter;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.io.MoreFiles;
import com.google.common.truth.Correspondence;
import com.google.common.util.concurrent.Uninterruptibles;
import com.google.j2cl.common.SourceUtils;
import com.google.j2cl.common.Problems;
import com.google.j2cl.transpiler.frontend.Frontend;
import com.google.j2cl.transpiler.backend.Backend;

import com.google.j2cl.transpiler.J2clTranspilerOptions;
import junit.framework.Assert;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/** Apis for end to end tests on the transpiler. */
public class TranspilerTester {
  /** Creates a new transpiler tester. */
  public static TranspilerTester newTester() {
    return new TranspilerTester();
  }


  /**
   * Creates a new transpiler tester initialized with the defaults (e.g. location for the JRE, etc)
   * @param classesOutPath
   */
  public static TranspilerTester newTesterWithDefaults(Path classesOutPath, Class cls) {
    String path = "com.google.j2cl.transpiler.incremental".replace('.', '/');//cls.getPackageName().replace('.', '/');

    return newTester()
        .setJavaPackage("test")
        .setClassesOutPath(classesOutPath)
        .setClassPath("transpiler/javatests/" + path + "/jre_bundle_deploy.jar");
  }

  public static TranspilerTester newTesterWithDefaults(Path classesOutPath, Class cls, String classPath) {
    String path = "com.google.j2cl.transpiler.incremental".replace('.', '/');//cls.getPackageName().replace('.', '/');

    return newTester()
            .setJavaPackage("test")
            .setClassesOutPath(classesOutPath)
            .setClassPath(classPath + ":" + "transpiler/javatests/" + path + "/jre_bundle_deploy.jar");
  }

  public static class File {
    private Path filePath;
    private String content;

    public File(Path filePath, String content) {
      this.filePath = filePath;
      this.content = content;
    }

    public Path getFilePath() {
      return filePath;
    }

    public void createFileIn(Path basePath) {
      try {
        Files.write(basePath.resolve(filePath), content.getBytes(Charset.forName("UTF-8")));
      } catch (IOException e) {
        throw new AssertionError(e);
      }
    }

    public boolean isNativeJsFile() {
      return filePath.toString().endsWith(".native.js");
    }

    public boolean isJavaSourceFile() {
      return filePath.toString().endsWith(".java");
    }

    public boolean isSrcJar() {
      return filePath.toString().endsWith(".srcjar");
    }
  }

  private List<File>   files = new ArrayList<>();
  private List<String> args = new ArrayList<>();
  private String       temporaryDirectoryPrefix = "transpile_tester";
  private String       packageName = "";
  private Path         outputPath;
  private Path         tempPath;
  private Path         classesOutPath;
  private String       classPath;
  private boolean      writeTypeGraph = false;

  public TranspilerTester setWriteTypeGraph(boolean writeTypeGraph) {
    this.writeTypeGraph = writeTypeGraph;
    return this;
  }

  public TranspilerTester addCompilationUnit(String compilationUnitName, String... code) {
    List<String> content = new ArrayList<>(Arrays.asList(code));
    if (!packageName.isEmpty()) {
      content.add(0, "package " + packageName + ";");
    }
    return addPath(
        getPackageRelativePath(compilationUnitName + ".java"), Joiner.on('\n').join(content));
  }

  public TranspilerTester removeCompilationUnit(String compilationUnitName) {
    Path path = getPackageRelativePath(compilationUnitName + ".java");
    Path inputPath = tempPath.resolve("input");
    try {
      Files.delete(inputPath.resolve(path));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return this;
  }

  public TranspilerTester addNativeFile(String compilationUnitName, String... code) {
    return addPath(
        getPackageRelativePath(compilationUnitName + ".native.js"), Joiner.on('\n').join(code));
  }

  public TranspilerTester addFile(String filename, String content) {
    return addPath(Paths.get(filename), content);
  }

  private TranspilerTester addPath(Path filePath, String content) {
    this.files.add(new File(filePath, content));
    return this;
  }

  public Path getClassesOutPath() {
    return classesOutPath;
  }

  public TranspilerTester setClassPath(String path) {
    if ( classesOutPath != null ) {
      classPath = toTestPath(path) + ":" + toTestPath(classesOutPath.toAbsolutePath().toString());
    } else {
      classPath = toTestPath(path);
    }

    return this.addArgs("-cp", classPath);
  }

  public TranspilerTester setNativeSourcePath(String path) {
    return this.addArgs("-nativesourcepath", toTestPath(path));
  }

  public TranspilerTester addSourcePath(String path) {
    return this.addArgs(toTestPath(path));
  }

  private static String toTestPath(String path) {
    return path;
  }

  public TranspilerTester setArgs(String... args) {
    return setArgs(Arrays.asList(args));
  }

  public TranspilerTester setArgs(Collection<String> args) {
    this.args = new ArrayList<>(args);
    return this;
  }

  public TranspilerTester addArgs(String... args) {
    return addArgs(Arrays.asList(args));
  }

  public TranspilerTester addArgs(Collection<String> args) {
    this.args.addAll(args);
    return this;
  }

  public TranspilerTester setJavaPackage(String packageName) {
    this.packageName = packageName;
    return this;
  }

  public TranspilerTester setOutputPath(Path outputPath) {
    this.outputPath = outputPath;
    return this;
  }

  public Path getOutputPath() {
    return outputPath;
  }

  public TranspilerTester setTempPath(Path tempPath) {
    this.tempPath = tempPath;
    return this;
  }

  public TranspilerTester setClassesOutPath(Path classesOutPath) {
    this.classesOutPath = classesOutPath;
    return this;
  }
  public TranspileResult assertTranspileSucceeds() {
    return transpile().assertNoErrors();
  }

  public TranspileResult assertTranspileFails() {
    return transpile().assertHasErrors();
  }

  /** A bundle of data recording the results of a transpile operation. */
  public static class TranspileResult {
    private final Problems problems;
    private final Path             outputPath;
    private final TypeGraphManager typeGraphManager;

    public TranspileResult(Problems problems, Path outputPath) {
      this(problems, outputPath, null);
    }

    public TranspileResult(Problems problems, Path outputPath, TypeGraphManager typeGraphManager) {
      this.problems = problems;
      this.outputPath = outputPath;
      this.typeGraphManager = typeGraphManager;
    }

    public Problems getProblems() {
      return problems;
    }

    public Path getOutputPath() {
      return outputPath;
    }

    public List<String> getOutputSource(String outputFile) throws IOException {
      Path outputFilePath = outputPath.resolve(outputFile);
      assertThat(outputFilePath.toFile().exists()).isTrue();
      return Files.readAllLines(outputFilePath);
    }

    public TypeGraphManager getTypeGraphManager() {
      return typeGraphManager;
    }

    public TranspileResult assertNoErrors() {
      assertThat(getProblems().getErrors()).isEmpty();
      return this;
    }

    public TranspileResult assertHasErrors() {
      assertThat(getProblems().getErrors()).isNotEmpty();
      return this;
    }

    private static List<Path> toFileNames(List<Path> original) {
      return original.stream().map(Path::getFileName).collect(ImmutableList.toImmutableList());
    }

    private static final Pattern messagePattern =
        Pattern.compile("(?:(?:Error)|(?:Warning))(?::[\\w.]+:\\d+)?: (?<message>.*)");

    private static final Correspondence<String, String> ERROR_WITHOUT_SOURCE_POSITION_COMPARATOR =
        Correspondence.from(TranspileResult::compare, "contained within");

    private static boolean compare(String actual, String expected) {
      Matcher matcher = messagePattern.matcher(actual);
      checkState(matcher.matches());
      return matcher.group("message").equals(expected);
    }
  }

  private TranspileResult transpile() {
    try {
      return transpileDirect();
    } catch (Exception e) {
      e.printStackTrace();
      Problems problems = new Problems();
      problems.error("%s", e.toString());
      return new TranspileResult(problems, outputPath);
    }
  }

  public void createOutPath(Path tempDir) throws IOException {
    if (outputPath == null) {
      outputPath = tempDir.resolve("output");
      Files.createDirectories(outputPath);
    }
  }

  public List<String> generateFiles(Path inputPath) throws IOException {
    Files.createDirectories(inputPath);

    checkState(!packageName.contains(".") && !packageName.contains("/"));
    Path packagePath = inputPath.resolve(packageName);
    Files.createDirectories(packagePath);

    // 2. Create all new declared files on disk
    files.forEach(file -> file.createFileIn(inputPath));

    return getAllFiles(packagePath, p -> true).stream()
                                   .map(file -> inputPath.resolve(file.getFilePath()))
                                   .map(Path::toAbsolutePath)
                                   .map(Path::toString)
                                   .collect(toImmutableList());
  }

  private TranspileResult transpileDirect() throws Exception  {
    Path tempDir = (this.tempPath != null) ? this.tempPath : Files.createTempDirectory(temporaryDirectoryPrefix);
    this.tempPath = tempDir;

    createOutPath(tempDir);

    J2clTranspilerOptions.Builder builder = J2clTranspilerOptions.newBuilder();

    //if (!files.isEmpty()) {
      Problems problems = new Problems();
      // 1. Create an input directory
      Path inputPath = tempDir.resolve("input");

      generateFiles(inputPath);

      Path packagePath = inputPath.resolve(packageName);
      List<String> dirs = new ArrayList<>();
      dirs.add(inputPath.toString());

      // This runs before, to get the calculated list of files to pass to J2CL
      //System.out.println("dp start:" + paths);
      TypeGraphStore dependencyManager = new TypeGraphStore();
      dependencyManager.calculateChangeSet(this.outputPath, dirs);
      dependencyManager.write();
      //System.out.println("dp end");

    List<SourceUtils.FileInfo> javaSources = getSources(problems, inputPath, packagePath, dependencyManager,
                                                               p -> p.endsWith(".java"));
    builder.setSources(javaSources);

    List<SourceUtils.FileInfo> nativeSources = getSources(problems, inputPath, packagePath, dependencyManager,
                                                                     p -> p.endsWith(".native.js"));

    builder.setNativeSources(nativeSources);

    builder.setClasspaths(getPathEntries(this.classPath));
    builder.setOutput(this.outputPath);
    builder.setEmitReadableSourceMap(false);
    builder.setEmitReadableLibraryInfo(false);
    builder.setGenerateKytheIndexingMetadata(false);
    builder.setFrontend(Frontend.JAVAC);
    builder.setBackend(Backend.CLOSURE);
    builder.setWriteTypeGraph(this.writeTypeGraph);
    //}

    J2clTranspilerOptions options = builder.build();
    try {
      TranspileResult results = transpileDirect(options, outputPath);
      return results;
    } catch (Exception e) {
      e.printStackTrace();
      problems = new Problems();
      problems.error("%s", e.toString());
      return new TranspileResult(problems, outputPath);
    }
  }

  private List<SourceUtils.FileInfo> getSources(Problems problems, Path inputPath, Path packagePath, TypeGraphStore dependencyManager,
                                                           Predicate<String> p) throws IOException {
    ImmutableList<String> allSources = getAllFiles(packagePath, p).stream()
                                                                  .map(file -> file.getFilePath().toAbsolutePath().toString())
                                                                  .collect(toImmutableList());

    Map<String, SourceUtils.FileInfo> allFileInfos =  SourceUtils.getAllSources(allSources, problems)
                                                              .collect(Collectors.toMap(SourceUtils.FileInfo::sourcePath, file -> file));
    if (!allFileInfos.isEmpty()) {
      List<String> relSources = dependencyManager.getSources();
      List<String> absSources = relSources.stream().map(f -> inputPath.toString() + java.io.File.separator + f).collect(Collectors.toList());
      ImmutableList<SourceUtils.FileInfo> sources = absSources.stream().filter(source -> p.test(source))
                                                                .map(sourcePath -> allFileInfos.get(sourcePath))
                                                                .collect(ImmutableList.toImmutableList());
      return sources;
    }

    return Collections.emptyList();
  }

  private static TranspileResult transpileDirect(J2clTranspilerOptions options, Path outputPath) throws Exception {
    ExecutorService executorService = Executors.newSingleThreadExecutor();
    Future<TranspileResult> result = executorService.submit(() -> invokeDirect(options, outputPath));
    executorService.shutdown();

    try {
      return Uninterruptibles.getUninterruptibly(result);
    } catch (ExecutionException e) {
      // Try unwrapping the cause...
      Throwables.throwIfUnchecked(e.getCause());
      throw new AssertionError(e.getCause());
    }
  }

  private static TranspileResult invokeDirect(J2clTranspilerOptions options, Path outputPath) throws NoSuchMethodException, InstantiationException, IllegalAccessException, java.lang.reflect.InvocationTargetException, ClassNotFoundException {
    Problems problems = new Problems();
    Class<?> cls = Class.forName("com.google.j2cl.transpiler.J2clTranspiler");
    Constructor<?> constructor = cls.getDeclaredConstructor(J2clTranspilerOptions.class, Problems.class);
    constructor.setAccessible(true);
    Object j2cl = constructor.newInstance(options, problems);

    Method transpileMethod = cls.getDeclaredMethod("transpileImpl");
    transpileMethod.setAccessible(true);

    transpileMethod.invoke(j2cl);

    Method getTypeGraphManagerMethod = cls.getDeclaredMethod("getTypeGraphManager");
    getTypeGraphManagerMethod.setAccessible(true);
    TypeGraphManager incrementalManager = (TypeGraphManager) getTypeGraphManagerMethod.invoke(j2cl);

    return new TranspileResult(problems, outputPath, incrementalManager);
  }

  public TranspilerTester compile() {
    try {
      Path tempDir = (this.tempPath != null) ? this.tempPath : Files.createTempDirectory(temporaryDirectoryPrefix);

      if (tempDir != null) {
        // 1. Create an input directory
        Path inputPath = tempDir.resolve("input");
        Files.createDirectories(inputPath);

        checkState(!packageName.contains(".") && !packageName.contains("/"));
        Path packagePath = inputPath.resolve(packageName);
        Files.createDirectories(packagePath);

        // 2. Create all new declared files on disk
        files.forEach(file -> file.createFileIn(inputPath));

        List<String> fileNames = getAllFiles(packagePath, p -> p.toString().endsWith(".java")).stream()
                                                         .map(file -> inputPath.resolve(file.getFilePath()))
                                                         .map(Path::toAbsolutePath)
                                                         .map(Path::toString)
                                                         .collect(toImmutableList());
        new JavacHelper().compile(fileNames, classPath, classesOutPath.toFile());

      }
    } catch (IOException e) {
      e.printStackTrace();
    }

    return this;
  }

  private ImmutableList<File> getAllFiles(Path packagePath, Predicate<String> p) throws IOException {
    return Files.find(packagePath, Integer.MAX_VALUE,
                      (path, basicFileAttributes) -> p.test(path.toString())).map(path -> new File(path, null)).collect(toImmutableList());
  }

  private Path createNativeZipFile(Path inputPath, List<File> nativeSources, String outputFileName)
      throws IOException {
    Path zipFilePath = inputPath.resolve(outputFileName);

    try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zipFilePath.toFile()))) {
      for (File nativeSource : nativeSources) {
        Path nativeSourceAbsolutePath =
            inputPath.resolve(nativeSource.getFilePath()).toAbsolutePath();
        out.putNextEntry(new ZipEntry(inputPath.relativize(nativeSourceAbsolutePath).toString()));
        Files.copy(nativeSourceAbsolutePath, out);
        out.closeEntry();
      }
    }

    return zipFilePath;
  }

  private Path getPackageRelativePath(String compilationUnitName) {
    Path filePath = Paths.get(compilationUnitName);
    return packageName != null && !packageName.isEmpty()
        ? Paths.get(packageName).resolve(filePath)
        : filePath;
  }

  private static List<String> getPathEntries(String path) {
    List<String> entries = new ArrayList<>();
    for (String entry : Splitter.on(java.io.File.pathSeparatorChar).omitEmptyStrings().split(path)) {
      if (new java.io.File(entry).exists()) {
        entries.add(entry);
      }
    }
    return entries;
  }

  private TranspilerTester() {}
}
