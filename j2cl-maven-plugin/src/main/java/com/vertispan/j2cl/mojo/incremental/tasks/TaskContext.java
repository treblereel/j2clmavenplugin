package com.vertispan.j2cl.mojo.incremental.tasks;

import com.vertispan.j2cl.build.Project;
import com.vertispan.j2cl.build.PropertyTrackingConfig;
import com.vertispan.j2cl.build.WatchService;
import com.vertispan.j2cl.build.task.BuildLog;
import com.vertispan.j2cl.mojo.incremental.ChangeSetHolder;
import com.vertispan.j2cl.mojo.incremental.Definition;
import com.vertispan.j2cl.mojo.incremental.Output;
import javassist.ClassPool;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.Stack;
import java.util.stream.Collectors;

public class TaskContext {


    public final Output.OutputFactory outputFactory;
    public final PropertyTrackingConfig config;
    public final Project application;
    public final BuildLog log;
    public final ClassPool pool;
    public final Map<String, Definition> files;
    public final Map<Project, ChangeSetHolder> current;
    private final Queue<Project> ordered = new LinkedList<>();

    public TaskContext(Output.OutputFactory outputFactory, PropertyTrackingConfig config, BuildLog log, Project root, ClassPool pool, Map<String, Definition> files, Map<Project, ChangeSetHolder> current) {
        this.outputFactory = outputFactory;
        this.config = config;
        this.log = log;
        this.application = root;
        this.pool = pool;
        this.files = files;
        this.current = current;

        order(current);
    }

    Queue<Project> getOrderedQueue() {
        return ordered;
    }

    public void addToBuildQueue(Project project) {
        System.out.println("Adding " + project + " to build queue");
        if(!ordered.contains(project)) {
            ordered.add(project);
        }
    }

    private void order(Map<Project, ChangeSetHolder> projectsToBuild) {
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
    }
}
