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
mvn dependency:get -Dartifact=org.treblereel.gwt.j2cl:j2cl-maven-plugin:0.1-SNAPSHOT -DremoteRepositories=sonatype-snapshots::::https://oss.sonatype.org/content/repositories/snapshots

2 generate simple j2cl application
mvn archetype:generate -DarchetypeGroupId=org.treblereel.gwt.j2cl -DarchetypeArtifactId=j2cl-maven-plugin -DarchetypeVersion=0.1-SNAPSHOT

3 to run dev mode:
mvn package -Pdevmode

4 build war 
mvn package -build


build local:

1 clone https://github.com/gitgabrio/connected

2 switch to branch j2cl-mavenplugin

3 issue mvn package -Pdevmode

The connected project has been modified so that

1 all the dependencies and compiled classes/js ends up inside target/webapp

2 the jetty server listen for modification and serves target/webapp



