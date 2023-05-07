package com.vertispan.j2cl.mojo.incremental;

import com.vertispan.j2cl.build.BuildService;
import com.vertispan.j2cl.build.Project;
import com.vertispan.j2cl.build.PropertyTrackingConfig;
import com.vertispan.j2cl.build.WatchService;
import com.vertispan.j2cl.mojo.MavenLog;
import com.vertispan.j2cl.mojo.incremental.tasks.BundleJarTask;
import com.vertispan.j2cl.mojo.incremental.tasks.BytecodeTask;
import com.vertispan.j2cl.mojo.incremental.tasks.ClearGeneratedTask;
import com.vertispan.j2cl.mojo.incremental.tasks.ClosureBundleTask;
import com.vertispan.j2cl.mojo.incremental.tasks.J2clTask;
import com.vertispan.j2cl.mojo.incremental.tasks.PostBytecodeTask;
import com.vertispan.j2cl.mojo.incremental.tasks.RemoveDeletedTask;
import com.vertispan.j2cl.mojo.incremental.tasks.StrippedSourcesTask;
import com.vertispan.j2cl.mojo.incremental.tasks.TaskContext;
import com.vertispan.j2cl.mojo.incremental.tasks.TaskGroup;
import com.vertispan.j2cl.mojo.incremental.tasks.TaskGroupExecutor;
import com.vertispan.j2cl.mojo.incremental.tasks.TurbineTask;
import javassist.ClassPool;

import java.nio.file.Paths;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.vertispan.j2cl.build.provided.J2clTask.NATIVE_JS_SOURCES;

class BuildRunner extends Thread {


    private AtomicBoolean isRunning;

    private Queue<WatchService.ChangeSetHolder> buildQueue;
    private boolean alwaysRunRootProject;
    private Project root;
    private BuildService buildService;
    private Map<String, Definition> files;
    private MavenLog mavenLog;

    private Set<WatchService.ChangeSetHolder> failedBuildQueue;

    private ClassPool pool;

    private Output.OutputFactory outputFactory;

    private Set<WatchService.ChangeSetHolder> projectsToBuild;

    @Override
    public void run() {
        isRunning.set(true);
        while (!buildQueue.isEmpty()) {
            Map<Project, ChangeSetHolder> current = new ConcurrentHashMap<>();
            if(alwaysRunRootProject) {
                mavenLog.info("Always running root project " + root.getKey());
                try {
                    current.put(root, new ChangeSetHolder(root));
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            CopyOnWriteArrayList<WatchService.ChangeSetHolder> onWriteArrayList = new CopyOnWriteArrayList<>(buildQueue);
            onWriteArrayList.addAll(failedBuildQueue);

            failedBuildQueue.clear();
            buildQueue.clear();
            //TODO there is can be a case when .java file is deleted and then added again
            for (WatchService.ChangeSetHolder holder : onWriteArrayList) {
                current.putIfAbsent(holder.project, new ChangeSetHolder(holder.project));
                holder.created.values().stream().map(h -> new ChangeSetEntry(h.getSourcePath(), h.getAbsolutePath())).forEach(current.get(holder.project).created::add);
                holder.modified.values().stream().map(h -> new ChangeSetEntry(h.getSourcePath(), h.getAbsolutePath())).forEach(current.get(holder.project).modified::add);
                holder.deleted.forEach(d -> current.get(holder.project).deleted.add(d));
            }

            for (Map.Entry<Project, ChangeSetHolder> entry : current.entrySet()) {
                Set<ChangeSetEntry> natives = Stream.concat(entry.getValue().created.stream(), entry.getValue().modified.stream())
                        .filter(p -> NATIVE_JS_SOURCES.matches(p.absolutePath)).collect(Collectors.toUnmodifiableSet());
                for (ChangeSetEntry n : natives) {
                    String absolutePath = n.absolutePath.toFile().toString().replace(".native.js", ".java");
                    String relativePath = n.relativePath.toFile().toString().replace(".native.js", ".java");
                    entry.getValue().modified.add(new ChangeSetEntry(Paths.get(relativePath), Paths.get(absolutePath)));
                }
            }

            //move to constructor
            TaskContext context = new TaskContext(outputFactory, alwaysRunRootProject, new PropertyTrackingConfig(buildService.getConfig()), mavenLog, root, pool, files, current);
            TaskGroup taskGroup = new TaskGroup();
            taskGroup.addTask(new ClearGeneratedTask(context));
            taskGroup.addTask(new RemoveDeletedTask(context));
            taskGroup.addTask(new BytecodeTask(context));
            taskGroup.addTask(new PostBytecodeTask(context));
            taskGroup.addTask(new StrippedSourcesTask(context));
            taskGroup.addTask(new TurbineTask(context));
            taskGroup.addTask(new J2clTask(context));
            taskGroup.addTask(new ClosureBundleTask(context));

            context.log.info("build was requested for projects:");

            projectsToBuild.forEach(p -> {
                context.log.info("project " + p.project.getKey());
                context.log.info("created " + p.created);
                context.log.info("modified " + p.modified);
                context.log.info("deleted " + p.deleted);
            });

            long start = System.currentTimeMillis();

            Runnable callback = () -> {
                isRunning.set(false);
                context.log.info("FINISHED IN " + (System.currentTimeMillis() - start) + "ms");
            };
            BundleJarTask bundleJarTask = new BundleJarTask(context, root, callback);
            boolean result = new TaskGroupExecutor(taskGroup, bundleJarTask, context).execute();
            if (!result) {
                failedBuildQueue.addAll(onWriteArrayList);
                context.log.error("Build failed");
            } else {
                context.log.info("-----  Build Complete: ready for browser refresh  -----");
            }
        }
    }

    public BuildRunner setIsRunning(AtomicBoolean isRunning) {
        this.isRunning = isRunning;
        return this;
    }

    public BuildRunner setBuildQueue(Queue<WatchService.ChangeSetHolder> buildQueue) {
        this.buildQueue = buildQueue;
        return this;
    }

    public BuildRunner setAlwaysRunRootProject(boolean alwaysRunRootProject) {
        this.alwaysRunRootProject = alwaysRunRootProject;
        return this;
    }

    public BuildRunner setRoot(Project root) {
        this.root = root;
        return this;
    }

    public BuildRunner setBuildService(BuildService buildService) {
        this.buildService = buildService;
        return this;
    }

    public BuildRunner setFiles(Map<String, Definition> files) {
        this.files = files;
        return this;
    }

    public BuildRunner setMavenLog(MavenLog mavenLog) {
        this.mavenLog = mavenLog;
        return this;
    }

    public BuildRunner setFailedBuildQueue(Set<WatchService.ChangeSetHolder> failedBuildQueue) {
        this.failedBuildQueue = failedBuildQueue;
        return this;
    }

    public BuildRunner setPool(ClassPool pool) {
        this.pool = pool;
        return this;
    }

    public BuildRunner setOutputFactory(Output.OutputFactory outputFactory) {
        this.outputFactory = outputFactory;
        return this;
    }

    public BuildRunner setProjectsToBuild(Set<WatchService.ChangeSetHolder> projectsToBuild) {
        this.projectsToBuild = projectsToBuild;
        return this;
    }
}
