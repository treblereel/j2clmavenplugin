package com.vertispan.j2cl.build.incremental;

import com.vertispan.j2cl.build.BuildService;
import com.vertispan.j2cl.build.DiskCache;
import com.vertispan.j2cl.build.Project;
import com.vertispan.j2cl.build.WatchService;
import com.vertispan.j2cl.build.task.CachedPath;
import com.vertispan.j2cl.build.task.Input;
import com.vertispan.j2cl.build.task.OutputTypes;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Stack;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.vertispan.j2cl.build.incremental.FileHelper.JAVA;

public class IncrementalProcessor {

    private final BuildService buildService;

    private Set<Project> projects = new HashSet<>();

    private Runnable requestBuild;

    private final LastSuccessfulBuildTracker lastSuccessfulBuildTracker;

    public IncrementalProcessor(BuildService buildService, DiskCache diskCache) {
        this.buildService = buildService;
        this.lastSuccessfulBuildTracker = new LastSuccessfulBuildTracker(diskCache);
    }


    private Optional<Set<BuildMapClassDefinition>> getBuildMapClassDefinition(Project project) {
        return lastSuccessfulBuildTracker.getLastSuccessfulTaskDir(project, OutputTypes.BYTECODE)
                .map(path -> new BuildMapParser(path).parse());
    }

    private final Map<String, BuildMapClassDefinition> lastSuccessfulBuildMap = new HashMap<>();

    private final Map<String, Pair<Project, BuildMapClassDefinition>> currentBuildMap = new HashMap<>();

    private final Map<String, Set<String>> dependents = new HashMap<>();

    private final Map<Project, Map<String, Set<Path>>> changeSet = new HashMap<>();

    public void triggerChanges(Set<WatchService.ChangeSetHolder> _changeSet) {
        lastSuccessfulBuildMap.clear();
        currentBuildMap.clear();
        dependents.clear();
        changeSet.clear();

        _changeSet.forEach(changeSetHolder -> {
            Project project = changeSetHolder.project;
            buildService.triggerChanges(project, changeSetHolder.created, changeSetHolder.modified, changeSetHolder.deleted);

            this.changeSet.put(project, new HashMap<>());
            this.changeSet.get(project).put("created", new HashSet<>());
            this.changeSet.get(project).put("modified", new HashSet<>());
            this.changeSet.get(project).put("deleted", new HashSet<>());

            changeSetHolder.created.forEach((path, entry) -> {
                this.changeSet.get(project).get("created").add(path);
            });
            changeSetHolder.modified.forEach((path, entry) -> {
                this.changeSet.get(project).get("modified").add(path);
            });
            changeSetHolder.deleted.forEach(path -> {
                this.changeSet.get(project).get("deleted").add(path);
            });

            getBuildMapClassDefinition(project)
                    .ifPresent(buildMap -> buildMap.forEach(definition
                            -> lastSuccessfulBuildMap.put(definition.className, definition)));
        });

        requestBuild.run();

    }

    public boolean beforeCompile(com.vertispan.j2cl.build.task.Project project, Input ownJavaSources, List<Input> ownNativeJsSources,
                                 Consumer<Result> consumer, Path outputPath, List<File> classpathDirs) {
        // it's very first run, there are no lastSuccessfulBuildMap
        if (lastSuccessfulBuildMap.isEmpty()) {
            return true;
        }

        // create a map of all the classes that are being compiled
        // all bytecode tasks must be finished before this is called
        processCurrentBuildMap();

        // TODO by default, j2cl-m-p processes all modules, even if they are not changed
        // so we simple copy existing files from lastSuccessfulBuildMap
        if (!changeSet.containsKey(project)) {
            copyAndDeleteFiles((Project) project, outputPath);
            return false;
        }

        // merge changeSet with transient changes
        processChangeSet(project);

        Set<String> combinedChangeSet = changeSet.get(project).get("created").stream().map(Path::toString).collect(Collectors.toSet());
        changeSet.get(project).get("modified").stream().map(Path::toString).forEach(combinedChangeSet::add);

        Path bytecodePath = lastSuccessfulBuildTracker.getLastSuccessfulTaskDir((Project) project, OutputTypes.BYTECODE)
                .orElseThrow(() -> new RuntimeException("No last successful BYTECODE dir found for " + project)).resolve("results");

        Path strippedSources = lastSuccessfulBuildTracker.getLastSuccessfulTaskDir((Project) project, OutputTypes.STRIPPED_SOURCES)
                .orElseThrow(() -> new RuntimeException("No last successful STRIPPED_SOURCES dir found for " + project)).resolve("results");

        classpathDirs.add(bytecodePath.toFile());

        copyAndDeleteFiles((Project) project, outputPath);

        Set<Path> sources = ownJavaSources.getFilesAndHashes().stream()
                .filter(file -> combinedChangeSet.contains(file.getSourcePath().toString()))
                .map(CachedPath::getAbsolutePath)
                .filter(p -> p.getFileName().toString().endsWith(JAVA))
                .collect(Collectors.toSet());

        Set<Path> natives = ownNativeJsSources.stream().flatMap(i ->
                        i.getFilesAndHashes()
                                .stream())
                .filter(file -> combinedChangeSet.contains(file.getSourcePath().toString().replace(".native.js", ".java")))
                .map(CachedPath::getAbsolutePath)
                .collect(Collectors.toSet());
        consumer.accept(new Result(new Pair<>(strippedSources, sources), new Pair<>(bytecodePath, natives)));
        return false;
    }

    private void processChangeSet(com.vertispan.j2cl.build.task.Project project) {
        Set<String> changed = new HashSet<>();
        getBuildMapClassDefinition((Project) project)
                .ifPresent(buildMap -> buildMap.stream().filter(definition -> currentBuildMap.get(definition.className).v.propagated)
                        .forEach(definition -> changed.add(definition.className)));
        for (String s : changed) {
            String clazz = s.replaceAll("\\.", "/");
            if (clazz.contains("$")) {
                clazz = clazz.substring(0, clazz.indexOf("$"));
            }
            changeSet.get(project).get("modified").add(Paths.get(clazz + ".java"));
        }
    }

    private void processCurrentBuildMap() {
        if (currentBuildMap.isEmpty()) {
            Set<String> changed = new HashSet<>();
            for (Project _project : projects) {
                getBuildMapClassDefinition(_project)
                        .ifPresent(buildMap -> buildMap.forEach(definition -> {
                            for (String dep : definition.dependsOn) {
                                dependents.computeIfAbsent(dep, k -> new HashSet<>()).add(definition.className);
                            }
                            boolean isChanged = !lastSuccessfulBuildMap.containsKey(definition.className) ? true
                                    : !definition.hash.equals(lastSuccessfulBuildMap.get(definition.className).hash);
                            if (isChanged) {
                                changed.add(definition.className);
                            }
                            currentBuildMap.put(definition.className,
                                    new Pair<>(_project, definition.setPropagated(isChanged)));
                        }));
            }

            Set<String> visited = new HashSet<>();
            Stack<String> stack = new Stack<>();
            stack.addAll(changed);
            while (!stack.isEmpty()) {
                String className = stack.pop();
                if (!visited.contains(className)) {
                    visited.add(className);
                    if (dependents.containsKey(className)) {
                        Set<String> dependentClasses = dependents.get(className);
                        if (currentBuildMap.get(className).v.propagated) {
                            changed.addAll(dependentClasses);
                        }
                    }
                }
            }
            changed.forEach(className -> currentBuildMap.get(className).v.propagated = true);
        }
    }

    private void copyAndDeleteFiles(Project project, Path outputPath) {
        lastSuccessfulBuildTracker.getLastSuccessfulTaskDir(project, OutputTypes.TRANSPILED_JS).ifPresent(lastPath -> {
            Path input = lastPath.resolve("results");
            FileHelper manager = new FileHelper(outputPath);
            manager.copyFolder(input);
            Set<Path> deleteFiles = new HashSet<>();
            if(this.changeSet.containsKey(project)) {
                deleteFiles.addAll(this.changeSet.get(project).get("deleted"));
                deleteFiles.addAll(this.changeSet.get(project).get("modified"));
            }
            if(!deleteFiles.isEmpty()) {
                manager.deleteFiles(deleteFiles);
            }
        });
    }

    public void setBuildQueue(Runnable runnable) {
        this.requestBuild = runnable;
    }

    public void setLastSuccessfulTaskDir(Path taskDir, com.vertispan.j2cl.build.Input input) {
        this.lastSuccessfulBuildTracker.setLastSuccessfulTaskDir(taskDir, input);
    }

    public void setProjects(Map<Project, List<Path>> sourcePathsToWatch) {
        sourcePathsToWatch.forEach((project, paths) -> {
            projects.add(project);
        });
    }

    //TODO
    public static class Pair<K, V> {
        public final K k;
        public final V v;

        public Pair(K k, V v) {
            this.k = k;
            this.v = v;
        }
    }

    public static class Result {

        public final Pair<Path, Set<Path>> sources;
        public final Pair<Path, Set<Path>> natives;

        private Result(Pair<Path, Set<Path>> sources, Pair<Path, Set<Path>> natives) {
            this.sources = sources;
            this.natives = natives;
        }
    }
}
