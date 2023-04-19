package com.vertispan.j2cl.mojo.incremental.tasks;

import com.vertispan.j2cl.mojo.incremental.ChangeSetHolder;

import java.util.function.Function;

public abstract class Task implements Function<ChangeSetHolder, Boolean> {

    protected TaskContext context;

    Task(TaskContext context) {
        this.context = context;
    }
}
