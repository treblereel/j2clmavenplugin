package com.vertispan.j2cl.mojo.incremental.tasks;

import com.google.common.collect.ImmutableList;
import com.google.turbine.diag.TurbineError;
import com.google.turbine.main.Main;
import com.google.turbine.options.TurbineOptions;
import com.vertispan.j2cl.build.task.OutputTypes;
import com.vertispan.j2cl.build.task.Project;
import com.vertispan.j2cl.mojo.incremental.ChangeSetHolder;
import com.vertispan.j2cl.mojo.incremental.Output;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Stack;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.vertispan.j2cl.build.provided.JavacTask.JAVA_SOURCES;
import static com.vertispan.j2cl.build.provided.TurbineTask.extractJar;

public class TurbineTask extends Task {

    public TurbineTask(TaskContext context) {
        super(context);
    }

    @Override
    public void accept(ChangeSetHolder changeSetHolder) {
        Project project = changeSetHolder.project;
        if (context.application.equals(project)) {
            return;
        }
        List<File> extraClasspath = context.config.getExtraClasspath();
        Path strippedSources = context.outputFactory.create(project, OutputTypes.STRIPPED_SOURCES).results();
        Output strippedBytecodeHeaders = context.outputFactory.create(project, OutputTypes.STRIPPED_BYTECODE_HEADERS);
        Path results = strippedBytecodeHeaders.results();
        delete(results);
        List<String> deps = Stream.concat(project.getDependencies()
                        .stream()
                        .map(dependency -> context.outputFactory.create(dependency.getProject(), OutputTypes.STRIPPED_BYTECODE_HEADERS)
                                .results()
                                .resolve("output.jar")).
                        map(Path::toString),
                extraClasspath.stream().map(File::toString)
        ).collect(Collectors.toUnmodifiableList());

        List<String> sources = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(strippedSources)) {
            walk.filter(file -> JAVA_SOURCES.matches(file)).forEach(file -> sources.add(file.toString()));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        File output = results.resolve("output.jar").toFile();
        try {
            long start = System.currentTimeMillis();
            Main.Result result = Main.compile(
                    TurbineOptions.builder()
                            .setSources(ImmutableList.copyOf(sources))
                            .setOutput(output.toString())
                            .setClassPath(ImmutableList.copyOf(deps))
                            //TODO https://github.com/Vertispan/j2clmavenplugin/issues/181
                            //.setReducedClasspathMode(TurbineOptions.ReducedClasspathMode.JAVABUILDER_REDUCED)
                            .build());

            context.log.debug("turbine finished: " + result + " in " + (System.currentTimeMillis() - start) + "ms");
            extractJar(output, results, context.log);
        } catch (TurbineError e) {
            // usually it means, it's an apt that can't be processed, log it
            context.log.info(e.getMessage());
        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
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
