package com.vertispan.j2cl.build.task;

import java.io.File;
import java.util.Set;

public interface Dependency {
    Project getProject();

    Scope getScope();

    boolean isAPT();

    Set<String> getProcessors();

    File getJar();

    enum Scope {
        COMPILE,
        RUNTIME,
        BOTH;

        public boolean isCompileScope() {
            return this != RUNTIME;
        }

        public boolean isRuntimeScope() {
            return this != COMPILE;
        }
    }
}
