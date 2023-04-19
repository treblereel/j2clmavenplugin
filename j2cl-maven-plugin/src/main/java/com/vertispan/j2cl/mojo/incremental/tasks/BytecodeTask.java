package com.vertispan.j2cl.mojo.incremental.tasks;

import com.google.j2cl.common.SourceUtils;
import com.vertispan.j2cl.build.WatchService;
import com.vertispan.j2cl.build.task.OutputTypes;
import com.vertispan.j2cl.build.task.Project;
import com.vertispan.j2cl.mojo.incremental.ChangeSetHolder;
import com.vertispan.j2cl.tools.Javac;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.vertispan.j2cl.build.provided.JavacTask.JAVA_SOURCES;

public class BytecodeTask extends Task {

   private final File bootstrapClasspath;

    public BytecodeTask(TaskContext context) {
        super(context);
        bootstrapClasspath = context.config.getBootstrapClasspath();
    }

    @Override
    public Boolean apply(ChangeSetHolder changeSetHolder) {
        if(changeSetHolder.created.isEmpty() && changeSetHolder.modified.isEmpty()) {
            return true;
        }

        Project project = changeSetHolder.project;

        List<SourceUtils.FileInfo> sources = Stream.concat(
                        changeSetHolder.created.stream(),
                        changeSetHolder.modified.stream()
                ).filter(value -> JAVA_SOURCES.matches(value.absolutePath))
                .map(p -> SourceUtils.FileInfo.create(p.absolutePath.toString(), p.relativePath.toString()))
                .collect(Collectors.toUnmodifiableList());

        File classOutputDir = context.outputFactory.create(project, OutputTypes.BYTECODE).results().toFile();
        File generatedClassesDir = context.outputFactory.create(project, OutputTypes.BYTECODE).generated().toFile();

        List<File> classpathDirs = Stream.concat(
                project.getDependencies()
                        .stream()
                        .map(dep -> context.outputFactory.create(dep.getProject(), OutputTypes.BYTECODE).results())
                        .map(Path::toFile),
                context.config.getExtraClasspath()
                        .stream())
                .collect(Collectors.toUnmodifiableList());

        List<File> sourcePaths = ((com.vertispan.j2cl.build.Project) project).getSourceRoots()
                .stream()
                .map(File::new)
                .collect(Collectors.toUnmodifiableList());

        try {
            Javac javac = new Javac(context.log, generatedClassesDir, sourcePaths, classpathDirs, classOutputDir, bootstrapClasspath);

            if (!javac.compile(sources)) {
                throw new RuntimeException("Failed to complete bytecode task, check log");
            }
        } catch (IOException exception) {
            exception.printStackTrace();
            return false;
        } catch (Exception exception) {
            exception.printStackTrace();
            return false;
        }

        return true;
    }
}
