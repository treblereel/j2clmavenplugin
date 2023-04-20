package com.vertispan.j2cl.mojo.incremental.tasks;

import com.google.j2cl.common.SourceUtils;
import com.vertispan.j2cl.build.WatchService;
import com.vertispan.j2cl.build.task.Dependency;
import com.vertispan.j2cl.build.task.OutputTypes;
import com.vertispan.j2cl.build.task.Project;
import com.vertispan.j2cl.mojo.incremental.ChangeSetHolder;
import com.vertispan.j2cl.tools.Javac;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Stack;
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
        boolean runRootProject = context.alwaysRunMainProject && changeSetHolder.project.equals(context.application);
        //always run main project, even if nothing changed, it may has apt processors that need to run
        if (changeSetHolder.created.isEmpty() && changeSetHolder.modified.isEmpty() && !runRootProject) {
            System.out.println("Nothing to compile, skipping");
            return true;
        }

        Project project = changeSetHolder.project;

        ((com.vertispan.j2cl.build.Project) project).getSourceRoots().stream().forEach(System.out::println);


        Path classOutputDir = context.outputFactory.create(project, OutputTypes.BYTECODE).results();
        Path generatedClassesDir = context.outputFactory.create(project, OutputTypes.BYTECODE).generated();

        delete(classOutputDir);
        delete(generatedClassesDir);

        List<File> classpathDirs = Stream.concat(
                        project.getDependencies()
                                .stream()
                                .map(dep -> context.outputFactory.create(dep.getProject(), OutputTypes.BYTECODE).results())
                                .map(Path::toFile),
                        context.config.getExtraClasspath()
                                .stream())
                .collect(Collectors.toUnmodifiableList());

        List<Path> sourcePaths = ((com.vertispan.j2cl.build.Project) project).getSourceRoots()
                .stream()
                .map(Paths::get)
                .collect(Collectors.toUnmodifiableList());

        List<SourceUtils.FileInfo> sources =
                sourcePaths.stream().map(path -> {
                            try {
                                return Files.walk(path)
                                        .filter(JAVA_SOURCES::matches)
                                        .map(p -> SourceUtils.FileInfo.create(p.toFile().getAbsolutePath(), path.relativize(p).toString()));
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        })
                        .flatMap(s -> s)
                        .collect(Collectors.toUnmodifiableList());


        try {
            Javac javac = new Javac(context.log, generatedClassesDir.toFile(), sourcePaths.stream().map(Path::toFile).collect(Collectors.toUnmodifiableList()), classpathDirs, classOutputDir.toFile(), bootstrapClasspath);

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

    private void delete(Path output) {
        Stack<File> stack = new Stack<>();
        stack.push(output.toFile());
        while (!stack.isEmpty()) {
            File file = stack.peek();
            if (file.isDirectory()) {
                File[] files = file.listFiles();
                if (files.length == 0) {
                    stack.pop();
                    file.delete();
                } else {
                    for (File subFile : files) {
                        stack.push(subFile);
                    }
                }
            } else {
                stack.pop();
                file.delete();
            }
        }
        output.toFile().mkdirs();
    }
}
