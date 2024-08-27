package uk.anbu.maven.monorepo;

import lombok.SneakyThrows;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.util.List;

import static uk.anbu.maven.monorepo.IncrementChangedModuleVersionMojo.extracted;

@Mojo(name = "list-changed-modules", defaultPhase = LifecyclePhase.INITIALIZE)
public class ListChangedModulesMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project.basedir}", readonly = true)
    private File basedir;

    @Parameter(property = "project", readonly = true)
    private MavenProject project;

    @Override
    @SneakyThrows
    public void execute() {
        String packaging = project.getPackaging();
        List<String> modules = project.getModules();
        String baseDir = basedir.getAbsolutePath();

        if (extracted(getLog(), packaging, modules, baseDir)) return;

        List<String> changedModules = new GitHelper(getLog(), basedir).changedModuleList();
        if (changedModules == null || changedModules.isEmpty()) {
            getLog().info("No modules changed since last successful build.");
            return;
        }

        getLog().info("Changed modules since last successful build:");
        for (String module : changedModules) {
            getLog().info("- " + module);
        }
    }

}