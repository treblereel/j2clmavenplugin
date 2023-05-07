package com.vertispan.j2cl.mojo.incremental;

import com.vertispan.j2cl.build.BuildService;
import com.vertispan.j2cl.build.Project;
import com.vertispan.j2cl.build.WatchService;
import com.vertispan.j2cl.build.task.Dependency;
import com.vertispan.j2cl.build.task.OutputTypes;
import com.vertispan.j2cl.mojo.MavenLog;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtField;
import javassist.CtMethod;
import javassist.Modifier;
import javassist.NotFoundException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.vertispan.j2cl.build.provided.JavacTask.JAVA_BYTECODE;

public class Processor implements WatchService.IncrementalProcessorDelegate {

    private final ClassPool pool = new ClassPool(null);

    private final Output.OutputFactory outputFactory;

    private final BuildService buildService;

    private final Map<String, Definition> files = new HashMap<>();
    private final MavenLog mavenLog;

    private Map<Project, List<Path>> projectListMap = new HashMap<>();
    private Project root;

    public Processor(BuildService buildService, MavenLog mavenLog) {
        this.buildService = buildService;
        this.mavenLog = mavenLog;
        pool.appendSystemPath();
        outputFactory = new Output.OutputFactory(buildService.getDiskCache().cacheDir.toPath());
    }

    private final ConcurrentLinkedQueue<WatchService.ChangeSetHolder> buildQueue = new ConcurrentLinkedQueue<>();

    private Set<WatchService.ChangeSetHolder> failedBuildQueue = new HashSet<>();
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    public void requestBuild(Set<WatchService.ChangeSetHolder> projectsToBuild) {
        if (projectsToBuild.isEmpty()) {
            return;
        }
        run(projectsToBuild);
    }

    private void run(Set<WatchService.ChangeSetHolder> projectsToBuild) {

        buildQueue.addAll(projectsToBuild);

        if (!isRunning.get()) {
            new BuildRunner()
                    .setBuildQueue(buildQueue)
                    .setFailedBuildQueue(failedBuildQueue)
                    .setAlwaysRunRootProject(alwaysRunRootProject)
                    .setRoot(root)
                    .setIsRunning(isRunning)
                    .setBuildService(buildService)
                    .setMavenLog(mavenLog)
                    .setOutputFactory(outputFactory)
                    .setFiles(files)
                    .setPool(pool)
                    .setProjectsToBuild(projectsToBuild).start();
        }
    }

    private boolean alwaysRunRootProject;

    public void ready() {
        projectListMap.keySet()
                .stream()
                .flatMap(p -> p.getDependencies().stream())
                .forEach(p -> {
                    Path path = outputFactory.create(p.getProject(), OutputTypes.BYTECODE).results();
                    try {
                        pool.appendClassPath(path.toString());
                    } catch (NotFoundException e) {
                        e.printStackTrace();
                        throw new RuntimeException(e);
                    }
                });

        projectListMap.forEach((project, folders) -> {
            Path byteCodePath = outputFactory.create(project, OutputTypes.BYTECODE).results();
            try {
                // is it the same as above?
                pool.appendClassPath(byteCodePath.toString());
            } catch (NotFoundException e) {
                e.printStackTrace();
                throw new RuntimeException(e);
            }

            try (Stream<Path> paths = Files.walk(byteCodePath).filter(JAVA_BYTECODE::matches)) {
                for (Path path : paths.collect(Collectors.toUnmodifiableSet())) {
                    Path relative = byteCodePath.relativize(path);
                    String className = relative.toString().replace(".class", "").replace("/", ".");
                    if (className.contains("$")) {
                        continue;
                    }
                    CtClass ctClass = pool.getOrNull(className);
                    if (ctClass != null && ctClass.getDeclaringClass() == null) {
                        Definition definition = createDefinition(project, className, ctClass);
                        files.put(className, definition);
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
                throw new RuntimeException(e);
            } catch (Exception e) {
                e.printStackTrace();
                throw new RuntimeException(e);
            }
        });

        files.forEach((k, v) -> {
            for (String dependency : v.getDependencies()) {
                if (files.containsKey(dependency)) {
                    files.get(k).addOut(dependency);
                    files.get(dependency).addIn(k);
                }
            }
        });

        mavenLog.info("check Main project " + root.getKey() + " has APT processors");
        // If main project has APT processors, we need to run it
        long processors = root.getDependencies().stream().filter(Dependency::isAPT).count();
        if(processors > 0) {
            alwaysRunRootProject = true;
            mavenLog.info("Main project " + root.getKey() + " has APT processors, always running it");
        }
        mavenLog.info("initial setup is done ");
    }

    private Definition createDefinition(Project project, String className, CtClass ctClass) {
        ClassFile classFile = getClassFile(ctClass);
        return new Definition(project, className, classFile);
    }

    private ClassFile getClassFile(CtClass ctClass) {
        try {
            ClassFile classFile = new ClassFile(ctClass.getName());

            for (CtField field : ctClass.getFields()) {
                if(Modifier.isPrivate(field.getModifiers())) {
                    continue;
                }
                classFile.addField(field.getName(), field.getType().getName());
            }

            for (CtMethod method : ctClass.getDeclaredMethods()) {
                if(Modifier.isPrivate(method.getModifiers())) {
                    continue;
                }
                String params = Arrays.stream(method.getParameterTypes()).map(CtClass::getName).collect(Collectors.joining(","));
                classFile.addMethod(method.getName(), method.getReturnType().getName(), params);
            }

            classFile.addExtendsClass(ctClass.getSuperclass().getName());
            Arrays.stream(ctClass.getInterfaces()).forEach(i -> classFile.addImplementsInterface(i.getName()));

            ctClass.getRefClasses().stream()
                    .filter(r -> !r.startsWith("java."))
                    .filter(r -> !r.equals(ctClass.getName()))
                    .forEach(classFile::addReference);

            for (CtClass nestedClass : ctClass.getNestedClasses()) {
                if (Modifier.isPrivate(nestedClass.getModifiers()) || isAnonymousClass(nestedClass)) {
                    continue;
                }
                ClassFile nested = getClassFile(nestedClass);
                classFile.addNested(nested);
            }
            return classFile;
        } catch (Exception e) {
            e.printStackTrace();
            throw new Error(e);
        }
    }

    private boolean isAnonymousClass(CtClass ctClass) {
        String className = ctClass.getName();
        return Pattern.matches(".+\\$\\d+.*", className);
    }

    public void init(Map<Project, List<Path>> projectListMap) {
        this.projectListMap.putAll(projectListMap);
    }

    public void assignProject(Project root) {
        this.root = root;
    }
}
