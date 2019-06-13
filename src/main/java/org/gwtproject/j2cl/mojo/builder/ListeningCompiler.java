package org.gwtproject.j2cl.mojo.builder;

import java.io.File;
import java.io.IOException;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.j2cl.common.FrontendUtils;
import org.gwtproject.j2cl.mojo.options.Gwt3Options;
import org.apache.maven.project.MavenProject;

/**
 * Simple "dev mode" for j2cl+closure, based on the existing bash script. Lots of room for improvement, this
 * isn't intended to be a proposal, just another experiment on the way to one.
 * <p>
 * Assumptions:
 * o The js-compatible JRE is already on the java classpath (need not be on js). Probably not a good one, but
 * on the other hand, we may want to allow changing out the JRE (or skipping it) in favor of something else.
 * o A JS entrypoint already exists. Probably safe, should get some APT going soon as discussed, at least to
 * try it out.
 * <p>
 * Things about this I like:
 * o Treat both jars and jszips as classpaths (ease of dependency system integrations)
 * o Annotation processors are (or should be) run as an IDE would do, so all kinds of changes are picked up. I
 * think I got it right to pick up generated classes changes too...
 * <p>
 * Not so good:
 * o Not correctly recompiling classes that require it based on dependencies
 * o Not at all convinced my javac wiring is correct
 * o Polling for changes
 */
public class ListeningCompiler {

    private final static Logger LOGGER = Logger.getLogger(ListeningCompiler.class.getName());

    public static void run(Gwt3Options options, List<File> orderedClasspath, File targetPath, Map<String, MavenProject> baseDirProjectMap) throws Exception {
        LOGGER.setLevel(Level.INFO);
        LOGGER.info("Setup SingleCompiler");
        SingleCompiler.setup(options, orderedClasspath, targetPath, baseDirProjectMap);
        FileTime lastModified = FileTime.fromMillis(0);
        LOGGER.info("Begin listening");

        if (!options.getRecompileIfFilesChanged()) {
            lastModified = runJsCompRound(targetPath, lastModified, 0);

            Scanner in = new Scanner(System.in);

            while (true) {
                String line = in.nextLine();
                if (line.length() == 0) {
                    lastModified = runJsCompRound(targetPath, lastModified, 0);
                }
            }
        } else {
            while (true) {
                lastModified = runJsCompRound(targetPath, lastModified, 100);
            }
        }
    }

    private static FileTime runJsCompRound(File targetPath, FileTime lastModified, long delay) throws IOException, InterruptedException {
        // currently polling for changes.
        // block until changes instead? easy to replace with filewatcher, just watch out for java9/osx issues...
        long pollStarted = System.currentTimeMillis();
        FileTime newerThan = lastModified;
        List<FrontendUtils.FileInfo> modifiedJavaFiles = SingleCompiler.getModifiedJavaFiles(newerThan);
        long pollTime = System.currentTimeMillis() - pollStarted;
        // don't replace this until the loop finishes successfully, so we know the last time we started a successful preCompile
        FileTime nextModifiedIfSuccessful = FileTime.fromMillis(System.currentTimeMillis());
        if (modifiedJavaFiles.isEmpty()) {
            Thread.sleep(delay);
            return lastModified;
        }
        try {
            SingleCompiler.preCompile(modifiedJavaFiles, targetPath);
            SingleCompiler.closure();
        } catch (Exception e) {
            LOGGER.severe(e.getMessage());
        }
        LOGGER.info("Recompile of " + modifiedJavaFiles.size() + " source classes finished in " + (System.currentTimeMillis() - nextModifiedIfSuccessful.to(TimeUnit.MILLISECONDS)) + "ms");
        LOGGER.info("poll: " + pollTime + "millis");
        lastModified = nextModifiedIfSuccessful;
        return lastModified;
    }
}
