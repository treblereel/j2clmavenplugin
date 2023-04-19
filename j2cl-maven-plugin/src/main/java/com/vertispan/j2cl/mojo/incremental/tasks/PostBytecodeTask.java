package com.vertispan.j2cl.mojo.incremental.tasks;

import com.vertispan.j2cl.build.task.OutputTypes;
import com.vertispan.j2cl.build.task.Project;
import com.vertispan.j2cl.mojo.incremental.ChangeSetEntry;
import com.vertispan.j2cl.mojo.incremental.ChangeSetHolder;
import com.vertispan.j2cl.mojo.incremental.ClassFile;
import com.vertispan.j2cl.mojo.incremental.Definition;
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
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.vertispan.j2cl.build.provided.JavacTask.JAVA_SOURCES;

public class PostBytecodeTask extends Task {

    public PostBytecodeTask(TaskContext context) {
        super(context);
    }

    @Override
    public Boolean apply(ChangeSetHolder changeSetHolder) {
        Map<String, Definition> removed = new HashMap<>();
        Project project = changeSetHolder.project;
        Path results = context.outputFactory.create(project, OutputTypes.BYTECODE).results();

        Set<Path> removeFromPool = new HashSet<>();
        changeSetHolder.modified
                .stream()
                .filter(entry -> JAVA_SOURCES.matches(entry.relativePath))
                .map(m -> m.relativePath).forEach(removeFromPool::add);
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


        changeSetHolder.created.forEach((entrye) -> {
            processByteCodePath(project, results, entrye.relativePath);
        });

        Set<String> doRecompute = new HashSet<>();

        changeSetHolder.modified
                .stream()
                .filter(entry -> JAVA_SOURCES.matches(entry.relativePath))
                .forEach((entry) -> {
                    Definition oldDefinition = removed.get(entry.relativePath.toString().replace('/', '.').replace(".java", ""));
                    Definition newDefinition = processByteCodePath(project, results, entry.relativePath);
                    newDefinition.getIn().addAll(oldDefinition.getIn());
                    boolean theSame = oldDefinition.generateHash().equals(newDefinition.generateHash());
                    if (!theSame) {
                        doRecompute.addAll(newDefinition.getIn());
                    }
                });


        doRecompute.forEach(in -> {
            //check if this is already in the queue
            Project project1 = context.files.get(in).getProject();
            Path classFile = Paths.get(in.replace(".", "/") + ".java");

            if (!context.current.entrySet().stream().filter(set -> set.getKey().equals(project1))
                    .flatMap(set -> set.getValue().modified.stream())
                    .map(m -> m.absolutePath)
                    .filter(m -> m.equals(classFile))
                    .findFirst()
                    .isPresent()) {
                for (String root : ((com.vertispan.j2cl.build.Project) project1).getSourceRoots()) {
                    Path maybe = Paths.get(root).resolve(classFile);
                    if (Files.exists(maybe)) {
                        ChangeSetEntry entry = new ChangeSetEntry(classFile, maybe);
                        context.current.putIfAbsent((com.vertispan.j2cl.build.Project) project1, new ChangeSetHolder(project1));
                        context.current.get(project1).modified.add(entry);
                        context.addToBuildQueue((com.vertispan.j2cl.build.Project) project1);
                        break;
                    }
                }
            }
            System.out.println("recomputing " + in + " " + project1.getKey());
        });

        //override native.js files
        changeSetHolder.modified
                .stream()
                .filter(entry -> JAVA_SOURCES.matches(entry.absolutePath))
                .filter(entry -> Files.exists(Paths.get(entry.absolutePath.toString().replace(".java", ".native.js"))))
                .forEach(entry -> {
                    Path nativeJs = Paths.get(entry.absolutePath.toString().replace(".java", ".native.js"));
                    Path dist = context.outputFactory.create(changeSetHolder.project, OutputTypes.BYTECODE)
                            .results()
                            .resolve(entry.relativePath.toString()
                                    .replace(".java", ".native.js"));
                    try {
                        Files.copy(nativeJs, dist, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException e) {
                        e.printStackTrace();
                        return; //TODO: handle this
                    }
                });
        return true;

    }

    private Definition processByteCodePath(Project project, Path results, Path path) {
        String classname = path.toString().replace('/', '.').replace(".java", "");
        String classBytecode = path.toString().replace(".java", ".class");
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
