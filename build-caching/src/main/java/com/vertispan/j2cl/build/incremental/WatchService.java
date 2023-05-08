package com.vertispan.j2cl.build.incremental;

import com.vertispan.j2cl.build.BuildService;
import com.vertispan.j2cl.build.DiskCache;
import com.vertispan.j2cl.build.Project;
import com.vertispan.j2cl.build.task.BuildLog;
import io.methvin.watcher.DirectoryChangeEvent;
import io.methvin.watcher.DirectoryChangeListener;
import io.methvin.watcher.DirectoryWatcher;
import io.methvin.watcher.OnTimeoutListener;
import io.methvin.watcher.changeset.ChangeSet;
import io.methvin.watcher.changeset.ChangeSetEntry;
import io.methvin.watcher.changeset.ChangeSetListener;
import io.methvin.watcher.hashing.FileHash;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Given a set of projects and source paths for each, will watch for changes and notify with the new
 * hashes. If used, will compute the file hashes on its own, no need to ask the build service
 * to do it.
 */
public class WatchService extends com.vertispan.j2cl.build.WatchService {

    private IncrementalProcessorDelegate processor;
    private CompositeListener onTimeoutListener;

    public WatchService(BuildService buildService, ScheduledExecutorService executorService, IncrementalProcessorDelegate processor, BuildLog log) {
        super(buildService, executorService, processor, log);
        this.processor = processor;
    }

    public void watch(Map<Project, List<Path>> sourcePathsToWatch) throws IOException {
        buildLog.info("Start watching " + sourcePathsToWatch);
        Map<Path, Project> pathToProjects = new HashMap<>();
        sourcePathsToWatch.forEach((project, paths) -> {
            paths.forEach(path -> pathToProjects.put(path, project));
        });

        onTimeoutListener = new CompositeListener(100, counter -> {
            Map<Path, ChangeSet> changeSet = onTimeoutListener.getChangeSet();
            update(pathToProjects, changeSet);
        });

        directoryWatcher = DirectoryWatcher.builder()
                .paths(sourcePathsToWatch.values().stream().flatMap(List::stream).collect(Collectors.toList()))
                .listener(onTimeoutListener).build();


        // initial hashes are ready, notify builder of initial hashes since we have them
        for (Map.Entry<Path, Project> entry : pathToProjects.entrySet()) {
            Project project = entry.getValue();
            Path rootPath = entry.getKey();
            Map<Path, DiskCache.CacheEntry> projectFiles = directoryWatcher.pathHashes().entrySet().stream()
                    .filter(e -> e.getValue() != FileHash.DIRECTORY)
                    .filter(e -> e.getKey().startsWith(rootPath))
                    .map(e -> new DiskCache.CacheEntry(rootPath.relativize(e.getKey()), rootPath, e.getValue()))
                    .collect(Collectors.toMap(e -> e.getSourcePath(), Function.identity()));
            buildService.triggerChanges(project, projectFiles, Collections.emptyMap(), Collections.emptySet());
        }

        processor.init(sourcePathsToWatch);

        // start the first build
        buildQueue.requestBuild();

        // start watching to observe changes
        directoryWatcher.watchAsync(executorService);
    }

    private void update(Map<Path, Project> pathToProjects, Map<Path, ChangeSet> changeSet) {
        Set<ChangeSetHolder> projectsToBuild = new HashSet<>();
        for (Map.Entry<Path, ChangeSet> pathChangeSetEntry : changeSet.entrySet()) {
            Project project = pathToProjects.get(pathChangeSetEntry.getKey());
            Map<Path, DiskCache.CacheEntry> created = new HashMap<>();
            Map<Path, DiskCache.CacheEntry> modified = new HashMap<>();
            Set<Path> deleted = new HashSet<>();

            for (ChangeSetEntry changeSetEntry : pathChangeSetEntry.getValue().created()) {
                if (!changeSetEntry.isDirectory()) {
                    Path relativeFilePath = pathChangeSetEntry.getKey().relativize(changeSetEntry.path());
                    created.put(relativeFilePath, new DiskCache.CacheEntry(relativeFilePath, pathChangeSetEntry.getKey(), changeSetEntry.hash()));
                }
            }
            for (ChangeSetEntry changeSetEntry : pathChangeSetEntry.getValue().modified()) {
                if (!changeSetEntry.isDirectory()) {
                    Path relativeFilePath = pathChangeSetEntry.getKey().relativize(changeSetEntry.path());
                    modified.put(relativeFilePath, new DiskCache.CacheEntry(relativeFilePath, pathChangeSetEntry.getKey(), changeSetEntry.hash()));
                }
            }
            for (ChangeSetEntry changeSetEntry : pathChangeSetEntry.getValue().deleted()) {
                if (!changeSetEntry.isDirectory()) {
                    Path relativeFilePath = pathChangeSetEntry.getKey().relativize(changeSetEntry.path());
                    deleted.add(relativeFilePath);
                }
            }
            projectsToBuild.add(new ChangeSetHolder(project, created, modified, deleted));
        }

        processor.requestBuild(projectsToBuild);
    }

    public static class ChangeSetHolder {

        public final Project project;
        public final Map<Path, DiskCache.CacheEntry> created;
        public final Map<Path, DiskCache.CacheEntry> modified;
        public final Set<Path> deleted;

        private ChangeSetHolder(Project project, Map<Path, DiskCache.CacheEntry> created, Map<Path, DiskCache.CacheEntry> modified, Set<Path> deleted) {
            this.project = project;
            this.created = created;
            this.modified = modified;
            this.deleted = deleted;
        }
    }

    public void close() throws IOException {
        directoryWatcher.close();
    }

    private class CompositeListener implements DirectoryChangeListener {

        private final ChangeSetListener changeSetListener = new ChangeSetListener();
        private final OnTimeoutListener onTimeoutListener;

        private CompositeListener(int timeout, Consumer<Integer> consumer) {
            this.onTimeoutListener = new OnTimeoutListener(timeout, consumer);
        }

        @Override
        public void onIdle(int count) {
            onTimeoutListener.onIdle(count);
        }

        @Override
        public void onEvent(DirectoryChangeEvent event) {
            changeSetListener.onEvent(event);
            onTimeoutListener.onEvent(event);
        }

        public Map<Path, ChangeSet> getChangeSet() {
            return changeSetListener.getChangeSet();
        }
    }
}
