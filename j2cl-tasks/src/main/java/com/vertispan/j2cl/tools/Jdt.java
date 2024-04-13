package com.vertispan.j2cl.tools;

import com.google.j2cl.common.SourceUtils;
import com.vertispan.j2cl.build.task.BuildLog;
import org.eclipse.jdt.core.compiler.batch.BatchCompiler;
import org.eclipse.jdt.internal.compiler.DefaultErrorHandlingPolicies;
import org.eclipse.jdt.internal.compiler.batch.Main;

import javax.lang.model.SourceVersion;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class Jdt {

    private final BuildLog log;
    private final List<String> javacOptions;

    public Jdt(BuildLog log, File generatedClassesPath, List<File> sourcePaths, List<File> classpath, File classesDirFile, Set<String> processors) {
        this.log = log;
        this.javacOptions = new ArrayList<>(Arrays.asList("-encoding", "utf8"));
        //this.javacOptions = new ArrayList<>();



        if (generatedClassesPath == null) {
            javacOptions.add("-proc:none");
        }
        if (SourceVersion.latestSupported().compareTo(SourceVersion.RELEASE_11) >= 0) {
            //none
        }
        javacOptions.add("-11");

        if (!processors.isEmpty()) {
            javacOptions.add("-processor");
            javacOptions.add(String.join(",", processors));
            System.out.println("processors: " + String.join(",", processors));

        } else {
            System.out.println("No processors");
            javacOptions.add("-proc:none");
        }

        javacOptions.add("-sourcepath");
        javacOptions.add(sourcePaths.stream().map(File::getAbsolutePath).collect(Collectors.joining(":")));

        System.out.println("sourcepath: " + sourcePaths.stream().map(File::getAbsolutePath).collect(Collectors.joining(":")));

        javacOptions.add("-classpath");

        System.out.println("classpath: ");
        classpath.stream().map(File::getAbsolutePath).forEach(e -> {
            //System.out.println(e);
        });

        javacOptions.add(classpath.stream().map(File::getAbsolutePath).collect(Collectors.joining(":")));

        javacOptions.add("-d"); // -d <dir>           destination directory
        javacOptions.add(classesDirFile.getAbsolutePath());
        javacOptions.add("-s"); //-s <dir>             destination directory for generated source files
        javacOptions.add(generatedClassesPath.getAbsolutePath());
        javacOptions.add("-time");
        javacOptions.add("-XprintProcessorInfo");
        javacOptions.add("-XprintRounds");

        System.out.println("-D " + classesDirFile.getAbsolutePath());
        System.out.println("-S " + generatedClassesPath.getAbsolutePath());

    }

    public boolean compile(List<SourceUtils.FileInfo> modifiedJavaFiles) {
        modifiedJavaFiles.forEach(f -> javacOptions.add(f.sourcePath()));

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ByteArrayOutputStream errorOutputStream = new ByteArrayOutputStream();

        boolean result = BatchCompiler.compile(javacOptions.toArray(new String[javacOptions.size()]), new PrintWriter(outputStream), new PrintWriter(errorOutputStream), null);

        log.info(outputStream.toString(Charset.defaultCharset()));
        log.error(errorOutputStream.toString(Charset.defaultCharset()));
        return result;
    }
}
