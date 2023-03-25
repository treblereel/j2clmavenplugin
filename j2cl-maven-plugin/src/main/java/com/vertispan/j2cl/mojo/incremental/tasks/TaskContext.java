package com.vertispan.j2cl.mojo.incremental.tasks;

import com.vertispan.j2cl.build.Project;
import com.vertispan.j2cl.build.PropertyTrackingConfig;
import com.vertispan.j2cl.build.task.BuildLog;
import com.vertispan.j2cl.mojo.incremental.Output;

public class TaskContext {


    public final Output.OutputFactory outputFactory;
    public final PropertyTrackingConfig config;
    public final Project application;

    public final BuildLog log;

    public TaskContext(Output.OutputFactory outputFactory, PropertyTrackingConfig config, BuildLog log, Project root) {
        this.outputFactory = outputFactory;
        this.config = config;
        this.log = log;
        this.application = root;
    }
}
