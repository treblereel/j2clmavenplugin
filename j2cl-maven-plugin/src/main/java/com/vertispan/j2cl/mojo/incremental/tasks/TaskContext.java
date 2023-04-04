package com.vertispan.j2cl.mojo.incremental.tasks;

import com.vertispan.j2cl.build.Project;
import com.vertispan.j2cl.build.PropertyTrackingConfig;
import com.vertispan.j2cl.build.WatchService;
import com.vertispan.j2cl.build.task.BuildLog;
import com.vertispan.j2cl.mojo.incremental.Definition;
import com.vertispan.j2cl.mojo.incremental.Output;
import javassist.ClassPool;

import java.util.Map;
import java.util.Set;

public class TaskContext {


    public final Output.OutputFactory outputFactory;
    public final PropertyTrackingConfig config;
    public final Project application;
    public final BuildLog log;
    public final ClassPool pool;
    public final Map<String, Definition> files;
    public final Map<Project, WatchService.ChangeSetHolder> current;

    public TaskContext(Output.OutputFactory outputFactory, PropertyTrackingConfig config, BuildLog log, Project root, ClassPool pool, Map<String, Definition> files, Set<WatchService.ChangeSetHolder> current) {
        this.outputFactory = outputFactory;
        this.config = config;
        this.log = log;
        this.application = root;
        this.pool = pool;
        this.files = files;
        this.current = current;

    }
}
