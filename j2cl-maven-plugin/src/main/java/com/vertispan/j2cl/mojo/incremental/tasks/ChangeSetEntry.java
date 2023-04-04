package com.vertispan.j2cl.mojo.incremental.tasks;

public class ChangeSetEntry {

    public final String path;
    public final String absolutePath;

    public ChangeSetEntry(String path, String absolutePath) {
        this.path = path;
        this.absolutePath = absolutePath;
    }
}
