package com.vertispan.j2cl.mojo.incremental.tasks;

import com.vertispan.j2cl.build.WatchService;
import com.vertispan.j2cl.build.task.OutputTypes;
import com.vertispan.j2cl.mojo.incremental.ChangeSetHolder;
import com.vertispan.j2cl.mojo.incremental.Output;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Stack;

public class ClearGeneratedTask extends Task {

    public ClearGeneratedTask(TaskContext context) {
        super(context);
    }

    @Override
    public Boolean apply(ChangeSetHolder changeSetHolder) {
        Path output = context.outputFactory.create(changeSetHolder.project, OutputTypes.BYTECODE).generated();

        if(Files.exists(output)) {
                Stack<File> stack = new Stack<>();
                stack.push(output.toFile());
                while (!stack.isEmpty()) {
                    File file = stack.pop();
                    if (file.isDirectory()) {
                        File[] files = file.listFiles();
                        if (files.length == 0) {
                            file.delete();
                        } else {
                            for (File subFile : files) {
                                stack.push(subFile);
                            }
                        }
                    } else {
                        file.delete();
                    }
                }
        }
        output.toFile().mkdirs();

        return true;
    }

}
