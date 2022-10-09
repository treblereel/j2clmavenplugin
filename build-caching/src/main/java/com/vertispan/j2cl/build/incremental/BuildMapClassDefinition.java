package com.vertispan.j2cl.build.incremental;

import java.util.HashSet;
import java.util.Set;

class BuildMapClassDefinition {

    final String className;
    final String hash;
    final Set<String> dependsOn = new HashSet<>();

    boolean propagated = false;

    BuildMapClassDefinition(String className, String hash) {
        this.className = className;
        this.hash = hash;
    }

    BuildMapClassDefinition setPropagated(boolean propagated) {
        this.propagated = propagated;
        return this;
    }
}
