package uk.anbu.maven.monorepo;

import lombok.Builder;
import lombok.Value;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.Settings;

import java.io.File;
import java.util.Properties;

@Value
@Builder
public class MavenEnvironment {
    private String projectName;
    private String projectVersion;
    private String artifactId;
    private String groupId;
    private Properties systemProperties;
    private String home;

    private Settings settings;
    private MavenSession session;
    private MavenProject project;

    public String getLocalRepositoryPath() {
        return getSession().getRequest().getLocalRepositoryPath().getAbsolutePath();
    }

    public File getUserSettingsFile() {
        return getSession().getRequest().getUserSettingsFile();
    }

}