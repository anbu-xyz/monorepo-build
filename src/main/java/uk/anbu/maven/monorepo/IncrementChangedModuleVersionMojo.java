package uk.anbu.maven.monorepo;

import lombok.SneakyThrows;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.util.List;

@Mojo(name = "increment-changed-module-version", defaultPhase = LifecyclePhase.INITIALIZE)
public class IncrementChangedModuleVersionMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project.basedir}", readonly = true)
    private File basedir;

    @Parameter(property = "project", readonly = true)
    private MavenProject project;

    @Override
    @SneakyThrows
    public void execute() {
        if ("pom".equals(project.getPackaging())) {
            getLog().info("The current project is of type 'pom'.");
            List<String> modules = project.getModules();
            if (modules == null || modules.isEmpty()) {
                getLog().info("The current project does not have submodules.");
                return;
            }
        } else {
            getLog().info("The current project is NOT of type 'pom'.");
            return;
        }

        List<String> changedModules = new GitHelper(getLog(), basedir).changedModuleList();
        if (changedModules == null || changedModules.isEmpty()) {
            getLog().info("No modules changed since last successful build.");
            return;
        }

        for (String module : changedModules) {
            String newVersion = new GitHelper(getLog(), basedir).incrementRevisionOfSubModule(module);
            getLog().info("Incremented version for module " + module + " to " + newVersion);
        }

    }

}