package com.google.j2cl.transpiler.incremental;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.j2cl.common.SourceUtils;
import com.google.j2cl.common.Problems;
import com.sun.tools.javac.api.JavacTaskImpl;
import com.sun.tools.javac.file.JavacFileManager;

import static java.util.stream.Collectors.toList;

public class JavacHelper {
    private String temporaryDirectoryPrefix = "compiler_tester";

    public Problems compile(List<String> files, String classPath, File classesOutputPath) {
        Problems problems = new Problems();
        List<SourceUtils.FileInfo> fileInfos = SourceUtils.getAllSources(files, problems)
                     .filter(p -> p.sourcePath().endsWith(".java"))
                     .collect(ImmutableList.toImmutableList());

        compile(fileInfos, getPathEntries(classPath), classesOutputPath, problems);

        return problems;
    }

    /** Returns a map from file paths to compilation units after Javac parsing. */
    public void compile(List<SourceUtils.FileInfo> files, List<String> classpathEntries, File classesOutputPath, Problems problems) {

        if (files.isEmpty()) {
            return;
        }

        // The map must be ordered because it will be iterated over later and if it was not ordered then
        // our output would be unstable
        final Map<String, String> targetPathBySourcePath =
                files.stream().collect(Collectors.toMap(SourceUtils.FileInfo::sourcePath, SourceUtils.FileInfo::targetPath));

        try {
            JavaCompiler                        compiler    = ToolProvider.getSystemJavaCompiler();
            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            JavacFileManager fileManager =
                    (JavacFileManager)
                            compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8);
            List<File> searchpath = classpathEntries.stream().map(File::new).collect(toList());
            fileManager.setLocation(StandardLocation.PLATFORM_CLASS_PATH, searchpath);
            fileManager.setLocation(StandardLocation.CLASS_PATH, searchpath);
            fileManager.setLocation(StandardLocation.CLASS_OUTPUT, Collections.singleton(classesOutputPath));

            JavacTaskImpl task =
                    (JavacTaskImpl)
                            compiler.getTask(
                                    null,
                                    fileManager,
                                    diagnostics,
                                    // TODO(b/143213486): Remove -source 8 and figure out how to configure
                                    // SYSTEM_MODULES and MODULE_PATH to prevent searching for modules.
                                    ImmutableList.of("-source", "8"),
                                    null,
                                    fileManager.getJavaFileObjectsFromFiles(
                                            targetPathBySourcePath.keySet().stream().map(File::new).collect(toList())));

            task.call();

            if ( !diagnostics.getDiagnostics().isEmpty() ) {
                for (Diagnostic d : diagnostics.getDiagnostics())
                System.err.println(d.getMessage(Locale.getDefault()));
            }

            return;
        } catch (IOException e) {
            //problems.fatal(Problems.FatalError.valueOf(e.getMessage()));
            return;
        }
    }

    private static List<String> getPathEntries(String path) {
        List<String> entries = new ArrayList<>();
        for (String entry : Splitter.on(File.pathSeparatorChar).omitEmptyStrings().split(path)) {
            if (new File(entry).exists()) {
                entries.add(entry);
            }
        }
        return entries;
    }
}
