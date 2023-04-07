package com.vertispan.j2cl.mojo.incremental.tasks;

import com.google.j2cl.common.SourceUtils;
import com.google.javascript.jscomp.CompilationLevel;
import com.google.javascript.jscomp.CompilerOptions;
import com.google.javascript.jscomp.DependencyOptions;
import com.vertispan.j2cl.build.WatchService;
import com.vertispan.j2cl.build.provided.ClosureTask;
import com.vertispan.j2cl.build.task.OutputTypes;
import com.vertispan.j2cl.build.task.Project;
import com.vertispan.j2cl.mojo.incremental.ChangeSetHolder;
import com.vertispan.j2cl.tools.Closure;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.SuffixFileFilter;
import org.apache.commons.lang3.tuple.Pair;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.vertispan.j2cl.build.provided.ClosureBundleTask.BUNDLE_JS_EXTENSION;

public class ClosureBundleTask extends Task {

    public ClosureBundleTask(TaskContext context) {
        super(context);
    }

    @Override
    public void accept(ChangeSetHolder changeSetHolder) {
        Project project = changeSetHolder.project;

        Path transpiledJs = context.outputFactory.create(project, OutputTypes.TRANSPILED_JS).results();
        Path bytecode = context.outputFactory.create(project, OutputTypes.BYTECODE).results();
        Path bytecodeGenerated = context.outputFactory.create(project, OutputTypes.BYTECODE).generated();
        File closureOutputDir = context.outputFactory.create(project, OutputTypes.BUNDLED_JS).results().toFile();


        try {
            //TODO dubs with ClosureTask param map
            Map<Path, SourceUtils.FileInfo> js = Stream.of(Files.walk(transpiledJs)
                                    .filter(ClosureTask.PLAIN_JS_SOURCES::matches)
                                    .map(p -> Pair.of(transpiledJs ,SourceUtils.FileInfo.create(p.toAbsolutePath().toString(), transpiledJs.relativize(p).toString()))),
                            Files.walk(bytecode)
                                    .filter(ClosureTask.PLAIN_JS_SOURCES::matches)
                                    .map(p -> Pair.of(bytecode, SourceUtils.FileInfo.create(p.toAbsolutePath().toString(), bytecode.relativize(p).toString()))),
                            Files.walk(bytecodeGenerated)
                                    .filter(ClosureTask.PLAIN_JS_SOURCES::matches)
                                    .map(p -> Pair.of(bytecodeGenerated,SourceUtils.FileInfo.create(p.toAbsolutePath().toString(), bytecodeGenerated.relativize(p).toString()))))
                    .flatMap(s -> s)
                    .collect(HashMap::new, (m, p) -> m.put(p.getKey(), p.getValue()), HashMap::putAll);

            String fileNameKey = project.getKey().replaceAll("[^\\-_a-zA-Z0-9.]", "-");
            String outputFile = closureOutputDir + "/" + fileNameKey + ".js";

            Path outputFilePath = Paths.get(outputFile);
            if (js.isEmpty()) {
                // if there are no js sources, write an empty file and exit
                Files.createFile(outputFilePath);
                return;// nothing to do
            }

            Closure closureCompiler = new Closure(context.log);

            // copy the sources locally so that we can create usable sourcemaps
            //TODO consider a soft link
            File sources = new File(closureOutputDir, Closure.SOURCES_DIRECTORY_NAME);
            for (Path path : js.keySet()) {
                FileUtils.copyDirectory(path.toFile(), sources);
            }

            // create the JS bundle, only ordering these files
            boolean success = closureCompiler.compile(
                    CompilationLevel.BUNDLE,
                    DependencyOptions.DependencyMode.SORT_ONLY,
                    CompilerOptions.LanguageMode.NO_TRANSPILE,
                    Collections.singletonMap(
                            sources.getAbsolutePath(),
                            Stream.of(Files.walk(transpiledJs)
                                                    .filter(ClosureTask.PLAIN_JS_SOURCES::matches)
                                                    .map(p -> transpiledJs.relativize(p).toString()),
                                            Files.walk(bytecode)
                                                    .filter(ClosureTask.PLAIN_JS_SOURCES::matches)
                                                    .map(p -> bytecode.relativize(p).toString()),
                                            Files.walk(bytecodeGenerated)
                                                    .filter(ClosureTask.PLAIN_JS_SOURCES::matches)
                                                    .map(p -> bytecodeGenerated.relativize(p).toString()))
                                    .flatMap(s -> s)
                                    .collect(Collectors.toUnmodifiableList())),
                    sources,
                    Collections.emptyList(),
                    Collections.emptyMap(),
                    Collections.emptyList(),//TODO actually pass these in when we can restrict and cache them sanely
                    Optional.empty(),
                    true,//TODO have this be passed in,
                    true,//default to true, will have no effect anyway
                    false,
                    false,
                    "CUSTOM", // doesn't matter, bundle won't check this
                    outputFile
            );

            if (!success) {
                throw new IllegalStateException("Closure Compiler failed, check log for details");
            }

            for (String old : closureOutputDir.list(new SuffixFileFilter(BUNDLE_JS_EXTENSION))) {
                closureOutputDir.toPath().resolve(old).toFile().delete();
            }
            Files.move(outputFilePath, outputFilePath.resolveSibling(fileNameKey + BUNDLE_JS_EXTENSION));
        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }


    }
}
