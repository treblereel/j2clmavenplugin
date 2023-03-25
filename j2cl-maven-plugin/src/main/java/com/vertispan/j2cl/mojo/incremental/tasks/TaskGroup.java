package com.vertispan.j2cl.mojo.incremental.tasks;

import com.vertispan.j2cl.build.WatchService;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class TaskGroup {

    private final Set<Task> tasks = new LinkedHashSet<>();

    public void addTask(Task task) {
        tasks.add(task);
    }

    public void execute(List<WatchService.ChangeSetHolder> changeSet) {
        for (Task task : tasks) {
            for (WatchService.ChangeSetHolder changeSer : changeSet) {
                System.out.println("Executing " + task + " on " + changeSer.project);
                task.accept(changeSer);
            }
        }
    }
}
