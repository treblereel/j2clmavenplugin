package com.vertispan.j2cl.mojo.incremental;

import com.vertispan.j2cl.build.task.Project;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

public class Definition {

    private final Project project;

    private final String className;

    private final Set<String> in = new TreeSet<>();
    private final Set<String> out = new TreeSet<>();

    private final ClassFile classFile;

    public Definition(Project project, String className, ClassFile classFile) {
        this.project = project;
        this.classFile = classFile;
        this.className = className;
    }

    public Project getProject() {
        return project;
    }

    public Set<String> getDependencies() {
        return classFile.getReferencedClasses();
    }

    public void addIn(String dependent) {
        in.add(dependent);
    }

    public void removeIn(String dependent) {
        in.remove(dependent);
    }

    public Set<String> getIn() {
        return in;
    }

    public String generateHash() {
        return classFile.hash();
    }

    Path sourcePath() {
        for (String sourceRoot : ((com.vertispan.j2cl.build.Project) project).getSourceRoots()) {
            String fileName = classFile.getClassName().replace('.', '/') + ".java";
            Path path = Paths.get(sourceRoot, fileName);
            if (path.toFile().exists()) {
                return path;
            }
        }
        throw new RuntimeException("Could not find source for " + classFile.getClassName());
    }

    public void addOut(String dependency) {
        out.add(dependency);
    }

    public void removeOut(String dependency) {
        out.remove(dependency);
    }

    public Set<String> getOut() {
        return out;
    }

    @Override
    public String toString() {
        return "Definition{" +
                "project=" + project.getKey() +
                ", name=" + className +
                ", in=" + in.stream().collect(Collectors.joining(",")) +
                ", out=" + out.stream().collect(Collectors.joining(",")) +
                ", classFile=" + classFile.getClassName() +
                '}';
    }
}
