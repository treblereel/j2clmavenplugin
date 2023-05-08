package com.vertispan.j2cl.build.incremental;

import com.vertispan.j2cl.build.Project;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

public interface IncrementalProcessorDelegate {

    void init(Map<Project, List<Path>> sourcePathsToWatch);

    void ready();

    void requestBuild(Set<WatchService.ChangeSetHolder> projectsToBuild);

    void assignProject(Project p);
}
