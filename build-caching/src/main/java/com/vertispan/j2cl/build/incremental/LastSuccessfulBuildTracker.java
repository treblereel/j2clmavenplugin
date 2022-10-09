package com.vertispan.j2cl.build.incremental;

import com.vertispan.j2cl.build.DiskCache;
import com.vertispan.j2cl.build.Project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

class LastSuccessfulBuildTracker {

    private final static String LAST_SUCCESSFUL_TASK_DIR = "lastSuccessfulTaskDir.dat";

    private final DiskCache diskCache;

    private final Map<Project, Map<String, Path>> cache = new HashMap<>();

    private final BuildMapGenerator buildMapGenerator = new BuildMapGenerator(this);

    LastSuccessfulBuildTracker(DiskCache diskCache) {
        this.diskCache = diskCache;
    }

    void setLastSuccessfulTaskDir(Path lastSuccessfulTaskDir, com.vertispan.j2cl.build.Input input) {
        //reduce the number of times we write this file, only write it if it's changed
        if(cache.containsKey(input.getProject())) {
            if(cache.get(input.getProject()).containsKey(input.getOutputType())) {
                if (cache.get(input.getProject()).get(input.getOutputType()).equals(lastSuccessfulTaskDir)) {
                    return;
                }
            }
            cache.get(input.getProject()).put(input.getOutputType(), lastSuccessfulTaskDir);
        } else {
            cache.computeIfAbsent(input.getProject(), k -> new HashMap<>()).put(input.getOutputType(), lastSuccessfulTaskDir);
        }

        // do we need to generate maps for jars ? nope
        if (input.getProject().hasSourcesMapped() && input.getOutputType().equals(com.vertispan.j2cl.build.task.OutputTypes.BYTECODE)) {
            buildMapGenerator.generate(input, lastSuccessfulTaskDir);
        }
        Path projectPath = lastSuccessfulTaskDir.getParent();
        Path lastSuccessfulTaskDirFile = projectPath.resolve(LAST_SUCCESSFUL_TASK_DIR);
        boolean fileExists = Files.exists(lastSuccessfulTaskDirFile);
        StringBuffer sb = new StringBuffer();
        if (fileExists) {
            try {
                Files.readAllLines(lastSuccessfulTaskDirFile).stream()
                        .filter(line -> !line.startsWith(input.getOutputType() + ":"))
                        .forEach(line -> sb.append(line).append(System.lineSeparator()));
            } catch (IOException e) {
                throw new RuntimeException("Failed to read " + lastSuccessfulTaskDirFile, e);
            }

        }
        sb.append(input.getOutputType()).append(":").append(lastSuccessfulTaskDir);

        byte[] strToBytes = sb.toString().getBytes();
        try {
            Files.write(projectPath.resolve(LAST_SUCCESSFUL_TASK_DIR), strToBytes, fileExists ? StandardOpenOption.TRUNCATE_EXISTING : StandardOpenOption.CREATE);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write " + projectPath.resolve(LAST_SUCCESSFUL_TASK_DIR), e);
        }
    }


    Optional<Path> getLastSuccessfulTaskDir(Project project, String outputType) {
        return readPathAsString(project, outputType).map(Paths::get);
    }

    private Optional<List<String>> readPathAsString(Project project) {
        Path path = diskCache.getCacheDir().toPath()
                .resolve(project.getKey().replaceAll(":", "-")) //TODO there must be a better way
                .resolve(LAST_SUCCESSFUL_TASK_DIR);
        try {
            return Optional.of(Files.readAllLines(path));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private Optional<String> readPathAsString(Project project, String outputType) {
        Optional<List<String>> lines = readPathAsString(project);
        if(lines.isPresent()) {
            for (String line : lines.get()) {
                if (line.startsWith(outputType + ":")) {
                    return Optional.of(line.substring(outputType.length() + 1));
                }
            }
        }
        return Optional.empty();
    }
}
