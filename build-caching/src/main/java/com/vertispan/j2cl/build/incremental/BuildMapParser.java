package com.vertispan.j2cl.build.incremental;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

class BuildMapParser {

    private final Path path;

    BuildMapParser(Path path) {
        this.path = path;
    }

    Set<BuildMapClassDefinition> parse() {
        Set<BuildMapClassDefinition> result = new HashSet<>();

        List<String> lines = readLines();

        for (String line : lines) {
            String[] parts = line.split("\\s");
            String className = parts[0];
            String hash = parts[1];
            BuildMapClassDefinition classDefinition = new BuildMapClassDefinition(className, hash);

            if(parts.length > 2) {
                for (int i = 2; i < parts.length; i++) {
                    classDefinition.dependsOn.add(parts[i]);
                }
            }
            result.add(classDefinition);
        }
        return result;
    }

    private List<String> readLines() {
        try {
            return Files.readAllLines(path.resolve("buildMap.dat"));
        } catch (IOException e) {
            throw new RuntimeException("Failed to read " + path, e);
        }
    }
}
