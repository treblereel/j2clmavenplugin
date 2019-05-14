J2CL Maven plugin
=================

This plugin includes the code original developed as

        com.vertispan.j2cl:build-tools

built from here:

    https://github.com/gitgabrio/j2cl-devmode-strawman

------------------------
The plugin has three goals

1. build: it executes a single compilation

2. run: it starts in listening mode detecting file changing and eventually recompiling them

3. clean: it cleans up all the plugin-specific directories

----------------------
To test it:

use snapshot repo:

1 download archetype:
```console
mvn dependency:get -Dartifact=org.treblereel.gwt.j2cl:j2cl-maven-plugin:0.1-SNAPSHOT -DremoteRepositories=sonatype-snapshots::::https://oss.sonatype.org/content/repositories/snapshots
```

2 generate simple j2cl application
```console
mvn archetype:generate -DarchetypeGroupId=org.treblereel.gwt.j2cl -DarchetypeArtifactId=j2cl-maven-plugin -DarchetypeVersion=0.1-SNAPSHOT
```

to run dev mode:
```console
mvn clean package -Pdevmode
```

build war 
```console
mvn clean package -build
```

build local:

```console
git clone clone https://github.com/treblereel/j2clmavenplugin.git
mvn clean install -DskipTests
```

The connected project has been modified so that

1 all the dependencies and compiled classes/js ends up inside target/webapp

2 the jetty server listen for modification and serves target/webapp



