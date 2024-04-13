package com.vertispan.j2cl.build.provided;

import com.google.auto.service.AutoService;
import com.google.j2cl.common.SourceUtils;
import com.vertispan.j2cl.build.task.CachedPath;
import com.vertispan.j2cl.build.task.Config;
import com.vertispan.j2cl.build.task.Input;
import com.vertispan.j2cl.build.task.OutputTypes;
import com.vertispan.j2cl.build.task.Project;
import com.vertispan.j2cl.build.task.TaskFactory;
import com.vertispan.j2cl.tools.Jdt;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@AutoService(TaskFactory.class)
public class JdtBytecodeTask extends BytecodeTask {

    @Override
    public String getTaskName() {
        return "default";
        //return "jdt";
    }

    @Override
    public Task resolve(Project project, Config config) {
        if (!project.hasSourcesMapped()) {
            // instead, copy the bytecode+resources out of the jar so it can be used by downstream bytecode/apt tasks
            Input existingUnpackedBytecode = input(project, OutputTypes.INPUT_SOURCES);
            return context -> {
                for (CachedPath entry : existingUnpackedBytecode.getFilesAndHashes()) {
                    Path outputFile = context.outputPath().resolve(entry.getSourcePath());
                    Files.createDirectories(outputFile.getParent());
                    Files.copy(entry.getAbsolutePath(), outputFile);
                }
            };
        }

        // TODO just use one input for both of these
        // track the dirs (with all file changes) so that APT can see things it wants
        Input inputDirs = input(project, OutputTypes.INPUT_SOURCES);
        // track just java files (so we can just compile them)
        Input inputSources = input(project, OutputTypes.INPUT_SOURCES).filter(JAVA_SOURCES);
        // track resources so they are available to downstream processors on the classpath, as they would
        // be if we had built a jar
        Input resources = input(project, OutputTypes.INPUT_SOURCES).filter(NOT_BYTECODE);


        scope(project.getDependencies()
                        .stream()
                        .filter(dependency -> dependency.getProject().getProcessors().isEmpty()).collect(Collectors.toSet()),
                com.vertispan.j2cl.build.task.Dependency.Scope.COMPILE)
                .stream()
                //.filter(dependency -> !dependency.isJsZip())
                .forEach(dependency -> {
                    //System.out.println("Dependency: " + dependency.getKey());
                });

        List<Input> bytecodeClasspath = scope(project.getDependencies()
                        .stream()
                        .filter(dependency -> dependency.getProject().getProcessors().isEmpty()).collect(Collectors.toSet()),
                com.vertispan.j2cl.build.task.Dependency.Scope.COMPILE)
                .stream()
                //.filter(dependency -> !dependency.isJsZip())
                .filter(dependency -> !dependency.getKey().toString().equals("com.vertispan.j2cl:jre:v20230718-1:jszip"))
                .map(inputs(OutputTypes.BYTECODE))
                .collect(Collectors.toUnmodifiableList());

        List<Input> inReactorProcessors = scope(project.getDependencies().stream().filter(dependency -> dependency.getProject().hasSourcesMapped()
                        && !dependency.getProject().isJsZip()).collect(Collectors.toSet()),
                com.vertispan.j2cl.build.task.Dependency.Scope.COMPILE)
                .stream()
                .map(inputs(OutputTypes.BYTECODE))
                .map(input -> input.filter(APT_PROCESSOR))
                .collect(Collectors.toUnmodifiableList());

        System.out.println("processors in " + project.getKey() + " " + inReactorProcessors.size());

        inReactorProcessors.forEach(p -> {
            System.out.println("Processor: " + p.getProject().getKey());
        });

        //File bootstrapClasspath = config.getBootstrapClasspath();
        List<File> extraClasspath = new ArrayList<>(config.getExtraClasspath());
        Set<String> processors = new HashSet<>();
        project.getDependencies()
                .stream()
                .map(d -> d.getProject())
                .filter(p -> !p.getProcessors().isEmpty())
                .forEach(p -> {
                    processors.addAll(p.getProcessors());
                    extraClasspath.add(p.getJar());
                });

        return context -> {
                        /* we don't know if reactor dependency project is apt, before it's compiled, so we need to check it on the fly
               1) there are no processors in the project, so we pass empty set to javac, nothing happens
               2) there are only reactor processors, so we pass empty set to javac, they will be triggered by javac
               3) thee are both reactor and nonreactor processors, so we pass both to javac via set
               4) there are only nonreactor processors, we pass them to javac via set
             */

            System.out.println("CONTEXT: " + project.getKey() + " " + inReactorProcessors.size());

            Set<String> aptProcessors = maybeAddInReactorAptProcessor(inReactorProcessors, processors);

            aptProcessors.forEach(p -> {
                System.out.println("APT Processor: " + p);
            });



            if (!inputSources.getFilesAndHashes().isEmpty()) {
                // At least one .java file in sources, compile it (otherwise skip this and just copy resource)

                List<File> classpathDirs = Stream.concat(
                                bytecodeClasspath.stream().map(Input::getParentPaths)
                                        .flatMap(Collection::stream)
                                        .map(path -> {
                                            if (path.toString().contains("com.vertispan.j2cljre")) {
                                                return path.resolve("bazelbin/jre/java/jre.js/javaemul");
                                            }
                                            return path;
                                        })
                                        .map(Path::toFile),
                                extraClasspath.stream().filter(f -> !f.getName().equals("jre-v20230718-1.jar"))
                        )


                        .collect(Collectors.toUnmodifiableList());


                List<File> sourcePaths = inputDirs.getParentPaths().stream().map(Path::toFile).collect(Collectors.toUnmodifiableList());
                File generatedClassesDir = getGeneratedClassesDir(context);
                File classOutputDir = context.outputPath().toFile();

                System.out.println("started  : " + project.getKey());
                Jdt javac = new Jdt(context, generatedClassesDir, sourcePaths, classpathDirs, classOutputDir, aptProcessors);
                System.out.println("finished : " + project.getKey());


                // TODO convention for mapping to original file paths, provide FileInfo out of Inputs instead of Paths,
                //      automatically relativized?
                List<SourceUtils.FileInfo> sources = inputSources.getFilesAndHashes()
                        .stream()
                        .filter(p -> !p.getAbsolutePath().toFile().getName().equals("module-info.java"))
                        .map(p -> SourceUtils.FileInfo.create(p.getAbsolutePath().toString(), p.getSourcePath().toString()))
                        .collect(Collectors.toUnmodifiableList());

                try {
                    if (!javac.compile(sources)) {
                        throw new RuntimeException("Failed to complete bytecode task, check log");
                    }
                } catch (Exception exception) {
                    exception.printStackTrace();
                    throw exception;
                }
            }

            // Copy all resources, even .java files, so that this output is the source of truth as if this
            // were freshly unpacked from a jar
            for (CachedPath entry : resources.getFilesAndHashes()) {
                Files.createDirectories(context.outputPath().resolve(entry.getSourcePath()).getParent());
                Files.copy(entry.getAbsolutePath(), context.outputPath().resolve(entry.getSourcePath()));
            }

        };
    }

}
