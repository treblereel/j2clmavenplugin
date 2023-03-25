package com.vertispan.j2cl.mojo.incremental.tasks;

import com.vertispan.j2cl.build.Project;
import com.vertispan.j2cl.build.WatchService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.stream.Collectors;

public class TaskGroupExecutor {

    private final TaskGroup group;
    private final BundleJarTask finalTask;

    public TaskGroupExecutor(TaskGroup group, BundleJarTask finalTask) {
        this.group = group;
        this.finalTask = finalTask;
    }

    public void execute(Set<WatchService.ChangeSetHolder> changeSet) {
        Map<Project, WatchService.ChangeSetHolder> projectsToBuild = changeSet.stream()
                .collect(Collectors.toMap(p -> p.project, p -> p));
        List<WatchService.ChangeSetHolder> ordered = new ArrayList<>(projectsToBuild.size());
        for(Project project : order(projectsToBuild)) {
            ordered.add(projectsToBuild.get(project));
        }
        group.execute(ordered);

        System.out.println("Finished executing task group");

        finalTask.finish();
    }

    private List<Project> order(Map<Project, WatchService.ChangeSetHolder> projectsToBuild) {
        List<Project> ordered = new ArrayList<>(projectsToBuild.size());
        Map<String, Project> projects = projectsToBuild.keySet().stream().collect(Collectors.toMap(Project::getKey, p -> p));
        Map<String, Set<String>> adjacencyList = projects.values().stream().
                collect(Collectors.toMap(Project::getKey, p -> p.getDependencies().
                        stream()
                        .filter(d -> d.getProject().hasSourcesMapped())
                        .filter(d -> projects.containsKey(d.getProject().getKey()))
                        .map(d -> d.getProject().getKey())
                        .collect(Collectors.toSet())));
        Stack<String> stack = new Stack<>();
        stack.addAll(projects.keySet());
        while (!stack.isEmpty()) {
            String key = stack.peek();
            if(adjacencyList.get(key).isEmpty()) {
                ordered.add(projects.get(key));
                stack.pop();
                adjacencyList.values().forEach(set -> set.remove(key));
            }
        }
        return ordered;
    }
}
