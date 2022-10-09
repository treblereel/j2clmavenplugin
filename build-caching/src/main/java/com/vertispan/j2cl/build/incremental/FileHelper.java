package com.vertispan.j2cl.build.incremental;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;

class FileHelper {

    static final String IMPL = ".impl.java.js";
    static final String JAVA = ".java";
    static final String CLASS = ".class";
    static final String JAVA_JS = ".java.js";
    static final String JS_MAP = ".js.map";
    static final String NATIVE_JS = ".native_js";
    static final String JAVA_NATIVE_JS = ".native.js";

    private final String[] extensions = new String[]{JAVA, JAVA_JS, IMPL, JS_MAP, NATIVE_JS};

    private final Path output;

    FileHelper(Path output) {
        this.output = output;
    }

    void copyFolder(Path source) {
        try {
            Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir,
                                                         BasicFileAttributes attrs) throws IOException {
                    Path resolve = output.resolve(source.relativize(dir));
                    if (Files.notExists(resolve)) {
                        Files.createDirectories(resolve);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                        throws IOException {
                    Path resolve = output.resolve(source.relativize(file));
                    Files.copy(file, resolve, StandardCopyOption.REPLACE_EXISTING);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    void deleteFiles(Set<Path> changeSet) {
        for (Path path : changeSet) {
            String changed = path.toString();
            try {
                String fileName = path.getFileName().toString();
                if (fileName.endsWith(JAVA_NATIVE_JS) || fileName.endsWith(JAVA)) {
                    // always delete .java .native pairs, regardless which was changed
                    int suffixLength = changed.endsWith(JAVA_NATIVE_JS) ? 10 : 5;
                    String firstPart = changed.substring(0, changed.length() - suffixLength);
                    deleteFile(firstPart);
                    //TODO we can do better than this, it's could be to expensive to find inner classes by pattern {firstPart}$*.*
                    Path dir = output.resolve(changed).getParent();
                    String innerFilePattern = fileName.substring(0, path.getFileName().toString().length() - suffixLength) + "$";
                    File[] inner = dir.toFile()
                                        .listFiles(file -> file.getName().startsWith(innerFilePattern));
                    if(inner != null) {
                        for (File file : inner) {
                            file.delete();
                        }
                    }
                } else {
                    // just standard delete for anything else
                    Path changedPath = output.resolve(changed);
                    Files.delete(changedPath);
                }
            } catch (IOException e) {
                e.printStackTrace();
                throw new RuntimeException(e);
            }
        }
    }

    private void deleteFile(String firstPart) throws IOException {
        for (String extension : extensions) {
            Path toDelete = output.resolve(firstPart + extension);
            Files.deleteIfExists(toDelete);
        }
    }

}
