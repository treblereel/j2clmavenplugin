package com.vertispan.j2cl.mojo.incremental.tasks;

import com.google.j2cl.common.SourceUtils;
import com.vertispan.j2cl.build.WatchService;
import com.vertispan.j2cl.build.task.OutputTypes;
import com.vertispan.j2cl.tools.GwtIncompatiblePreprocessor;

import java.io.File;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.vertispan.j2cl.build.provided.JavacTask.JAVA_SOURCES;

public class StrippedSourcesTask extends Task {

    public StrippedSourcesTask(TaskContext context) {
        super(context);
    }

    @Override
    public void accept(WatchService.ChangeSetHolder changeSetHolder) {
        if(changeSetHolder.created.isEmpty() && changeSetHolder.modified.isEmpty()) {
            return;
        }

        File output = context.outputFactory.create(changeSetHolder.project, OutputTypes.STRIPPED_SOURCES).getOutputPath().resolve("results").toFile();

        List<SourceUtils.FileInfo> sources = Stream.concat(
                        changeSetHolder.created.values().stream(),
                        changeSetHolder.modified.values().stream()
                ).filter(value -> JAVA_SOURCES.matches(value.getSourcePath()))
                .map(p -> SourceUtils.FileInfo.create(p.getAbsolutePath().toString(), p.getSourcePath().toString()))
                .collect(Collectors.toUnmodifiableList());

        GwtIncompatiblePreprocessor preprocessor = new GwtIncompatiblePreprocessor(output, context.log);
        preprocessor.preprocess(sources);
    }
}
