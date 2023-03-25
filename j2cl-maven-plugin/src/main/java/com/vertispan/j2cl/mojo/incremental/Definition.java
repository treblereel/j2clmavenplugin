package com.vertispan.j2cl.mojo.incremental;

import com.vertispan.j2cl.build.Project;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.TreeSet;

public class Definition {

    private Project project;

    private Set<String> dependents = new TreeSet<>();

    private final ClassFile classFile;

    Definition(Project project, ClassFile classFile) {
        this.project = project;
        this.classFile = classFile;
    }

    public Project getProject() {
        return project;
    }

    public Set<String> getDependencies() {
        return classFile.getReferencedClasses();
    }

    public void addDependent(String dependent) {
        dependents.add(dependent);
    }

    public void removeDependent(String dependent) {
        dependents.remove(dependent);
    }

    public Set<String> getDependents() {
        return dependents;
    }

    public void generateHash() {
        classFile.hash();
    }

    Path sourcePath() {
        for (String sourceRoot : project.getSourceRoots()) {
            String fileName = classFile.getClassName().replace('.', '/') + ".java";
            Path path = Paths.get(sourceRoot, fileName);
            if (path.toFile().exists()) {
                return path;
            }
        }
        throw new RuntimeException("Could not find source for " + classFile.getClassName());
    }
}
