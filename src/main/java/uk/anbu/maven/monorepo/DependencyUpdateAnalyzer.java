package uk.anbu.maven.monorepo;

import lombok.SneakyThrows;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.ModelBase;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;

import java.io.File;
import java.io.FileReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public class DependencyUpdateAnalyzer {

    public record Module(String groupId, String artifactId) {
        public Module {
            if(groupId == null || artifactId == null) {
                throw new IllegalArgumentException("groupId and artifactId cannot be null");
            }
        }
        public String toString() {
            return artifactId;
        }
    }

    private record SubModuleInfo(Module subModule, Set<Module> dependencies, Set<Module> children) {}

    private Map<Module, SubModuleInfo> modules = new HashMap<>();

    public void buildDependencyGraph(String rootPomPath) {
        buildDependencyGraph(new File(rootPomPath));
        printDependencyList();
        System.out.println("---------".repeat(10));
        replaceChildModuleDependenciesWithParentModuleDependencies();
        printDependencyList();
    }

    private void replaceChildModuleDependenciesWithParentModuleDependencies() {
        modules.forEach((moduleName, moduleInfo) -> {
            List<Module> toBeRemoved = new ArrayList<>();
            List<Module> toBeAdded = new ArrayList<>();
            for (var child : moduleInfo.dependencies) {
                var parent = findModuleContainingChild(child);
                if (parent.isPresent()) {
                    toBeRemoved.add(child);
                    toBeAdded.add(parent.get());
                }
            }
            moduleInfo.dependencies.addAll(toBeAdded);
            moduleInfo.dependencies.removeAll(toBeRemoved);
        });
    }

    @SneakyThrows
    private void buildDependencyGraph(File pomFile) {
        MavenXpp3Reader reader = new MavenXpp3Reader();
        Model topLevelPomModel = reader.read(new FileReader(pomFile));

        if (topLevelPomModel.getModules() != null) {
            for (String subModule : topLevelPomModel.getModules()) {
                File subModulePomFile = new File(pomFile.getParentFile(), subModule + File.separator + "pom.xml");
                Model subModuleModel = reader.read(new FileReader(subModulePomFile));

                var dependencies = findAllDependencies(subModulePomFile, topLevelPomModel);
                // if submodule is a pom find all children
                Set<Module> children = new HashSet<>();
                if (subModuleModel.getPackaging().equals("pom")) {
                    children = findAllChildren(subModulePomFile, dependencies);
                }
                var groupId = getGroupId(subModuleModel);
                modules.put(new Module(groupId, subModuleModel.getArtifactId())
                        , new SubModuleInfo(new Module(groupId, subModuleModel.getArtifactId())
                                , dependencies, children));
            }
        }
    }

    private static String getGroupId(Model model) {
        var groupId = model.getGroupId();
        if (groupId == null) {
            groupId = model.getParent().getGroupId();
        }
        return groupId;
    }

    @SneakyThrows
    private Set<Module> findAllChildren(File subModuleModel, Set<Module> dependencies) {
        var subModuleDirectory = subModuleModel.getParentFile();
        // walk down the directory tree to find all pom.xml files using  Files.walk()
        var subModulePoms = Files.walk(subModuleDirectory.toPath())
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().equals("pom.xml"))
                .map(Path::toFile)
                .toList();

        Set<Module> subModuleModels = new HashSet<>();
        for (var subModulePom : subModulePoms) {
            MavenXpp3Reader readerx = new MavenXpp3Reader();
            var subModuleModelx = readerx.read(new FileReader(subModulePom));
            var groupId = getGroupId(subModuleModelx);
            var child = new Module(groupId, subModuleModelx.getArtifactId());
            subModuleModels.add(child);
        }
        return subModuleModels;
    }

    private void printDependencyList() {
        modules.forEach((moduleName, moduleInfo) -> {
            System.out.println(moduleName + ":");
            System.out.println("  Dependencies: ");
            for (Module dependency : moduleInfo.dependencies) {
                System.out.println("      -> " + dependency.artifactId);
            }
            System.out.println("  Children: ");
            for (Module child : moduleInfo.children) {
                System.out.println("      -> " + child.artifactId);
            }
        });
    }

    private Optional<Module> findModuleContainingChild(Module child) {
        for (var entry : modules.entrySet()) {
            if(entry.getValue().children.contains(child)) {
                return Optional.of(entry.getKey());
            }
        }
        return Optional.empty();
    }

    @SneakyThrows
    private Set<Module> findAllDependencies(File subModulePomFile, Model topLevelPomModel) {
        var reader = new MavenXpp3Reader();
        var subModuleModel = reader.read(new FileReader(subModulePomFile));
        if (subModuleModel.getPackaging().equals("pom")) {
            var subModuleDirectory = subModulePomFile.getParentFile();
            // walk down the directory tree to find all pom.xml files using  Files.walk()
            var subModulePoms = Files.walk(subModuleDirectory.toPath())
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals("pom.xml"))
                    .map(Path::toFile)
                    .toList();

            List<Model> subModuleModels = new ArrayList<>();
            for (var subModulePom : subModulePoms) {
                MavenXpp3Reader readerx = new MavenXpp3Reader();
                subModuleModels.add(readerx.read(new FileReader(subModulePom)));
            }
            var x = subModuleModels.stream()
                    .map(ModelBase::getDependencies)
                    .filter(Objects::nonNull)
                    .map(dependencies -> dependencies.stream()
                            .map(DependencyUpdateAnalyzer::getModule))
                    .toList();
            // filter out only those that match top level pom groupId
            return x.stream()
                    .flatMap(Function.identity())
                    .filter(module -> module.groupId.startsWith(topLevelPomModel.getGroupId()))
                    .collect(Collectors.toSet());
        } else {
            return subModuleModel.getDependencies().stream()
                    .map(DependencyUpdateAnalyzer::getModule)
                    .filter(module -> module.groupId.startsWith(topLevelPomModel.getGroupId()))
                    .collect(Collectors.toSet());
        }
    }

    private static Module getModule(Dependency dependency) {
        return new Module(dependency.getGroupId(), dependency.getArtifactId());
    }

    public Set<Module> findModulesToUpdate(List<String> changedModules) {
        Set<Module> modulesToUpdate = new HashSet<>();
        Queue<Module> queue = new LinkedList<>();

        // Convert changedModules strings to Module objects and add them to the queue
        for (String changedModule : changedModules) {
            modules.keySet().stream()
                    .filter(module -> module.artifactId().equals(changedModule))
                    .findFirst()
                    .ifPresent(queue::add);
        }

        while (!queue.isEmpty()) {
            Module currentModule = queue.poll();
            modulesToUpdate.add(currentModule);

            // Find all modules that depend on the current module
            for (Map.Entry<Module, SubModuleInfo> entry : modules.entrySet()) {
                if (entry.getValue().dependencies().contains(currentModule) && !modulesToUpdate.contains(entry.getKey())) {
                    queue.add(entry.getKey());
                }
            }

            // Add parent modules if the current module is a child
            findParentModule(currentModule).ifPresent(parent -> {
                if (!modulesToUpdate.contains(parent)) {
                    queue.add(parent);
                }
            });
        }

        return modulesToUpdate;
    }

    private Optional<Module> findParentModule(Module childModule) {
        return modules.entrySet().stream()
                .filter(entry -> entry.getValue().children().contains(childModule))
                .map(Map.Entry::getKey)
                .findFirst();
    }
}