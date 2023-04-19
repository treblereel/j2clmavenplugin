package com.vertispan.j2cl.mojo.incremental.tasks;

import com.google.j2cl.common.SourceUtils;
import com.vertispan.j2cl.build.task.OutputTypes;
import com.vertispan.j2cl.build.task.Project;
import com.vertispan.j2cl.mojo.incremental.ChangeSetHolder;
import com.vertispan.j2cl.tools.J2cl;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.vertispan.j2cl.build.provided.JavacTask.JAVA_SOURCES;

public class J2clTask extends Task {

    private final File bootstrapClasspath;


    public J2clTask(TaskContext context) {
        super(context);
        this.bootstrapClasspath = context.config.getBootstrapClasspath();
    }

    @Override
    public Boolean apply(ChangeSetHolder changeSetHolder) {
        if (changeSetHolder.created.isEmpty() && changeSetHolder.modified.isEmpty()) {
            return true;
        }

        Project project = changeSetHolder.project;

        List<File> extraClasspath = context.config.getExtraClasspath();
        List<File> classpathDirs = new ArrayList<>(extraClasspath);

        project.getDependencies()
                .stream()
                .map(dependency -> context.outputFactory.create(dependency.getProject(), OutputTypes.STRIPPED_BYTECODE_HEADERS).results())
                .map(Path::toFile)
                .forEach(classpathDirs::add);

        File strippedSources = context.outputFactory.create(project, OutputTypes.STRIPPED_SOURCES).results().toFile();
        classpathDirs.add(strippedSources);

        File classOutputDir = context.outputFactory.create(project, OutputTypes.TRANSPILED_JS).results().toFile();
        Path generated = context.outputFactory.create(project, OutputTypes.BYTECODE).generated();

        try {

            J2cl j2cl = new J2cl(classpathDirs, bootstrapClasspath, classOutputDir, context.log);
            //always recompile all generated sources, since we don't know which ones changed
            Stream<SourceUtils.FileInfo> generatedJavaFiles = Files.walk(generated)
                    .filter(JAVA_SOURCES::matches)
                    .map(p -> SourceUtils.FileInfo.create(p.toFile().getAbsolutePath(), generated.relativize(p).toString()));

            List<SourceUtils.FileInfo> sources = Stream.concat(Stream.concat(
                                            changeSetHolder.created.stream(),
                                            changeSetHolder.modified.stream()
                                    ).filter(value -> JAVA_SOURCES.matches(value.relativePath))
                                    .map(p -> SourceUtils.FileInfo.create(p.absolutePath.toString(), p.relativePath.toString())),
                            generatedJavaFiles)
                    .collect(Collectors.toUnmodifiableList());


            List<SourceUtils.FileInfo> nativeSources = new ArrayList<>();
            //native sources can be in the src and in the generated folders
            for (SourceUtils.FileInfo source : sources) {
                String nativeJsSource = source.sourcePath().replace(".java", ".native.js");
                String nativeJsOriginal = source.originalPath().replace(".java", ".native.js");
                Path nativeJsSourcePath = Paths.get(nativeJsSource);

                if(Files.exists(nativeJsSourcePath)) {
                    nativeSources.add(SourceUtils.FileInfo.create(nativeJsSource, nativeJsOriginal));
                }

                String generatedNativeJs = generated.resolve(nativeJsOriginal).toFile().getAbsolutePath();
                Path generatedNativeJsPath = Paths.get(generatedNativeJs);

                if(Files.exists(generatedNativeJsPath)) {
                    nativeSources.add(SourceUtils.FileInfo.create(generatedNativeJs, nativeJsOriginal));
                }
            }

            if (!j2cl.transpile(sources, nativeSources)) {
                context.log.error("J2CL failed, see above for details");
                return false;
            }

        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }
}
