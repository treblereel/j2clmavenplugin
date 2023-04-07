package com.vertispan.j2cl.mojo.incremental;

import java.nio.file.Path;
import java.util.Objects;

public class ChangeSetEntry {

    public final Path relativePath;
    public final Path absolutePath;

    public ChangeSetEntry(Path relativePath, Path absolutePath) {
        this.relativePath = relativePath;
        this.absolutePath = absolutePath;
    }

    @Override
    public String toString() {
        return "ChangeSetEntry{" +
                "relativePath=" + relativePath +
                ", absolutePath=" + absolutePath +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ChangeSetEntry that = (ChangeSetEntry) o;
        return Objects.equals(relativePath, that.relativePath) && Objects.equals(absolutePath, that.absolutePath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(relativePath, absolutePath);
    }
}
