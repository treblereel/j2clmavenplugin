package com.vertispan.j2cl.mojo.incremental;

import com.vertispan.j2cl.build.BuildService;
import com.vertispan.j2cl.build.TaskOutput;
import com.vertispan.j2cl.build.task.Project;

import java.nio.file.Path;
import java.util.Map;

public class Output {

    private final Path outputPath;

    private Output(Path outputPath) {
        this.outputPath = outputPath;
    }

    public Path getOutputPath() {
        return outputPath;
    }

    public Path results(){
        return outputPath.resolve("results");
    }

    public static class OutputFactory {

        private final Map<com.vertispan.j2cl.build.Project, Map<String, Path>> lastSuccessfulOutputs;

        OutputFactory(BuildService buildService) {
            this.lastSuccessfulOutputs = buildService.getDiskCache().getLastSuccessfulOutputs();
        }

        public Output get(Project project, String outputTypes) {
            return new Output(lastSuccessfulOutputs.get(project).get(outputTypes));
        }
    }


}
