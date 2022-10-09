package com.vertispan.j2cl.build.incremental;

import com.vertispan.j2cl.build.Input;
import com.vertispan.j2cl.build.Project;
import com.vertispan.j2cl.build.task.Dependency;
import com.vertispan.j2cl.build.task.OutputTypes;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtField;
import javassist.CtMethod;
import javassist.NotFoundException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

class BuildMapGenerator {

    private final LastSuccessfulBuildTracker tracker;

    BuildMapGenerator(LastSuccessfulBuildTracker tracker) {
        this.tracker = tracker;
    }

    void generate(Input input, Path path) {
        Path classOutputDir = path.resolve("results");

        try {
            ClassPool pool = new ClassPool(null);
            pool.appendSystemPath();
            pool.appendClassPath(classOutputDir.toString());

            for (Dependency dependency : input.getProject().getDependencies()) {
                Path lastSuccessfulTaskDir = tracker.getLastSuccessfulTaskDir((Project) dependency.getProject(), OutputTypes.BYTECODE)
                        .orElseThrow(() -> new RuntimeException("No last successful task dir for " + dependency.getProject()));
                pool.appendClassPath(lastSuccessfulTaskDir.resolve("results").toString());
            }

            Set<CtClass> classes = new TreeSet<>(Comparator.comparing(CtClass::getName));

            Files.walk(classOutputDir)
                    .filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().endsWith(".java"))
                    .map(f -> classOutputDir
                            .relativize(f)
                            .toString()
                            .replace(".java", "")
                            .replace("/", "."))
                    .forEach(className -> {
                        try {
                            CtClass ctClass = pool.getCtClass(className);
                            classes.add(ctClass);
                            classes.addAll(Arrays.asList(ctClass.getNestedClasses()));
                        } catch (NotFoundException e) {
                        }
                    });
            writeBuildMap(path, classes);
        } catch (IOException | NotFoundException e) {
            throw new RuntimeException("Failed to walk class output dir: " + classOutputDir, e);
        }
    }

    private void writeBuildMap(Path path, Set<CtClass> classes) {
        try {
            StringBuffer sb = new StringBuffer();
            for (CtClass aClass : classes) {
                ClassFile classFile = new ClassFile(aClass.getName());

                for (CtField field : aClass.getFields()) {
                    classFile.addField(field.getName(), field.getType().getName());
                }

                for (CtMethod method : aClass.getDeclaredMethods()) {
                    String params = Arrays.stream(method.getParameterTypes()).map(CtClass::getName).collect(Collectors.joining(","));
                    classFile.addMethod(method.getName(), method.getReturnType().getName(), params);
                }

                classFile.addExtendsClass(aClass.getSuperclass().getName());
                Arrays.stream(aClass.getInterfaces()).forEach(i -> classFile.addImplementsInterface(i.getName()));

                aClass.getRefClasses().stream()
                        .filter(r -> !r.startsWith("java."))
                        .filter(r -> !r.equals(aClass.getName()))
                        .forEach(classFile::addReference);

                sb.append(classFile).append(System.lineSeparator());
            }


            byte[] strToBytes = sb.toString().getBytes();
            Files.write(path.resolve("buildMap.dat"), strToBytes);
        } catch (IOException | NotFoundException e) {
            throw new RuntimeException("Failed to write build map", e);
        }
    }
}
