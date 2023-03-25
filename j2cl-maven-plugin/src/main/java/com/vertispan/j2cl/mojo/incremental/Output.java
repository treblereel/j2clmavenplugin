package com.vertispan.j2cl.mojo.incremental;

import com.vertispan.j2cl.build.task.Project;

import java.nio.file.Path;
import java.util.stream.Stream;

public class Output {

    private final Project project;
    private final String outputTypes;
    private final Path cacheDir;

    private final Path outputPath;

    private Output(Path cacheDir, Project project, String outputTypes) {
        this.cacheDir = cacheDir;
        this.project = project;
        this.outputTypes = outputTypes;
        this.outputPath = cacheDir.resolve(project.getKey().replaceAll("[^\\-_a-zA-Z0-9.]", "-")).resolve(outputTypes);
    }

    public Path getOutputPath() {
        return outputPath;
    }

    public Path results(){
        return outputPath.resolve("results");
    }

    public Path generated(){
        return outputPath.resolve("generated");
    }

    public Stream<Path> combined() {
        return Stream.of(generated(), results());
    }

    public static class OutputFactory {

        private final Path cacheDir;

        OutputFactory(Path cacheDir) {
            this.cacheDir = cacheDir;
        }

        public Output create(Project project, String outputTypes) {
            return new Output(cacheDir, project, outputTypes);
        }
    }


}
