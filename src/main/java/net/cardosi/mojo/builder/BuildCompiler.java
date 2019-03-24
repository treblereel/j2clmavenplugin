package net.cardosi.mojo.builder;

import java.io.File;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.j2cl.frontend.FrontendUtils;
import net.cardosi.mojo.options.Gwt3Options;
import org.apache.maven.project.MavenProject;

/**
 * @author Dmitrii Tikhomirov
 * Created by treblereel 3/24/19
 */
public class BuildCompiler {

    private final static Logger LOGGER = Logger.getLogger(ListeningCompiler.class.getName());

    public static void build(Gwt3Options options, List<File> orderedClasspath, File targetPath, Map<String, MavenProject> baseDirProjectMap) throws Exception {
        LOGGER.setLevel(Level.INFO);
        LOGGER.info("Setup SingleCompiler");
        SingleCompiler.setup(options, orderedClasspath, targetPath, baseDirProjectMap);
        FileTime lastModified = FileTime.fromMillis(0);
        LOGGER.info("Begin build");
        long pollStarted = System.currentTimeMillis();
        FileTime newerThan = lastModified;
        List<FrontendUtils.FileInfo> modifiedJavaFiles = SingleCompiler.getModifiedJavaFiles(newerThan);
        long pollTime = System.currentTimeMillis() - pollStarted;
        try {
            SingleCompiler.preCompile(modifiedJavaFiles, targetPath);
            SingleCompiler.closure();
        } catch (Exception e) {
            LOGGER.severe(e.getMessage());
        }
        LOGGER.info("poll: " + pollTime + "millis");
    }
}
