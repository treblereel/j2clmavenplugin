package com.vertispan.j2cl.mojo.incremental.tasks;

import com.vertispan.j2cl.mojo.incremental.ChangeSetHolder;

import java.util.LinkedHashSet;
import java.util.Set;

public class TaskGroup {

    private final Set<Task> tasks = new LinkedHashSet<>();

    public void addTask(Task task) {
        tasks.add(task);
    }

    public boolean execute(ChangeSetHolder changeSet) {
        for (Task task : tasks) {
            task.context.log.info("Executing " + task.getClass().getCanonicalName() + " on " + changeSet.project);
            boolean result = task.apply(changeSet);
            if(!result) {
                return false;
            }
        }
        return true;
    }
}
