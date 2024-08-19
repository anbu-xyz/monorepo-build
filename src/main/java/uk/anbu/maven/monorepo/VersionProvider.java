package uk.anbu.maven.monorepo;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;

public interface VersionProvider {
    String getVersion(String command, Log log) throws MojoExecutionException;
}
