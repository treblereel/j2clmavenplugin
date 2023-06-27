package com.vertispan.j2cl.build.provided;

import com.google.auto.service.AutoService;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.MoreFiles;
import com.google.common.io.Resources;
import com.google.j2cl.common.OutputUtils;
import com.google.j2cl.common.Problems;
import com.google.j2cl.common.SourceUtils;
import com.google.j2cl.transpiler.J2clTranspiler;
import com.google.j2cl.transpiler.J2clTranspilerOptions;
import com.google.j2cl.transpiler.backend.Backend;
import com.google.j2cl.transpiler.frontend.Frontend;
import com.google.j2cl.transpiler.frontend.jdt.AnnotatedNodeCollector;
import com.google.javascript.jscomp.CompilationLevel;
import com.google.javascript.jscomp.CompilerOptions;
import com.google.javascript.jscomp.DependencyOptions;
import com.vertispan.j2cl.build.task.Config;
import com.vertispan.j2cl.build.task.Input;
import com.vertispan.j2cl.build.task.OutputTypes;
import com.vertispan.j2cl.build.task.Project;
import com.vertispan.j2cl.build.task.TaskContext;
import com.vertispan.j2cl.build.task.TaskFactory;
import com.vertispan.j2cl.tools.Closure;
import org.apache.commons.io.FileUtils;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

import static com.vertispan.j2cl.build.provided.BytecodeTask.JAVA_SOURCES;
import static com.vertispan.j2cl.build.provided.ClosureTask.EXTERNS;
import static com.vertispan.j2cl.build.provided.ClosureTask.PLAIN_JS_SOURCES;
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

        List<Input> bootstrap = scope(project.getDependencies()
                        .stream()
                        .filter(d -> d.getProject().isJsZip())
                        .collect(Collectors.toSet()),
                com.vertispan.j2cl.build.task.Dependency.Scope.BOTH)
                .stream()
                .map(inputs(OutputTypes.BYTECODE))
                .map(i -> i.filter(PLAIN_JS_SOURCES, EXTERNS))
                .collect(Collectors.toList());

        sources.add(input(project, OutputTypes.STRIPPED_SOURCES).filter(JAVA_SOURCES));
        String initialScriptFilename = config.getInitialScriptFilename().contains(".js") ?
                config.getInitialScriptFilename().substring(0, config.getInitialScriptFilename().lastIndexOf(".js")) :
                config.getInitialScriptFilename();
        List<File> extraClasspath = config.getExtraClasspath();
        Map<String, String> configDefines = config.getDefines();
        DependencyOptions.DependencyMode dependencyMode = DependencyOptions.DependencyMode.valueOf(config.getDependencyMode());
        Collection<String> externs = config.getExterns();
        String env = config.getEnv();

        return new FinalOutputTask() {
            @Override
            public void finish(TaskContext taskContext) throws Exception {
                Path webappDirectory = config.getWebappDirectory();
                if (!Files.exists(webappDirectory)) {
                    Files.createDirectories(webappDirectory);
                }
                // copy the output to the webapp directory
                FileUtils.copyFile(taskContext.outputPath().resolve(initialScriptFilename + ".symbols").toFile(), webappDirectory.resolve(initialScriptFilename + ".symbols").toFile());
                FileUtils.copyFile(taskContext.outputPath().resolve(initialScriptFilename + ".wasm").toFile(), webappDirectory.resolve(initialScriptFilename + ".wasm").toFile());
                FileUtils.copyFile(taskContext.outputPath().resolve(initialScriptFilename + ".wasm.map").toFile(), webappDirectory.resolve(initialScriptFilename + ".wasm.map").toFile());

                // task isn't finished but .testsuite already generated
                Collection<SourceUtils.FileInfo> testSuites = FileUtils.listFiles(webappDirectory.toFile(), new String[]{"testsuite"}, true)
                        .stream().map(file -> SourceUtils.FileInfo.create(webappDirectory.relativize(file.toPath()).toString(), file.getAbsolutePath()))
                        .collect(Collectors.toUnmodifiableList());

                new WasmGoogleModuleLoader(initialScriptFilename, taskContext.outputPath().resolve("imports.txt")).execute(webappDirectory);

                //is there a better way to do this? check we run TestMojo
                if (!testSuites.isEmpty()) {
                    new WasmJUnitTestGenerator(taskContext, initialScriptFilename, webappDirectory, testSuites, bootstrap, configDefines, dependencyMode, externs, env).execute();
                }

            }

            @Override
            public void execute(TaskContext taskContext) {
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

        private final String[] TEST_ADAPTER_METHOD_PREFIX = {"test", "setUp", "tearDown"};

        private Set<String> result = new HashSet<>();

        private Set<String> preprocessFiles(
                List<SourceUtils.FileInfo> fileInfos) {
            for (SourceUtils.FileInfo fileInfo : fileInfos) {

                try {
                    String fileContent = MoreFiles.asCharSource(Paths.get(fileInfo.sourcePath()), UTF_8).read();
                    if (fileContent.contains(WASM_ENTRY_POINT)) {
                        processWasmEntryPoint(fileContent);
                        // we need to collect ".*_Adapter#test.*", ".*_Adapter#setUp.*", "*_Adapter#tearDown.*" from tests
                    } else if (fileInfo.originalPath().startsWith("javatests/") && fileInfo.originalPath().endsWith("_Adapter.java")) {
                        processTestAdapter(fileContent);
                    }
                } catch (IOException e) {
                    throw new RuntimeException("Unable to complete " + e);
                }
            }
            return result;
        }

        private void processTestAdapter(String fileContent) {
            CompilationUnit compilationUnit = getCompilationUnit(fileContent);
            for (TypeDeclaration type : (List<TypeDeclaration>) compilationUnit.types()) {
                for (MethodDeclaration method : type.getMethods()) {
                    int flags = method.getModifiers();
                    if ((flags & Flags.AccStatic) != 0 && (flags & Flags.AccPublic) != 0 && method.parameters().isEmpty()) {
                        String methodName = method.getName().getFullyQualifiedName();
                        for (String prefix : TEST_ADAPTER_METHOD_PREFIX) {
                            if (methodName.startsWith(prefix)) {
                                result.add(exportFromMethod(compilationUnit.getPackage(), method));
                                break;
                            }
                        }
                    }
                }
            }
        }

        private void processWasmEntryPoint(String fileContent) {
            CompilationUnit compilationUnit = getCompilationUnit(fileContent);

            AnnotatedNodeCollector entryPointVisitor = new AnnotatedNodeCollector(WASM_ENTRY_POINT);
            compilationUnit.accept(entryPointVisitor);
            List<ASTNode> entryPoints = entryPointVisitor.getNodes();

            for (ASTNode node : entryPoints) {
                if (node instanceof MethodDeclaration) {
                    result.add(exportFromMethod(compilationUnit.getPackage(), (MethodDeclaration) node));
                }
            }
        }

        private String exportFromMethod(PackageDeclaration packageDeclaration, MethodDeclaration methodDeclaration) {
            TypeDeclaration parent = (TypeDeclaration) methodDeclaration.getParent();
            return packageDeclaration.getName() + "." + parent.getName() + "#" + methodDeclaration.getName().getFullyQualifiedName();
        }

        private CompilationUnit getCompilationUnit(String fileContent) {
            Map<String, String> compilerOptions = new HashMap<>();
            compilerOptions.put(JavaCore.COMPILER_SOURCE, JavaCore.VERSION_9);
            compilerOptions.put(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, JavaCore.VERSION_9);
            compilerOptions.put(JavaCore.COMPILER_COMPLIANCE, JavaCore.VERSION_9);

            ASTParser parser = ASTParser.newParser(AST.JLS9);
            parser.setCompilerOptions(compilerOptions);
            parser.setResolveBindings(false);
            parser.setSource(fileContent.toCharArray());
            return (CompilationUnit) parser.createAST(null);
        }
    }

    private class WasmGoogleModuleLoader {


        private final Path imports;
        private final String name;

        private WasmGoogleModuleLoader(String name, Path imports) {
            this.name = name.replace(".js", "");
            this.imports = imports;
        }

        private void execute(Path output) {
            try {
                //goog module
                String templateString = Resources.toString(getClass().getResource("WasmGoogleModule.txt"), UTF_8);
                String _imports = MoreFiles.asCharSource(imports, UTF_8).read();
                templateString = templateString.replace("%MODULE_NAME%", name.replace("-", "."));
                templateString = templateString.replace("%IMPORTS%", _imports);
                MoreFiles.asCharSink(output.resolve(name + ".module.js"), UTF_8).write(templateString);

                // java wrapper
/*                String javaWrapperTemplateString = Resources.toString(getClass().getResource("WasmJsInteropWrapper.txt"), UTF_8);
                javaWrapperTemplateString = javaWrapperTemplateString.replace("%PACKAGE%", name.toLowerCase(Locale.ROOT));
                javaWrapperTemplateString = javaWrapperTemplateString.replace("%MODULE_NAME%", StringUtils.capitalize(name));
                javaWrapperTemplateString = javaWrapperTemplateString.replace("%NAMESPACE%", name + ".j2wasm");
                MoreFiles.asCharSink(output.resolve(StringUtils.capitalize(name) + "Loader.java"), UTF_8).write(javaWrapperTemplateString);*/
            } catch (IOException e) {
                throw new RuntimeException("Unable to complete " + e);
            }
        }
    }

    private class WasmJUnitTestGenerator {

        private final String initialScriptFilename;
        private final Collection<SourceUtils.FileInfo> testSuites;
        private final Path webappDir;
        private final Map<String, List<String>> bootstrap;

        private final Closure closureCompiler;
        private final Map<String, String> configDefines;
        private final DependencyOptions.DependencyMode dependencyMode;
        private final Collection<String> externs;
        private final String env;


        public WasmJUnitTestGenerator(TaskContext context, String initialScriptFilename, Path webappDir, Collection<SourceUtils.FileInfo> testSuites,
                                      List<Input> bootstrap, Map<String, String> configDefines, DependencyOptions.DependencyMode dependencyMode,
                                      Collection<String> externs, String env) {
            this.closureCompiler = new Closure(context);
            this.initialScriptFilename = initialScriptFilename.substring(0, initialScriptFilename.indexOf("-"));
            this.testSuites = testSuites;
            this.webappDir = webappDir;
            this.bootstrap = Closure.mapFromInputs(bootstrap);
            this.configDefines = configDefines;
            this.dependencyMode = dependencyMode;
            this.externs = externs;
            this.env = env;
        }

        private void execute() throws IOException {
            for (SourceUtils.FileInfo suite : testSuites) {
                String fileName = suite.sourcePath().substring(suite.sourcePath().lastIndexOf("/") + 1);
                String testName = fileName.substring(0, fileName.lastIndexOf("."));
                String packageName = suite.sourcePath().substring(0, suite.sourcePath().lastIndexOf("/")).replace("/", ".");
                String finalName = initialScriptFilename + "-" + packageName + "." + testName;

                String scriptName = finalName + ".js";
                String wasmModule = finalName.replace("-", ".") + ".j2wasm";
                String wasmFile   = finalName + ".wasm";
                String googModule = finalName + ".goog.js";
                String wasmModuleFilename = finalName + ".module.js";

                String script = new String(Files.readAllBytes(Paths.get(suite.originalPath())));
                script = script.replace("REPLACEMENT_BUILD_PATH_PLACEHOLDER", wasmFile);
                script = script.replace("REPLACEMENT_MODULE_NAME_PLACEHOLDER", wasmModule);
                Files.write(webappDir.resolve(googModule), script.getBytes());

                compile(scriptName, wasmModuleFilename, googModule);
            }
        }

        private void compile(String compiledJs, String wasmModule, String googModule) {
            Map<String, List<String>> bootstrap = new HashMap<>(this.bootstrap);
            bootstrap.put(webappDir.toString(), List.of(googModule, wasmModule));

            boolean success = closureCompiler.compile(
                    CompilationLevel.WHITESPACE_ONLY,
                    dependencyMode,
                    CompilerOptions.LanguageMode.ECMASCRIPT_2021,
                    bootstrap,
                    null,
                    Collections.emptyList(),
                    configDefines,
                    externs,
                    Optional.empty(),
                    true,
                    true,
                    false,
                    false,
                    env,
                    webappDir.resolve(compiledJs).toString()
            );

            if (!success) {
                throw new IllegalStateException("Closure Compiler failed, check log for details");
            }
        }
    }
}
