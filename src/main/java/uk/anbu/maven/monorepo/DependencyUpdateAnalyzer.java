package uk.anbu.maven.monorepo;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;

public class DependencyUpdateAnalyzer {

    private static class Module {
        String artifactId;
        String version;
        List<Module> dependencies = new ArrayList<>();
        List<Module> dependents = new ArrayList<>();

        Module(String artifactId, String version) {
            this.artifactId = artifactId;
            this.version = version;
        }
    }

    private Map<String, Module> modules = new HashMap<>();

    public void buildDependencyGraph(String rootPomPath) throws IOException, XmlPullParserException {
        buildDependencyGraph(new File(rootPomPath));
    }

    private void buildDependencyGraph(File pomFile) throws IOException, XmlPullParserException {
        MavenXpp3Reader reader = new MavenXpp3Reader();
        Model model = reader.read(new FileReader(pomFile));

        Module module = modules.computeIfAbsent(model.getArtifactId(), k -> new Module(model.getArtifactId(), model.getVersion()));

        // Process sub-modules
        if (model.getModules() != null) {
            for (String subModule : model.getModules()) {
                File subModulePomFile = new File(pomFile.getParentFile(), subModule + File.separator + "pom.xml");
                buildDependencyGraph(subModulePomFile);
            }
        }

        // Process dependencies
        if (model.getDependencies() != null) {
            for (Dependency dependency : model.getDependencies()) {
                Module dependencyModule = modules.get(dependency.getArtifactId());
                if (dependencyModule != null) {
                    module.dependencies.add(dependencyModule);
                    dependencyModule.dependents.add(module);
                }
            }
        }
    }

    public Set<String> findModulesToUpdate(List<String> changedModules) {
        Set<String> modulesToUpdate = new HashSet<>();
        Queue<Module> queue = new LinkedList<>();

        for (String changedModule : changedModules) {
            Module module = modules.get(changedModule);
            if (module != null) {
                queue.add(module);
            }
        }

        while (!queue.isEmpty()) {
            Module currentModule = queue.poll();
            for (Module dependent : currentModule.dependents) {
                if (modulesToUpdate.add(dependent.artifactId)) {
                    queue.add(dependent);
                }
            }
        }

        return modulesToUpdate;
    }
}