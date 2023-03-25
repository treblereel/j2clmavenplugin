package com.vertispan.j2cl.mojo.incremental;

import com.vertispan.j2cl.build.BuildService;
import com.vertispan.j2cl.build.Project;
import com.vertispan.j2cl.build.PropertyTrackingConfig;
import com.vertispan.j2cl.build.WatchService;
import com.vertispan.j2cl.build.task.OutputTypes;
import com.vertispan.j2cl.mojo.MavenLog;
import com.vertispan.j2cl.mojo.incremental.tasks.BundleJarTask;
import com.vertispan.j2cl.mojo.incremental.tasks.BytecodeTask;
import com.vertispan.j2cl.mojo.incremental.tasks.ClearGeneratedTask;
import com.vertispan.j2cl.mojo.incremental.tasks.ClosureBundleTask;
import com.vertispan.j2cl.mojo.incremental.tasks.J2clTask;
import com.vertispan.j2cl.mojo.incremental.tasks.RemoveDeletedTask;
import com.vertispan.j2cl.mojo.incremental.tasks.StrippedSourcesTask;
import com.vertispan.j2cl.mojo.incremental.tasks.TaskContext;
import com.vertispan.j2cl.mojo.incremental.tasks.TaskGroup;
import com.vertispan.j2cl.mojo.incremental.tasks.TaskGroupExecutor;
import com.vertispan.j2cl.mojo.incremental.tasks.TurbineTask;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtField;
import javassist.CtMethod;
import javassist.NotFoundException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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

        System.out.println("Processor " + buildService.getDiskCache().cacheDir.toPath());

        outputFactory = new Output.OutputFactory(buildService.getDiskCache().cacheDir.toPath());
    }

    private final Set<WatchService.ChangeSetHolder> lastBuildRequest = new HashSet<>();


    public void requestBuild(Set<WatchService.ChangeSetHolder> projectsToBuild) {
        if(projectsToBuild.isEmpty()) {
            return;
        }

        projectsToBuild.addAll(lastBuildRequest);



        PropertyTrackingConfig config = new PropertyTrackingConfig(buildService.getConfig());
        config.getBootstrapClasspath();


        //move to constructor
        TaskGroup taskGroup = new TaskGroup();
        TaskContext context = new TaskContext(outputFactory, config, mavenLog, root);
        taskGroup.addTask(new ClearGeneratedTask(context));
        taskGroup.addTask(new RemoveDeletedTask(context, files));
        taskGroup.addTask(new BytecodeTask(context));
        taskGroup.addTask(new StrippedSourcesTask(context));
        taskGroup.addTask(new TurbineTask(context));
        taskGroup.addTask(new J2clTask(context));
        taskGroup.addTask(new ClosureBundleTask(context));


        System.out.println("requestBuild " + config.getBootstrapClasspath());


        projectsToBuild.forEach(p -> {
            System.out.println("requestBuild " + p.project.getKey());

            p.created.forEach((path, c) -> {
                //onCreate(p.project, path, c);
            });

            p.modified.forEach((path, c) -> {
                //onModified(p.project, path, c);
            });

            onDelete(p.project, p.deleted);
        });

        long start = System.currentTimeMillis();

        Runnable runnable = () -> System.out.println("FINISHED IN " + (System.currentTimeMillis() - start) + "ms");
        BundleJarTask bundleJarTask = new BundleJarTask(context, root, runnable);
        new TaskGroupExecutor(taskGroup, bundleJarTask).execute(projectsToBuild);
    }


    // do not forget to delete files from the output directories



    private void onDelete(Project project, Set<Path> paths) {
        System.out.println("onDelete " + project.getKey());
        paths.forEach(p -> {
            System.out.println("onDelete " + p);
        });
    }

    private void onCreate(Project project, Set<Path> paths) {
        System.out.println("onDelete " + project.getKey());
        paths.forEach(p -> {
            System.out.println("onDelete " + p);
        });
    }

    private void onModified(Project project, Set<Path> paths) {
        System.out.println("onDelete " + project.getKey());
        paths.forEach(p -> {
            System.out.println("onDelete " + p);
        });
    }

    public void ready() {
        System.out.println("ready " + projectListMap.size());
        projectListMap.keySet()
                .stream()
                .flatMap(p -> p.getDependencies().stream())
                .forEach(p -> {
                    Path path = outputFactory.create(p.getProject(), OutputTypes.BYTECODE).getOutputPath();
                    try {
                        pool.appendClassPath(path.resolve("results").toFile().getAbsolutePath());
                    } catch (NotFoundException e) {
                        throw new RuntimeException(e);
                    }
                });

        Map<Project, Set<String>> added = new HashMap<>();

        projectListMap.forEach((project, folders) -> {
            System.out.println("project " + project.getKey());
            added.put(project, new HashSet<>());
            Path byteCodePath = outputFactory.create(project, OutputTypes.BYTECODE).getOutputPath();
            try {
                // is it the same as above?
                pool.appendClassPath(byteCodePath.resolve("results").toFile().getAbsolutePath());
            } catch (NotFoundException e) {
                throw new RuntimeException(e);
            }
            folders.forEach(folder -> {
                System.out.println("path " + folder);
                try (Stream<Path> paths = Files.walk(folder)) {
                    paths.filter(Files::isRegularFile)
                            .filter(file -> file.toString().endsWith(".java")).forEach(file -> {
                                String className = folder.relativize(file).toString().replace(".java", ".class");
                                Path classFile = Paths.get(className);
                                if (Files.exists(byteCodePath.resolve("results").resolve(classFile))) {
                                    added.get(project).add(className.substring(0, className.lastIndexOf(".")));
                                    System.out.println("    " + file + " " + byteCodePath.resolve("results").resolve(classFile).toString());
                                } else {
                                    System.out.println("not found " + byteCodePath.resolve("results").resolve(classFile));
                                }
                            });
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        });


        // pool must be ready
        added.forEach((project, classes) -> {
            classes.forEach(className -> {
                try {
                    System.out.println("take " + className);

                    addOrUpdateCtClass(project, className);


                    CtClass ctClass = pool.get(className.replace("/", "."));
                    System.out.println("found " + ctClass.getName() + " " + ctClass.getPackageName() + " " + ctClass.getNestedClasses().length);

                    //ctClass.getRefClasses()

                    for (CtClass declaredClass : ctClass.getDeclaredClasses()) {
                        System.out.println(" nested " + declaredClass.getName() + " " + declaredClass.getPackageName());
                    }

                } catch (NotFoundException e) {
                    System.out.println("EXP " + e.getMessage() + " " + className.replace("/", "."));
                    throw new RuntimeException(e);
                }
            });
        });


       files.forEach((k, v) -> {
           for (String dependency : v.getDependencies()) {
                if(files.containsKey(dependency)) {
                    files.get(dependency).addDependent(k);
                }
           }
        });

        files.forEach((k, v) -> {
            System.out.println("file " + k + " " + v.getProject().getKey() + " " + v.sourcePath());
            for (String dependency : v.getDependencies()) {
                System.out.println("    dependency : " + dependency);
            }
            v.getDependents().forEach((k1) -> {
                System.out.println("    dependent : " + k1);
            });
        });
    }

    private void addOrUpdateCtClass(Project project, String className) {
        try {
            String fqdn = className.replace("/", ".");
            CtClass ctClass = pool.getOrNull(fqdn);
            if (ctClass != null) {
                if (files.containsKey(fqdn)) {
                    files.remove(fqdn);
                }
                for (CtClass nestedClass : ctClass.getNestedClasses()) {
                    if (files.containsKey(nestedClass.getName())) {
                        files.remove(nestedClass.getName());
                    }
                }
                ClassFile classFile = getClassFile(project, ctClass);
                Definition definition = new Definition(project, classFile);
                files.put(fqdn, definition);
            } else {
                throw new RuntimeException("not found " + className);
            }
        } catch (NotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    private ClassFile getClassFile(Project project, CtClass ctClass) {
        try {
            ClassFile classFile = new ClassFile(ctClass.getName());

            for (CtField field : ctClass.getFields()) {
                classFile.addField(field.getName(), field.getType().getName());
            }

            for (CtMethod method : ctClass.getDeclaredMethods()) {
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
                ClassFile nested = getClassFile(project, nestedClass);
                Definition definition = new Definition(project, classFile);
                files.put(nestedClass.getName(), definition);
                classFile.addNested(nested);
            }

            return classFile;
        } catch (NotFoundException e) {
            throw new RuntimeException(e);
        }
    }


    public void init(Map<Project, List<Path>> projectListMap) {
        System.out.println("init " + projectListMap.size());
        this.projectListMap.putAll(projectListMap);
    }

    private void deleteFolder(Path folderPath) throws Exception {
        if (Files.exists(folderPath)) {
            Files.walk(folderPath)
                    .sorted((p1, p2) -> -p1.compareTo(p2))
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
        }
    }

    private static class Pair<K, V> {
        private final K key;
        private final V value;

        public Pair(K key, V value) {
            this.key = key;
            this.value = value;
        }

        public K getKey() {
            return key;
        }

        public V getValue() {
            return value;
        }
    }

    public void assignProject(Project root) {
        this.root = root;
    }
}
