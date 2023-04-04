package com.vertispan.j2cl.mojo.incremental.tasks;

import com.vertispan.j2cl.build.DiskCache;
import com.vertispan.j2cl.build.WatchService;
import com.vertispan.j2cl.build.task.OutputTypes;
import com.vertispan.j2cl.build.task.Project;
import com.vertispan.j2cl.mojo.incremental.ClassFile;
import com.vertispan.j2cl.mojo.incremental.Definition;
import javassist.CtClass;
import javassist.CtField;
import javassist.CtMethod;
import javassist.Modifier;
import javassist.NotFoundException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static com.vertispan.j2cl.build.provided.JavacTask.JAVA_SOURCES;

public class PostBytecodeTask extends Task {

    public PostBytecodeTask(TaskContext context) {
        super(context);
    }

    @Override
    public void accept(WatchService.ChangeSetHolder changeSetHolder) {
        Map<String, Definition> removed = new HashMap<>();
        System.out.println("accept " + changeSetHolder.created + " " + changeSetHolder.modified + " " + changeSetHolder.deleted);

        Project project = changeSetHolder.project;
        Path results = context.outputFactory.create(project, OutputTypes.BYTECODE).results();

        Set<Path> removeFromPool = new HashSet<>();
        changeSetHolder.created.entrySet().stream().map(Map.Entry::getKey).forEach(removeFromPool::add);
        changeSetHolder.modified.entrySet().stream().map(Map.Entry::getKey).forEach(removeFromPool::add);
        removeFromPool.addAll(changeSetHolder.deleted);

        removeFromPool.stream()
                .filter(r -> JAVA_SOURCES.matches(r))
                .map(Path::toString)
                .map(r -> r.replace("/", "."))
                .map(r -> r.replace(".java", ""))
                .forEach(r -> {
                    removed.put(r, context.files.remove(r));
                    context.pool.getOrNull(r).detach();
                });

        removed.forEach((path, definition) -> {
            definition.getIn().forEach(in -> {
                Definition dep = context.files.get(in);
                if (dep != null) {
                    dep.removeOut(path);
                }
            });
        });

        changeSetHolder.created.forEach((path, file) -> {
            processByteCodePath(project, results, path);
        });


        changeSetHolder.created.forEach((path, file) -> {
            processByteCodePath(project, results, path);
        });

        Set<String> doRecompute = new HashSet<>();

        changeSetHolder.modified.forEach((path, file) -> {
            Definition oldDefinition = removed.get(path.toString().replace('/', '.').replace(".java", ""));
            Definition newDefinition = processByteCodePath(project, results, path);
            newDefinition.getIn().addAll(oldDefinition.getIn());
            boolean theSame = oldDefinition.generateHash().equals(newDefinition.generateHash());
            System.out.println("Modified " + path + " " + theSame);
            if(!theSame) {
                System.out.println("Different " + path);
                doRecompute.addAll(newDefinition.getIn());
            }
        });


        doRecompute.forEach(in -> {
            //check if this is already in the queue
                Project project1 = context.files.get(in).getProject();
                Path fqdn = Paths.get(in.replace(".", "/") + ".java");
                if(!context.current.stream().filter(set -> set.project.equals(project1))
                                .filter(set -> set.modified.keySet().contains(fqdn))
                        .findFirst()
                        .isPresent()){
                for(String root: ((com.vertispan.j2cl.build.Project)project1).getSourceRoots()) {
                    Path maybe = Paths.get(root).resolve(fqdn);
                    System.out.println(" path? " + root + " " + Files.exists(maybe));
                    if(Files.exists(maybe)) {
                        DiskCache.CacheEntry cacheEntry = new DiskCache.CacheEntry(fqdn, maybe, null);
                        Optional<WatchService.ChangeSetHolder> holder = context.current.stream().filter(set -> set.project.equals(project1)).findFirst();
                        if(holder.isPresent()) {
                            holder.get().modified.put(fqdn, cacheEntry);
                        } else {
                            Map<Path, DiskCache.CacheEntry> modified = new HashMap<>();
                            modified.put(fqdn, cacheEntry);
                            context.current.add(new WatchService.ChangeSetHolder(project1, new HashMap<>(), modified, Collections.emptySet()));
                        }




                        context.current.add(new WatchService.ChangeSetHolder(project1, new HashMap<>(), new HashMap<>(), new HashMap<>()));
                        break;
                    }

                }


                } else {
                    System.out.println("already in queue");
                }


                System.out.println("recomputing " + in + " " + project1.getKey());
        });

    }

    private Definition processByteCodePath(Project project, Path results, Path path) {
        String classname = path.toString().replace('/', '.').replace(".java", "");
        String classBytecode = path.toString().replace(".java", ".class");

        System.out.println("created " + classname + " " + classBytecode + " " + context.files.get(classname));
        Definition definition;
        try {
            context.pool.appendClassPath(results.resolve(classBytecode).toString());
            CtClass ctClass = context.pool.get(classname);
            definition = addDefinition(project, classname, ctClass);

            for (String dependency : definition.getDependencies()) {
                if (context.files.containsKey(dependency)) {
                    context.files.get(classname).addOut(dependency);
                    context.files.get(dependency).addIn(classname);
                }
            }
        } catch (NotFoundException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
        return definition;
    }

    private Definition addDefinition(Project project, String className, CtClass ctClass) {
        Definition definition = createDefinition(project, className, ctClass);
        context.files.put(className, definition);
        return definition;
    }

    private Definition createDefinition(Project project, String className, CtClass ctClass) {
        ClassFile classFile = getClassFile(ctClass);
        return new Definition(project, className, classFile);
    }

    private ClassFile getClassFile(CtClass ctClass) {
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
                if (Modifier.isPrivate(nestedClass.getModifiers())) {
                    continue;
                }
                ClassFile nested = getClassFile(nestedClass);
                classFile.addNested(nested);
            }
            return classFile;
        } catch (NotFoundException e) {
            throw new RuntimeException(e);
        }
    }


}
