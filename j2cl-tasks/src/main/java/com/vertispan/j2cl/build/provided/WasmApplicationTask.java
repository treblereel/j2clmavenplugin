package com.vertispan.j2cl.build.provided;

import com.google.auto.service.AutoService;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.MoreFiles;
import com.google.common.io.Resources;
import com.google.j2cl.common.OutputUtils;
import com.google.j2cl.common.Problems;
import com.google.j2cl.common.SourceUtils;
import com.google.j2cl.common.StringUtils;
import com.google.j2cl.transpiler.J2clTranspiler;
import com.google.j2cl.transpiler.J2clTranspilerOptions;
import com.google.j2cl.transpiler.backend.Backend;
import com.google.j2cl.transpiler.frontend.Frontend;
import com.google.j2cl.transpiler.frontend.jdt.AnnotatedNodeCollector;
import com.vertispan.j2cl.build.task.Config;
import com.vertispan.j2cl.build.task.Input;
import com.vertispan.j2cl.build.task.OutputTypes;
import com.vertispan.j2cl.build.task.Project;
import com.vertispan.j2cl.build.task.TaskContext;
import com.vertispan.j2cl.build.task.TaskFactory;
import org.apache.commons.io.FileUtils;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.*;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

import static com.vertispan.j2cl.build.provided.BytecodeTask.JAVA_SOURCES;
import static java.nio.charset.StandardCharsets.UTF_8;

@AutoService(TaskFactory.class)
public class WasmApplicationTask extends TaskFactory {

    @Override
    public String getOutputType() {
        return OutputTypes.WASM_APP;
    }

    @Override
    public String getTaskName() {
        return "default";
    }

    @Override
    public String getVersion() {
        return "1";
    }

    private final WasmOptScript wasmOptScript = new WasmOptScript();

    @Override
    public Task resolve(Project project, Config config) {
        List<String> declaredEntryPoints = config.getWasmEntryPoints();
        if (!wasmOptScript.runBynarian("--version")) {
            throw new RuntimeException("Binaryen is not available, please install it and make sure it is on your PATH");
        }
        List<Input> sources = scope(project.getDependencies(), com.vertispan.j2cl.build.task.Dependency.Scope.COMPILE)
                .stream()
                .map(inputs(OutputTypes.STRIPPED_SOURCES))
                .collect(Collectors.toList());

        sources.add(input(project, OutputTypes.STRIPPED_SOURCES).filter(JAVA_SOURCES));
        String initialScriptFilename = config.getInitialScriptFilename();
        List<File> extraClasspath = config.getExtraClasspath();

        return new FinalOutputTask() {
            @Override
            public void finish(TaskContext taskContext) throws Exception {
                List<SourceUtils.FileInfo> infos = sources
                        .stream()
                        .map(Input::getFilesAndHashes)
                        .flatMap(Collection::stream)
                        .map(p -> SourceUtils.FileInfo.create(p.getAbsolutePath().toString(), p.getSourcePath().toString()))
                        .collect(Collectors.toUnmodifiableList());

                Set<String> locatedEntryPoints = new WasmEntryPointLocator().preprocessFiles(infos);
                locatedEntryPoints.addAll(declaredEntryPoints);

                Map<String, String> defines = new HashMap<>();
                defines.put("J2WASM_DEBUG", "TRUE");
                defines.put("jre.strictFpToString", "DISABLED");
                defines.put("jre.checkedMode", "ENABLED");
                defines.put("jre.checks.checkLevel", "MINIMAL");
                defines.put("jre.checks.bounds", "AUTO");
                defines.put("jre.checks.api", "AUTO");
                defines.put("jre.checks.numeric", "AUTO");
                defines.put("jre.checks.type", "AUTO");
                defines.put("jre.logging.logLevel", "ALL");
                defines.put("jre.logging.simpleConsoleHandler", "ENABLED");
                defines.put("jre.classMetadata", "SIMPLE");

                Problems problems = new Problems();
                try (OutputUtils.Output output = OutputUtils.initOutput(taskContext.outputPath(), problems)) {
                    J2clTranspilerOptions options = J2clTranspilerOptions.newBuilder()
                            .setOutput(output)
                            .setFrontend(Frontend.JDT)
                            .setBackend(Backend.WASM)
                            .setClasspaths(extraClasspath.stream().map(File::getAbsolutePath).collect(Collectors.toList()))
                            .setSources(infos)
                            .setEmitReadableLibraryInfo(false)
                            .setEmitReadableSourceMap(false)
                            .setDefinesForWasm(ImmutableMap.copyOf(defines))
                            .setNativeSources(ImmutableList.of())
                            .setKotlincOptions(ImmutableList.of())
                            .setWasmEntryPoints(ImmutableList.copyOf(locatedEntryPoints))
                            .setGenerateKytheIndexingMetadata(false)
                            .build(problems);
                    J2clTranspiler.transpile(options, problems);
                } catch (Problems.Exit e) {
                    problems.getErrors().forEach(taskContext.log()::error);
                    throw new RuntimeException("Unable to complete");
                }

                if (problems.hasWarnings()) {
                    problems.getWarnings().forEach(taskContext.log()::error);
                } else {
                    problems.getInfoMessages().forEach(taskContext.log()::info);
                }

                new BynarianTask(taskContext.outputPath(), initialScriptFilename).execute();

                Path webappDirectory = config.getWebappDirectory();
                if (!Files.exists(webappDirectory)) {
                    Files.createDirectories(webappDirectory);
                }

                // copy the output to the webapp directory
                FileUtils.copyFile(taskContext.outputPath().resolve(initialScriptFilename + ".symbols").toFile(), webappDirectory.resolve(initialScriptFilename + ".symbols").toFile());
                FileUtils.copyFile(taskContext.outputPath().resolve(initialScriptFilename + ".wasm").toFile(), webappDirectory.resolve(initialScriptFilename + ".wasm").toFile());
                FileUtils.copyFile(taskContext.outputPath().resolve(initialScriptFilename + ".wasm.map").toFile(), webappDirectory.resolve(initialScriptFilename + ".wasm.map").toFile());
                FileUtils.copyFile(taskContext.outputPath().resolve("imports.txt").toFile(), webappDirectory.resolve("imports.txt").toFile());

                new WasmGoogleModuleLoader(initialScriptFilename, taskContext.outputPath().resolve("imports.txt")).execute(webappDirectory);
            }

            @Override
            public void execute(TaskContext context) {
                //do nothing
            }
        };
    }

    private class BynarianTask {

        private final static String FIRST_ROUND = "--enable-exception-handling --enable-gc --enable-reference-types --enable-sign-ext --enable-strings --enable-nontrapping-float-to-int --enable-bulk-memory --closed-world --traps-never-happen -O3 --gufa -O3 --debuginfo -o %s/%s_intermediate_1.wasm --output-source-map %s/%s_intermediate_1_map %s/module.wat";
        private final static String SECOND_ROUND = "--enable-exception-handling --enable-gc --enable-reference-types --enable-sign-ext --enable-strings --enable-nontrapping-float-to-int --enable-bulk-memory --closed-world --traps-never-happen --partial-inlining-ifs=4 -fimfs=50 --gufa -O3 -O3 -O3 --gufa -O3 --debuginfo -o %s/%s_intermediate_2.wasm --output-source-map %s/%s_intermediate_2_map --input-source-map %s/%s_intermediate_1_map %s/%s_intermediate_1.wasm";
        private final static String THIRD_ROUND = "--enable-exception-handling --enable-gc --enable-reference-types --enable-sign-ext --enable-strings --enable-nontrapping-float-to-int --enable-bulk-memory --closed-world --traps-never-happen --partial-inlining-ifs=4 -fimfs=50 --intrinsic-lowering --gufa -O3 -O3 --output-source-map-url %s/%s.wasm.map --symbolmap=%s/%s.symbols -o %s/%s.wasm --output-source-map %s/%s.wasm.map --input-source-map %s/%s_intermediate_2_map %s/%s_intermediate_2.wasm";

        private final String output;
        private final String name;


        private BynarianTask(Path output, String name) {
            this.output = output.toString();
            this.name = name;
        }

        public void execute() {
            round_1();
            round_2();
            round_3();
        }

        private void round_1() {
            String command = String.format(FIRST_ROUND, output, name, output, name, output);
            if (!WasmApplicationTask.this.wasmOptScript.runBynarian(command)) {
                throw new RuntimeException("Failed to run: wasm-opt " + command);
            }
        }

        private void round_2() {
            String command = String.format(SECOND_ROUND, output, name, output, name, output, name, output, name);
            if (!WasmApplicationTask.this.wasmOptScript.runBynarian(command)) {
                throw new RuntimeException("Failed to run: wasm-opt " + command);
            }
        }

        private void round_3() {
            String command = String.format(THIRD_ROUND, output, name, output, name, output, name, output, name, output, name, output, name);
            if (!WasmApplicationTask.this.wasmOptScript.runBynarian(command)) {
                throw new RuntimeException("Failed to run: wasm-opt " + command);
            }
        }
    }

    private class WasmOptScript {

        private boolean runBynarian(String args) {
            try {
                Process process = Runtime.getRuntime().exec("wasm-opt " + args);
                int exitCode = process.waitFor();
                if (exitCode != 0) {
                    return false;
                }
            } catch (IOException | InterruptedException e) {
                return false;
            }
            return true;
        }
    }

    private class WasmEntryPointLocator {

        private final static String WASM_ENTRY_POINT = "WasmEntryPoint";

        private Set<String> result = new HashSet<>();

        private Set<String> preprocessFiles(
                List<SourceUtils.FileInfo> fileInfos) {
            for (SourceUtils.FileInfo fileInfo : fileInfos) {
                try {
                    String fileContent = MoreFiles.asCharSource(Paths.get(fileInfo.sourcePath()), UTF_8).read();
                    if (fileContent.contains(WASM_ENTRY_POINT)) {
                        processClass(fileContent, fileInfo);
                    }
                } catch (IOException e) {
                    throw new RuntimeException("Unable to complete " + e);
                }
            }
            return result;
        }

        private void processClass(String fileContent, SourceUtils.FileInfo fileInfo) {
            Map<String, String> compilerOptions = new HashMap<>();
            compilerOptions.put(JavaCore.COMPILER_SOURCE, JavaCore.VERSION_9);
            compilerOptions.put(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, JavaCore.VERSION_9);
            compilerOptions.put(JavaCore.COMPILER_COMPLIANCE, JavaCore.VERSION_9);

            ASTParser parser = ASTParser.newParser(AST.JLS9);
            parser.setCompilerOptions(compilerOptions);
            parser.setResolveBindings(false);
            parser.setSource(fileContent.toCharArray());
            CompilationUnit compilationUnit = (CompilationUnit) parser.createAST(null);

            AnnotatedNodeCollector entryPointVisitor = new AnnotatedNodeCollector(WASM_ENTRY_POINT);
            compilationUnit.accept(entryPointVisitor);
            List<ASTNode> entryPoints = entryPointVisitor.getNodes();

            for (ASTNode node : entryPoints) {
                if (node instanceof MethodDeclaration) {
                    MethodDeclaration methodDeclaration = (MethodDeclaration) node;
                    result.add(fileInfo.originalPath().replace(".java", "").replace("/", ".") + "#" + methodDeclaration.getName().getFullyQualifiedName());
                }
            }
        }
    }

    private class WasmGoogleModuleLoader {


        private final Path imports;
        private final String name;

        private WasmGoogleModuleLoader(String name, Path imports) {
            this.name = name;
            this.imports = imports;
        }

        private void execute(Path output) {
            try {
                //goog module
                String templateString = Resources.toString(getClass().getResource("WasmGoogleModule.txt"), UTF_8);
                String _imports = MoreFiles.asCharSource(imports, UTF_8).read();
                templateString = templateString.replace("%MODULE_NAME%", name);
                templateString = templateString.replace("%IMPORTS%", _imports);
                MoreFiles.asCharSink(output.resolve(name + ".module.js"), UTF_8).write(templateString);

                // java wrapper
                String javaWrapperTemplateString = Resources.toString(getClass().getResource("WasmJsInteropWrapper.txt"), UTF_8);
                javaWrapperTemplateString = javaWrapperTemplateString.replace("%PACKAGE%", name.toLowerCase(Locale.ROOT));
                javaWrapperTemplateString = javaWrapperTemplateString.replace("%MODULE_NAME%", StringUtils.capitalize(name));
                javaWrapperTemplateString = javaWrapperTemplateString.replace("%NAMESPACE%", name + ".j2wasm");
                MoreFiles.asCharSink(output.resolve(StringUtils.capitalize(name) + "Loader.java"), UTF_8).write(javaWrapperTemplateString);
            } catch (IOException e) {
                throw new RuntimeException("Unable to complete " + e);
            }
        }
    }
}
