package com.vertispan.j2cl.mojo.incremental.tasks;

import com.vertispan.j2cl.mojo.incremental.ChangeSetHolder;

import java.util.function.Consumer;

public abstract class Task implements Consumer<ChangeSetHolder> {

    protected TaskContext context;

    Task(TaskContext context) {
        this.context = context;
    }
}
