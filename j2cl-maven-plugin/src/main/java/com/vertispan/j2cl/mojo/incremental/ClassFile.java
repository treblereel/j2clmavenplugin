package com.vertispan.j2cl.mojo.incremental;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

public class ClassFile {

    private final String className;

    private final Set<FieldDefinition> fields = new HashSet<>();
    private final Set<MethodDefinition> methods = new HashSet<>();
    private String extendsClass;

    private final Set<String> referencedClasses = new TreeSet<>();

    private final Set<String> implementsInterfaces = new HashSet<>();

    private final Set<ClassFile> nested = new HashSet<>();


    public ClassFile(String className) {
        this.className = className;
    }

    public void addExtendsClass(String clazz) {
        this.extendsClass = clazz;
    }

    public void addImplementsInterface(String clazz) {
        implementsInterfaces.add(clazz);
    }

    public void addField(String name, String type) {
        fields.add(new FieldDefinition(name, type));
    }

    public void addMethod(String name, String returnType, String... parameterType) {
        String params = Arrays.stream(parameterType).collect(Collectors.joining(","));
        MethodDefinition methodDefinition = new MethodDefinition(name, returnType, params);
        methods.add(methodDefinition);
    }

    public Set<ClassFile> getNested() {
        return nested;
    }

    public void addNested(ClassFile nested) {
        this.nested.add(nested);
    }

    String getClassName() {
        return className;
    }

    String hash() {
        HashBuilder builder = new HashBuilder(className);
        builder.addExtendsClass(extendsClass);
        implementsInterfaces.forEach(builder::addImplementsInterface);
        fields.forEach(builder::addField);
        methods.forEach(builder::addMethod);

        return builder.toString();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(className);
        sb.append(" ");
        sb.append("extends:");
        sb.append(extendsClass);
        sb.append("");
        sb.append(hash());
        referencedClasses.stream().forEach(r -> sb.append(" ").append(r));
        return sb.toString();
    }

    public void addReference(String r) {
        referencedClasses.add(r);
    }

    Set<String> getReferencedClasses() {
        return referencedClasses;
    }

    private class FieldDefinition {

        private final String name;
        private final String type;

        private FieldDefinition(String name, String type) {
            this.name = name;
            this.type = type;
        }

        public String getName() {
            return name;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            FieldDefinition that = (FieldDefinition) o;
            return Objects.equals(name, that.name) && Objects.equals(type, that.type);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, type);
        }
    }

    private class MethodDefinition {

        private final String name;
        private final String type;
        private final String params;

        private MethodDefinition(String name, String type, String params) {
            this.name = name;
            this.type = type;
            this.params = params;
        }

        public String getName() {
            return name;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            MethodDefinition that = (MethodDefinition) o;
            return Objects.equals(name, that.name) && Objects.equals(type, that.type) && Objects.equals(params, that.params);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, type, params);
        }
    }

    private static class HashBuilder {

        private List<Integer> hashes = new ArrayList<>();

        private HashBuilder(String className) {
            hashes.add(className.hashCode());
        }

        void addExtendsClass(String clazz) {
            if (clazz != null) {
                hashes.add(clazz.hashCode());
            }
        }

        void addImplementsInterface(String clazz) {
            if(clazz != null) {
                hashes.add(clazz.hashCode());
            }
        }

        private void addField(FieldDefinition field) {
            hashes.add(Objects.hash(field.type, field.name));
        }

        private void addMethod(MethodDefinition method) {
            hashes.add(method.hashCode());
        }

        @Override
        public String toString() {
            return String.valueOf(hashes.hashCode());
        }
    }

}
