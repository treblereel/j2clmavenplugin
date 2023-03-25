package com.vertispan.j2cl.mojo.incremental.tasks;

import com.vertispan.j2cl.build.WatchService;

import java.util.function.Consumer;

public abstract class Task implements Consumer<WatchService.ChangeSetHolder> {

    protected TaskContext context;

    Task(TaskContext context) {
        this.context = context;
    }
}
