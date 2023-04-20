package com.vertispan.j2cl.mojo.incremental;

import com.vertispan.j2cl.build.BuildService;
import com.vertispan.j2cl.build.Project;
import com.vertispan.j2cl.build.PropertyTrackingConfig;
import com.vertispan.j2cl.build.WatchService;
import com.vertispan.j2cl.build.task.Dependency;
import com.vertispan.j2cl.build.task.OutputTypes;
import com.vertispan.j2cl.mojo.MavenLog;
import com.vertispan.j2cl.mojo.incremental.tasks.BundleJarTask;
import com.vertispan.j2cl.mojo.incremental.tasks.BytecodeTask;
import com.vertispan.j2cl.mojo.incremental.tasks.ClearGeneratedTask;
import com.vertispan.j2cl.mojo.incremental.tasks.ClosureBundleTask;
import com.vertispan.j2cl.mojo.incremental.tasks.J2clTask;
import com.vertispan.j2cl.mojo.incremental.tasks.PostBytecodeTask;
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
import javassist.Modifier;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.vertispan.j2cl.build.provided.J2clTask.NATIVE_JS_SOURCES;
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

    private final Set<WatchService.ChangeSetHolder> lastBuildRequest = new HashSet<>();

    public void requestBuild(Set<WatchService.ChangeSetHolder> projectsToBuild) {
        if (projectsToBuild.isEmpty()) {
            return;
        }

        projectsToBuild.addAll(lastBuildRequest);
        Map<Project, ChangeSetHolder> current = new ConcurrentHashMap<>();
        if(alwaysRunRootProject) {
            System.out.println("Always running root project " + root.getKey());
            try {
                current.put(root, new ChangeSetHolder(root));
            } catch (Exception e) {
                e.printStackTrace();
            }

        }

        for (WatchService.ChangeSetHolder holder : projectsToBuild) {
            current.putIfAbsent(holder.project, new ChangeSetHolder(holder.project));
            holder.created.values().stream().map(h -> new ChangeSetEntry(h.getSourcePath(), h.getAbsolutePath())).forEach(current.get(holder.project).created::add);
            holder.modified.values().stream().map(h -> new ChangeSetEntry(h.getSourcePath(), h.getAbsolutePath())).forEach(current.get(holder.project).modified::add);
            holder.deleted.forEach(d -> current.get(holder.project).deleted.add(d));
        }

        for (Map.Entry<Project, ChangeSetHolder> entry : current.entrySet()) {
            Set<ChangeSetEntry> natives = Stream.concat(entry.getValue().created.stream(), entry.getValue().modified.stream())
                    .filter(p -> NATIVE_JS_SOURCES.matches(p.absolutePath)).collect(Collectors.toUnmodifiableSet());
            for (ChangeSetEntry n : natives) {
                String absolutePath = n.absolutePath.toFile().toString().replace(".native.js", ".java");
                String relativePath = n.relativePath.toFile().toString().replace(".native.js", ".java");
                entry.getValue().modified.add(new ChangeSetEntry(Paths.get(relativePath), Paths.get(absolutePath)));
            }
        }
        PropertyTrackingConfig config = new PropertyTrackingConfig(buildService.getConfig());
        config.getBootstrapClasspath();


        //move to constructor
        TaskGroup taskGroup = new TaskGroup();
        TaskContext context = new TaskContext(outputFactory, alwaysRunRootProject, config, mavenLog, root, pool, files, current);
        taskGroup.addTask(new ClearGeneratedTask(context));
        taskGroup.addTask(new RemoveDeletedTask(context));
        taskGroup.addTask(new BytecodeTask(context));
        taskGroup.addTask(new PostBytecodeTask(context));
        taskGroup.addTask(new StrippedSourcesTask(context));
        taskGroup.addTask(new TurbineTask(context));
        taskGroup.addTask(new J2clTask(context));
        taskGroup.addTask(new ClosureBundleTask(context));

        System.out.println("requestBuild ");

        projectsToBuild.forEach(p -> {
            System.out.println("project " + p.project.getKey());
            System.out.println("created " + p.created);
            System.out.println("modified " + p.modified);
            System.out.println("deleted " + p.deleted);
        });


        long start = System.currentTimeMillis();

        Runnable runnable = () -> {
            projectsToBuild.clear();
            current.clear();
            System.out.println("FINISHED IN " + (System.currentTimeMillis() - start) + "ms");
        };
        BundleJarTask bundleJarTask = new BundleJarTask(context, root, runnable);
        boolean result = new TaskGroupExecutor(taskGroup, bundleJarTask, context).execute();
        if (!result) {
            context.log.error("Build failed");
        } else {
            context.log.info("Build succeeded");
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
        ClassFile classFile = getClassFile(project, ctClass);
        return new Definition(project, className, classFile);
    }

    private ClassFile getClassFile(Project project, CtClass ctClass) {
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
                ClassFile nested = getClassFile(project, nestedClass);
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

    public void assignProject(Project root) {
        this.root = root;
    }
}
