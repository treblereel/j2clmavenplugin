package com.vertispan.j2cl.mojo.incremental.tasks;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.j2cl.common.SourceUtils;
import com.google.javascript.jscomp.deps.ClosureBundler;
import com.vertispan.j2cl.build.task.Dependency;
import com.vertispan.j2cl.build.task.OutputTypes;
import com.vertispan.j2cl.build.task.Project;
import com.vertispan.j2cl.tools.Closure;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.vertispan.j2cl.build.provided.BundleJarTask.BUNDLE_JS;

public class BundleJarTask {

    private final TaskContext context;
    private final Project project;
    private final Runnable finish;

    public BundleJarTask(TaskContext context, Project project, Runnable finish) {
        this.context = context;
        this.project = project;
        this.finish = finish;
    }

    public void finish() {

        List<Project> jsSources = Stream
                .concat(
                        project.getDependencies().stream()
                                .filter(dependency -> ((com.vertispan.j2cl.build.Dependency) dependency).belongsToScope(Dependency.Scope.RUNTIME))
                                .map(d -> (Project) d.getProject()),
                        Stream.of(project)
                ).collect(Collectors.toUnmodifiableList());

        List<Project> buildOrder = new ArrayList<>();
        Set<String> pendingProjectKeys = jsSources.stream().map(Project::getKey).collect(Collectors.toSet());
        List<Project> remaining = jsSources.stream().sorted(Comparator.comparing(i -> i.getDependencies().size())).collect(Collectors.toList());
        while (!remaining.isEmpty()) {
            for (Iterator<Project> iterator = remaining.iterator(); iterator.hasNext(); ) {
                Project input = iterator.next();
                if (input.getDependencies().stream()
                        .filter(dependency -> ((com.vertispan.j2cl.build.Dependency) dependency).belongsToScope(Dependency.Scope.RUNTIME))
                        .noneMatch(dep -> pendingProjectKeys.contains(dep.getProject().getKey()))) {
                    iterator.remove();
                    pendingProjectKeys.remove(input.getKey());
                    buildOrder.add(input);
                }
            }
        }

        File initialScriptFile = context.config.getWebappDirectory().resolve(context.config.getInitialScriptFilename()).toFile();
        Map<String, Object> defines = new LinkedHashMap<>(context.config.getDefines());

        //copy public resources
/*        List<Input> outputToCopy = Stream.concat(
                        Stream.of(project),
                        scope(project.getDependencies(), Dependency.Scope.RUNTIME).stream()
                )
                // Only need to consider the original inputs and generated sources,
                // J2CL won't contribute this kind of sources
                .map(p -> input(p, OutputTypes.BYTECODE).filter(COPIED_OUTPUT))
                .collect(Collectors.toUnmodifiableList());*/

        File outputDir = initialScriptFile.getParentFile();
        outputDir.mkdirs();

        try {

            buildOrder.stream().map(proj -> {
                Path path = context.outputFactory.create(proj, OutputTypes.BUNDLED_JS).getOutputPath().resolve("results");
                try {
                    return Files.walk(path).filter(p -> BUNDLE_JS.matches(p))
                            .map(p -> SourceUtils.FileInfo.create(path.relativize(p).toString(), p.toFile().getAbsolutePath()))
                            .collect(Collectors.toList());
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }).flatMap(Collection::stream).forEachOrdered(bundle -> {
                Path targetFile = outputDir.toPath().resolve(bundle.sourcePath());
                // if the file is present and has the same size, skip it
                try {
                    if (Files.exists(targetFile) && Files.size(targetFile) == Files.size(Paths.get(bundle.originalPath()))) {
                        //sorry it's 4am, I'm tired
                    } else {
                        Files.copy(Paths.get(bundle.originalPath()), targetFile, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });


            File destSourcesDir = outputDir.toPath().resolve(Closure.SOURCES_DIRECTORY_NAME).toFile();
            destSourcesDir.mkdirs();


            List<String> sourceOrder = new ArrayList<>();
            try {
                for (Project project1 : buildOrder) {
                    Path path = context.outputFactory.create(project1, OutputTypes.BUNDLED_JS).getOutputPath().resolve("results");
                    Files.walk(path).filter(p -> BUNDLE_JS.matches(p))
                            .map(p -> path.relativize(p).toString()).forEach(sourceOrder::add);


                    Path sourcesDir = path.resolve(Closure.SOURCES_DIRECTORY_NAME);
                    //copy contents of sources dir
                    if (Files.exists(sourcesDir)) {
                        FileUtils.copyDirectory(sourcesDir.toFile(), destSourcesDir);
                    }
                }
            } catch (IOException ex) {
                ex.printStackTrace();
                throw new RuntimeException(ex);
            }


            try {
                Gson gson = new GsonBuilder().setPrettyPrinting().create();
                String scriptsArray = gson.toJson(sourceOrder);
                // unconditionally set this to false, so that our dependency order works, since we're always in BUNDLE now
                defines.put("goog.ENABLE_DEBUG_LOADER", false);

                // defines are global, outside the IIFE
                String defineLine = "var CLOSURE_UNCOMPILED_DEFINES = " + gson.toJson(defines) + ";\n";
                // IIFE and base url
                String intro = "(function() {" + "var src = document.currentScript.src;\n" +
                        "var lastSlash = src.lastIndexOf('/');\n" +
                        "var base = lastSlash === -1 ? '' : src.substr(0, lastSlash + 1);";

                // iterate the scripts and append, close IIFE
                String outro = ".forEach(file => {\n" +
                        "  var elt = document.createElement('script');\n" +
                        "  elt.src = base + file;\n" +
                        "  elt.type = 'text/javascript';\n" +
                        "  elt.async = false;\n" +
                        "  document.head.appendChild(elt);\n" +
                        "});" + "})();";

                // Closure bundler runtime
                StringBuilder runtime = new StringBuilder();
                new ClosureBundler().appendRuntimeTo(runtime);

                Files.write(initialScriptFile.toPath(), Arrays.asList(
                        defineLine,
                        intro,
                        scriptsArray,
                        outro,
                        runtime
                ));
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to write html import file", e);
            }

            //copy public resources
/*        for (Input input : outputToCopy) {
            for (CachedPath entry : input.getFilesAndHashes()) {
                copiedOutputPath(outputDir.toPath(), entry);
            }
        }*/
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }


        finish.run();
    }

    private List<Project> order(List<Project> projectsToBuild) {
        List<Project> ordered = new ArrayList<>(projectsToBuild.size());
        Map<String, Project> projects = projectsToBuild.stream().collect(Collectors.toMap(Project::getKey, p -> p));
        Map<String, Set<String>> adjacencyList = projects.values().stream().
                collect(Collectors.toMap(Project::getKey, p -> p.getDependencies().
                        stream()
                        .filter(d -> d.getProject().hasSourcesMapped())
                        .filter(d -> projects.containsKey(d.getProject().getKey()))
                        .map(d -> d.getProject().getKey())
                        .collect(Collectors.toSet())));
        Stack<String> stack = new Stack<>();
        stack.addAll(projects.keySet());
        Set<String> seen = new HashSet<>();
        while (!stack.isEmpty()) {
            String key = stack.peek();
            if (!seen.contains(key)) {
                seen.add(key);
            } else {
                stack.pop();
                continue;
            }
            if (adjacencyList.get(key).isEmpty()) {
                ordered.add(projects.get(key));
                stack.pop();
                adjacencyList.values().forEach(set -> set.remove(key));
            }
        }
        return ordered;
    }
}
