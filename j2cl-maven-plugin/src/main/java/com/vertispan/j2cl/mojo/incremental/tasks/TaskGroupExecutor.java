package com.vertispan.j2cl.mojo.incremental.tasks;

import com.vertispan.j2cl.build.Project;
import com.vertispan.j2cl.build.WatchService;
import com.vertispan.j2cl.mojo.incremental.ChangeSetHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.Stack;
import java.util.stream.Collectors;

public class TaskGroupExecutor {

    private final TaskGroup group;
    private final BundleJarTask finalTask;

    private final TaskContext context;

    public TaskGroupExecutor(TaskGroup group, BundleJarTask finalTask, TaskContext context) {
        this.group = group;
        this.finalTask = finalTask;
        this.context = context;
    }

    public void execute() {
        while (!context.getOrderedQueue().isEmpty()) {
            Project project = context.getOrderedQueue().poll();
            group.execute(context.current.remove(project));
        }
        System.out.println("Finished executing task group");
        finalTask.finish();
    }
}
