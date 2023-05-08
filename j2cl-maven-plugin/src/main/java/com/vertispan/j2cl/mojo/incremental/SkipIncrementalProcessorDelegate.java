package com.vertispan.j2cl.mojo.incremental;

import com.vertispan.j2cl.build.Project;
import com.vertispan.j2cl.build.incremental.IncrementalProcessorDelegate;
import com.vertispan.j2cl.build.incremental.WatchService;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SkipIncrementalProcessorDelegate implements IncrementalProcessorDelegate {
    @Override
    public void init(Map<Project, List<Path>> sourcePathsToWatch) {

    }

    @Override
    public void ready() {

    }

    @Override
    public void requestBuild(Set<WatchService.ChangeSetHolder> projectsToBuild) {

    }

    @Override
    public void assignProject(Project p) {

    }
}
