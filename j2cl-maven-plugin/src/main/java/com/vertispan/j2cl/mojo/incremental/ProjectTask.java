package com.vertispan.j2cl.mojo.incremental;

import com.vertispan.j2cl.build.task.Project;

import java.util.HashSet;
import java.util.Set;

public class ProjectTask {

    Set<Wrapped> wrapped = new HashSet<>();

    ProjectTask(Set<Project> projects) {
        projects.forEach(p -> wrapped.add(new Wrapped(p)));
    }





    private static class Wrapped {

        private final Project project;

        private Set<Project> dependencies = new HashSet<>();


        public Wrapped(Project project) {
            this.project = project;
            project.getDependencies().stream()
                    .filter(dep -> dep.getProject().hasSourcesMapped())
                    .forEach(dep -> dependencies.add(dep.getProject()));
        }

    }
}
