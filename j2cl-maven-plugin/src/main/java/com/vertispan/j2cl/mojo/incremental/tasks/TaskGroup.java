package com.vertispan.j2cl.mojo.incremental.tasks;

import com.vertispan.j2cl.mojo.incremental.ChangeSetHolder;

import java.util.LinkedHashSet;
import java.util.Set;

public class TaskGroup {

    private final Set<Task> tasks = new LinkedHashSet<>();

    public void addTask(Task task) {
        tasks.add(task);
    }

    public void execute(ChangeSetHolder changeSet) {
        for (Task task : tasks) {
            System.out.println("Executing " + task + " on " + changeSet.project);
            task.accept(changeSet);
        }
    }
}
