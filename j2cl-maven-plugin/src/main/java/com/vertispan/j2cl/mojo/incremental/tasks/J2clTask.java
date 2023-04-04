package com.vertispan.j2cl.mojo.incremental.tasks;

import com.google.j2cl.common.SourceUtils;
import com.vertispan.j2cl.build.WatchService;
import com.vertispan.j2cl.build.task.Input;
import com.vertispan.j2cl.build.task.OutputTypes;
import com.vertispan.j2cl.build.task.Project;
import com.vertispan.j2cl.tools.J2cl;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.vertispan.j2cl.build.provided.J2clTask.*;
import static com.vertispan.j2cl.build.provided.JavacTask.JAVA_SOURCES;

public class J2clTask extends Task {

    private final File bootstrapClasspath;


    public J2clTask(TaskContext context) {
        super(context);
        this.bootstrapClasspath = context.config.getBootstrapClasspath();
    }

    @Override
    public void accept(WatchService.ChangeSetHolder changeSetHolder) {
        if(changeSetHolder.created.isEmpty() && changeSetHolder.modified.isEmpty()) {
            return;
        }

        Project project = changeSetHolder.project;

        List<File> extraClasspath = context.config.getExtraClasspath();
        List<File> classpathDirs = new ArrayList<>();
        classpathDirs.addAll(extraClasspath);

        project.getDependencies()
                .stream()
                .map(dependency -> context.outputFactory.create(dependency.getProject(), OutputTypes.STRIPPED_BYTECODE_HEADERS).results())
                .map(Path::toFile)
                .forEach(classpathDirs::add);

        File classOutputDir = context.outputFactory.create(project, OutputTypes.TRANSPILED_JS).results().toFile();
        Path generated = context.outputFactory.create(project, OutputTypes.BYTECODE).generated();

        try {

            J2cl j2cl = new J2cl(classpathDirs, bootstrapClasspath, classOutputDir, context.log);
            //always recompile all generated sources, since we don't know which ones changed
            Stream<SourceUtils.FileInfo> generatedJavaFiles = Files.walk(generated)
                    .filter(JAVA_SOURCES::matches)
                    .map(p -> SourceUtils.FileInfo.create(p.toFile().getAbsolutePath(), generated.relativize(p).toString()));

            Stream<SourceUtils.FileInfo> generatedNativeJsFiles = Files.walk(generated)
                    .filter(NATIVE_JS_SOURCES::matches)
                    .map(p -> SourceUtils.FileInfo.create(p.toFile().getAbsolutePath(), generated.relativize(p).toString()));

            List<SourceUtils.FileInfo> sources = Stream.concat(Stream.concat(
                            changeSetHolder.created.values().stream(),
                            changeSetHolder.modified.values().stream()
                    ).filter(value -> JAVA_SOURCES.matches(value.getSourcePath()))
                    .map(p -> SourceUtils.FileInfo.create(p.getAbsolutePath().toString(), p.getSourcePath().toString())),
                    generatedJavaFiles)
                    .collect(Collectors.toUnmodifiableList());

            List<SourceUtils.FileInfo> nativeSources = Stream.concat(Stream.concat(
                                            changeSetHolder.created.values().stream(),
                                            changeSetHolder.modified.values().stream()
                                    ).filter(value -> NATIVE_JS_SOURCES.matches(value.getSourcePath()))
                                    .map(p -> SourceUtils.FileInfo.create(p.getAbsolutePath().toString(), p.getSourcePath().toString())),
                            generatedNativeJsFiles)
                    .collect(Collectors.toUnmodifiableList());

            if (!j2cl.transpile(sources, nativeSources)) {
                throw new IllegalStateException("Error while running J2CL");
            }

        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }

    }
}
