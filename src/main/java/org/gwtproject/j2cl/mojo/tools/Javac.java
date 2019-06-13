package org.gwtproject.j2cl.mojo.tools;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import javax.tools.JavaCompiler;
import javax.tools.JavaCompiler.CompilationTask;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

import com.google.j2cl.common.FrontendUtils;

/**
 * Runs javac. Set this up with the appropriate classpath, directory for generated sources to be written,
 * and directory for bytecode to be written, and can be requested to preCompile any .java file where the
 * dependencies are appropriately already available.
 *
 * The classesDirFile generally should be in the classpath list.
 *
 * Note that incoming sources should already be pre-processed, and while it should be safe to directly
 * j2cl the generated classes, it may be necessary to pre-process them before passing them to j2cl.
 */
public class Javac {

    List<String> javacOptions;
    JavaCompiler compiler;
    StandardJavaFileManager fileManager;

    public Javac(File generatedClassesPath, List<File> classpath, File classesDirFile, File bootstrap) throws IOException {
        javacOptions = Arrays.asList("-implicit:none", "-bootclasspath", bootstrap.toString());
        compiler = ToolProvider.getSystemJavaCompiler();
        fileManager = compiler.getStandardFileManager(null, null, null);
        fileManager.setLocation(StandardLocation.SOURCE_PATH, Collections.emptyList());
        fileManager.setLocation(StandardLocation.SOURCE_OUTPUT, Collections.singleton(generatedClassesPath));
        fileManager.setLocation(StandardLocation.CLASS_PATH, classpath);
        fileManager.setLocation(StandardLocation.CLASS_OUTPUT, Collections.singleton(classesDirFile));
    }

    public boolean compile(List<FrontendUtils.FileInfo> modifiedJavaFiles) {
        List<FrontendUtils.FileInfo> files = modifiedJavaFiles.stream()
                .filter(file -> !file.originalPath()
                        .contains("target/generated-sources/annotations")).collect(Collectors.toList());
        Iterable<? extends JavaFileObject> modifiedFileObjects = fileManager.getJavaFileObjectsFromStrings(files.stream().map(FrontendUtils.FileInfo::sourcePath).collect(Collectors.toList()));
        //TODO pass-non null for "classes" to properly kick apt?
        //TODO consider a different classpath for this tasks, so as to not interfere with everything else?
        CompilationTask task = compiler.getTask(null, fileManager, null, javacOptions, null, modifiedFileObjects);
        return task.call();
    }
}
