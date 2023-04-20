package com.vertispan.j2cl.mojo.incremental;

import com.vertispan.j2cl.build.task.Project;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

public class ChangeSetHolder {

    public final Project project;

    public final Set<ChangeSetEntry> created = new HashSet<>();
    public final Set<ChangeSetEntry> modified = new HashSet<>();
    public final Set<Path> deleted = new HashSet<>();

    public ChangeSetHolder(Project project) {
        this.project = project;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ChangeSetHolder that = (ChangeSetHolder) o;
        return Objects.equals(project, that.project);
    }

    @Override
    public int hashCode() {
        return Objects.hash(project);
    }
}
