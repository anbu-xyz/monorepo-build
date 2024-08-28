package uk.anbu.maven.monorepo;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class DependencyUpdateAnalyzer {
    private Map<String, String> properties = new HashMap<>();

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

        if (model.getProperties() != null) {
            Map<String, String> propertiesAsStrings = model.getProperties().entrySet().stream()
                        .collect(Collectors.toMap(e -> e.getKey().toString(), e -> e.getValue().toString()));
            properties.putAll(propertiesAsStrings);
        }

        Module module = modules.computeIfAbsent(model.getArtifactId(), k -> new Module(model.getArtifactId(), resolveVersion(model.getVersion())));

        if (model.getModules() != null) {
            for (String subModule : model.getModules()) {
                File subModulePomFile = new File(pomFile.getParentFile(), subModule + File.separator + "pom.xml");
                buildDependencyGraph(subModulePomFile);
            }
        }

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

    private String resolveVersion(String version) {
        if (version == null) {
            return null;
        }
        if (version.startsWith("${") && version.endsWith("}")) {
            String propertyName = version.substring(2, version.length() - 1);
            return properties.getOrDefault(propertyName, version);
        }
        return version;
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

    public void printDependencyTree(List<String> changedModules) {
        Set<String> modulesToUpdate = findModulesToUpdate(changedModules);

        for (String rootModuleName : modules.keySet()) {
            if (!hasParent(rootModuleName)) {
                printModuleTree(rootModuleName, "", true, new HashSet<>(), modulesToUpdate);
            }
        }
    }

    private boolean hasParent(String moduleName) {
        Module module = modules.get(moduleName);
        return module.dependents.stream().anyMatch(m -> modules.containsKey(m.artifactId));
    }

    private void printModuleTree(String moduleName, String prefix, boolean isLast,
                                 Set<String> printed, Set<String> modulesToUpdate) {
        if (printed.contains(moduleName)) {
            System.out.println(prefix + (isLast ? "└── " : "├── ") + moduleName + " (circular reference)");
            return;
        }

        Module module = modules.get(moduleName);
        if (module == null) return;

        printed.add(moduleName);

        String marker = isLast ? "└── " : "├── ";
        String updateMarker = modulesToUpdate.contains(moduleName) ? " [UPDATE]" : "";
        System.out.println(prefix + marker + module.artifactId + " (" + module.version + ")" + updateMarker);

        String newPrefix = prefix + (isLast ? "    " : "│   ");

        for (int i = 0; i < module.dependencies.size(); i++) {
            Module dependency = module.dependencies.get(i);
            boolean lastDependency = (i == module.dependencies.size() - 1);
            printModuleTree(dependency.artifactId, newPrefix, lastDependency, new HashSet<>(printed), modulesToUpdate);
        }
    }
}