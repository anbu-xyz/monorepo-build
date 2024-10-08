package uk.anbu.maven.monorepo;

import lombok.SneakyThrows;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.Settings;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Mojo(name = "increment-changed-module-version", defaultPhase = LifecyclePhase.INITIALIZE)
public class IncrementChangedModuleVersionMojo extends AbstractMojo {

    @Parameter(property = "project", readonly = true)
    private MavenProject project;

    @Parameter(property = "settings")
    private Settings settings;

    @Component
    private MavenSession session;

    @Parameter(defaultValue = "${project.basedir}", readonly = true)
    private File basedir;

    @Override
    @SneakyThrows
    public void execute() {
        MavenEnvironment mavenEnvironment = MavenEnvironment.builder()
                .projectName(project.getName())
                .projectVersion(project.getVersion())
                .artifactId(project.getArtifactId())
                .session(session)
                .groupId(project.getGroupId())
                .home(System.getProperty("maven.home"))
                .settings(settings)
                .build();

        String packaging = project.getPackaging();
        List<String> modules = project.getModules();
        String baseDir = basedir.getAbsolutePath();

        if (extracted(getLog(), packaging, modules, baseDir)) return;

        List<String> changedModules = new GitHelper(getLog(), basedir).changedModuleList();
        if (changedModules == null || changedModules.isEmpty()) {
            getLog().info("No modules changed since last successful build.");
            return;
        }

        List<String> affectedDependantModules = computeAffectedDependantModules(changedModules);

        Set<String> allModulesToUpdate = new HashSet<>(changedModules);
        allModulesToUpdate.addAll(affectedDependantModules);

        for (String module : allModulesToUpdate) {
            String newVersion = new GitHelper(getLog(), basedir).incrementRevisionOfSubModule(module, mavenEnvironment);
            getLog().info("Incremented version for module " + module + " to " + newVersion);
        }

    }

    public static boolean extracted(Log log, String packaging, List<String> modules, String baseDir) {
        if ("pom".equals(packaging)) {
            log.info("The current project is of type 'pom'.");
            if (modules == null || modules.isEmpty()) {
                log.info("The current project does not have submodules.");
                return true;
            }
        } else {
            log.info("The current project is NOT of type 'pom'.");
            return true;
        }

        if (!System.getProperty("user.dir").equals(baseDir)) {
            log.info(String.format("Base directory %s is not same as current directory %s", baseDir, System.getProperty("user.dir")));
            return true;
        }
        return false;
    }

    private List<String> computeAffectedDependantModules(List<String> changedModules) {
        try {
            DependencyUpdateAnalyzer analyzer = new DependencyUpdateAnalyzer();
            analyzer.buildDependencyGraph(new File(basedir, "pom.xml").getAbsolutePath());
            Set<DependencyUpdateAnalyzer.Module> affectedModules = analyzer.findModulesToUpdate(changedModules);

            getLog().info("Affected dependent modules: " + affectedModules);
            return affectedModules.stream().map(DependencyUpdateAnalyzer.Module::artifactId).collect(Collectors.toList());
        } catch (Exception e) {
            throw new RuntimeException("Error computing affected dependent modules", e);
        }
    }
}