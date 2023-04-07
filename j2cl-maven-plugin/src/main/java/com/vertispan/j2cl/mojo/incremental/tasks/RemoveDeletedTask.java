package com.vertispan.j2cl.mojo.incremental.tasks;

import com.vertispan.j2cl.build.WatchService;
import com.vertispan.j2cl.build.task.OutputTypes;
import com.vertispan.j2cl.mojo.incremental.ChangeSetHolder;
import com.vertispan.j2cl.mojo.incremental.Definition;
import com.vertispan.j2cl.mojo.incremental.Output;
import com.vertispan.j2cl.mojo.incremental.tasks.Task;
import com.vertispan.j2cl.mojo.incremental.tasks.TaskContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class RemoveDeletedTask extends Task {

    public RemoveDeletedTask(TaskContext context) {
        super(context);
    }

    @Override
    public void accept(ChangeSetHolder changeSetHolder) {
        if (!changeSetHolder.deleted.isEmpty()) {
            try {
                Output byteCodeOutput = context.outputFactory.create(changeSetHolder.project, OutputTypes.BYTECODE);
                Output outputStrippedSources = context.outputFactory.create(changeSetHolder.project, OutputTypes.STRIPPED_SOURCES);
                Output outputTranspiledJs = context.outputFactory.create(changeSetHolder.project, OutputTypes.TRANSPILED_JS);
                Output outputBundledJs = context.outputFactory.create(changeSetHolder.project, OutputTypes.BUNDLED_JS);

                for (Path path : changeSetHolder.deleted) {

                    System.out.println("deleteFile " + path);

                    Path javaFileByteCodeOutput = byteCodeOutput.results().resolve(path);
                    //replace .java with .class
                    String fileName = path.getFileName().toString().replace(".java", "");
                    String fqdn = fileName.replace("/", ".");
                    Definition definition = context.files.remove(fqdn); //TODO

                    String className = fileName + ".class";
                    String classImpl = fileName + ".impl.java.js";
                    String classJavaJs = fileName + ".java.js";
                    String classJsMap = fileName + ".js.map";


                    Files.deleteIfExists(javaFileByteCodeOutput);
                    Files.deleteIfExists(javaFileByteCodeOutput.resolveSibling(className));

                    Path javaFileStrippedSourcesOutput = outputStrippedSources.results().resolve(path);
                    Files.deleteIfExists(javaFileStrippedSourcesOutput);

                    Path javaFileTranspiledJsOutput = outputTranspiledJs.results().resolve(path);

                    Files.deleteIfExists(javaFileTranspiledJsOutput);
                    Files.deleteIfExists(javaFileTranspiledJsOutput.resolveSibling(classImpl));
                    Files.deleteIfExists(javaFileTranspiledJsOutput.resolveSibling(classJavaJs));
                    Files.deleteIfExists(javaFileTranspiledJsOutput.resolveSibling(classJsMap));

                    Path javaFileOutputBundledJs = outputBundledJs.results().resolve("sources").resolve(path);

                    Files.deleteIfExists(javaFileOutputBundledJs);
                    Files.deleteIfExists(javaFileOutputBundledJs.resolveSibling(classImpl));
                    Files.deleteIfExists(javaFileOutputBundledJs.resolveSibling(classJavaJs));
                    Files.deleteIfExists(javaFileOutputBundledJs.resolveSibling(classJsMap));
                }
            } catch (IOException e) {
                e.printStackTrace();
                throw new RuntimeException(e);
            }
        }
    }
}
