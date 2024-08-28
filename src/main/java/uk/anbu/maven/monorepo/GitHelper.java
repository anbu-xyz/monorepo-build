package uk.anbu.maven.monorepo;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import lombok.SneakyThrows;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.shared.invoker.DefaultInvocationRequest;
import org.apache.maven.shared.invoker.DefaultInvoker;
import org.apache.maven.shared.invoker.InvocationRequest;
import org.apache.maven.shared.invoker.InvocationResult;
import org.apache.maven.shared.invoker.Invoker;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.SshSessionFactory;
import org.eclipse.jgit.transport.SshTransport;
import org.eclipse.jgit.transport.TagOpt;
import org.eclipse.jgit.transport.ssh.jsch.JschConfigSessionFactory;
import org.eclipse.jgit.transport.ssh.jsch.OpenSshConfig;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.util.FS;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

public class GitHelper {

    private final Log log;
    private final File basedir;

    public GitHelper(Log log, File basedir) {
        this.log = log;
        this.basedir = basedir;
    }

    private Log getLog() {
        return log;
    }

    @SneakyThrows
    public List<String> changedModuleList() {
        List<String> changedModules;
        final SshSessionFactory sshSessionFactory = new JschConfigSessionFactory() {
            @Override
            protected void configure(OpenSshConfig.Host host, Session session) {
            }

            @Override
            protected JSch createDefaultJSch(FS fs) throws JSchException {
                JSch defaultJSch = super.createDefaultJSch(fs);
                var homeDir = System.getProperty("user.home");
                var idRsa = new File(homeDir, ".ssh/id_rsa");
                getLog().info(String.format("Using private key %s", idRsa.getAbsolutePath()));
                defaultJSch.addIdentity(idRsa.getAbsolutePath());
                return defaultJSch;
            }
        };
        getLog().info("Using basedir: " + basedir);

        Repository repository = new FileRepositoryBuilder()
                .setGitDir(new File(basedir, ".git"))
                .build();

        try (Git git = new Git(repository)) {
            var fetchCommand = git.fetch();
            fetchCommand.setTransportConfigCallback(transport -> {
                SshTransport sshTransport = (SshTransport) transport;
                sshTransport.setSshSessionFactory(sshSessionFactory);
            });

            // Fetch all remote tags
            fetchCommand.setTagOpt(TagOpt.FETCH_TAGS)
                    .setTransportConfigCallback(transport -> {
                        SshTransport sshTransport = (SshTransport) transport;
                        sshTransport.setSshSessionFactory(sshSessionFactory);
                    })
                    .call();

            // Find the last commit with prefix 'last-successful-build-'
            RevCommit lastSuccessfulBuildCommit = findLastSuccessfulBuildCommit(git);

            if (lastSuccessfulBuildCommit == null) {
                getLog().warn("No commit found with prefix 'last-successful-build-'");
                return null;
            }

            // Find changed submodules
            changedModules = findChangedModules(git, lastSuccessfulBuildCommit);
        }
        return changedModules;
    }

    private RevCommit findLastSuccessfulBuildCommit(Git git) throws GitAPIException, IOException {
        final String PREFIX = "last-successful-build-";
        RevCommit lastSuccessfulBuildCommit = null;
        RevWalk walk = new RevWalk(git.getRepository());

        try {
            List<Ref> tags = git.tagList().call();

            getLog().info("Found " + tags.size() + " tags");
            for (Ref tag : tags) {
                String tagName = tag.getName();
                getLog().info(String.format("Found tag %s", tagName));
                if (tagName.startsWith("refs/tags/" + PREFIX)) {
                    RevCommit commit = walk.parseCommit(tag.getObjectId());

                    if (lastSuccessfulBuildCommit == null || commit.getCommitTime() > lastSuccessfulBuildCommit.getCommitTime()) {
                        lastSuccessfulBuildCommit = commit;
                    }
                }
            }
        } finally {
            walk.dispose();
        }

        if (lastSuccessfulBuildCommit != null) {
            getLog().info("Found last successful build commit: " + lastSuccessfulBuildCommit.getName());
        } else {
            getLog().warn("No commit found with prefix '" + PREFIX + "'");
        }

        return lastSuccessfulBuildCommit;
    }

    private List<String> findChangedModules(Git git, RevCommit sinceCommit) throws GitAPIException, IOException, MojoExecutionException, org.codehaus.plexus.util.xml.pull.XmlPullParserException {
        Set<String> changedModules = new HashSet<>();

        ObjectReader reader = git.getRepository().newObjectReader();
        CanonicalTreeParser oldTreeIter = new CanonicalTreeParser();
        CanonicalTreeParser newTreeIter = new CanonicalTreeParser();

        try {
            ObjectId oldTree = sinceCommit.getTree().getId();
            ObjectId newTree = git.getRepository().resolve("HEAD^{tree}");

            oldTreeIter.reset(reader, oldTree);
            newTreeIter.reset(reader, newTree);

            List<DiffEntry> diffs = git.diff()
                    .setNewTree(newTreeIter)
                    .setOldTree(oldTreeIter)
                    .call();

            for (DiffEntry diff : diffs) {
                String path = diff.getNewPath();
                if (diff.getChangeType() == DiffEntry.ChangeType.DELETE) {
                    path = diff.getOldPath();
                }

                String module = findModule(path);
                if (module != null) {
                    changedModules.add(module);
                }
            }
        } finally {
            reader.close();
        }

        return new ArrayList<>(changedModules);
    }

    private String findModule(String path) {
        String[] parts = path.split("/");
        if (parts.length > 0) {
            return parts[0];  // Assumes top-level directories are modules
        }
        return null;
    }

    @SneakyThrows
    public String incrementRevisionOfSubModule(String moduleName) {
        File moduleDir = new File(basedir, moduleName);
        if (!moduleDir.exists() || !moduleDir.isDirectory()) {
            getLog().warn("Module directory not found: " + moduleDir.getAbsolutePath());
            return null;
        }

        InvocationRequest request = getInvocationRequest(moduleDir);

        Invoker invoker = new DefaultInvoker();
        InvocationResult result = invoker.execute(request);
        if (result.getExitCode() != 0) {
            if (result.getExecutionException() != null) {
                throw new MojoExecutionException("Failed to increment version for module: " + moduleName, result.getExecutionException());
            } else {
                throw new MojoExecutionException("Failed to increment version for module: " + moduleName + ". Exit code: " + result.getExitCode());
            }
        }

        MavenXpp3Reader reader = new MavenXpp3Reader();
        Model model = reader.read(new FileReader(new File(moduleDir, "pom.xml")));
        return model.getVersion();
    }

    @SneakyThrows
    private static InvocationRequest getInvocationRequest(File moduleDir) {
        InvocationRequest request = new DefaultInvocationRequest();
        File pomFile = new File(moduleDir, "pom.xml");
        request.setPomFile(pomFile);
        request.addArg("versions:set");

        String mavenHome = System.getenv("M2_HOME");
        if (mavenHome == null) {
            throw new IllegalStateException("M2_HOME not set. This is required for maven version:set to run");
        }
        String mavenRepo = System.getenv("M2_REPO");
        if (mavenRepo == null) {
            throw new IllegalStateException("M2_REPO not set. This is required for maven version:set to run");
        }
        request.setMavenHome(new File(mavenHome));
        request.setLocalRepositoryDirectory(new File(mavenRepo));

        // Read the current version from the POM file
        MavenXpp3Reader reader = new MavenXpp3Reader();
        Model model = reader.read(new FileReader(pomFile));
        String currentVersion = model.getVersion();

        // Parse the version and increment the patch number
        String[] versionParts = currentVersion.split("\\.");
        int major = Integer.parseInt(versionParts[0]);
        int minor = Integer.parseInt(versionParts[1]);
        int patch = Integer.parseInt(versionParts[2]);
        String newVersion = String.format("%d.%d.%d", major, minor, patch + 1);

        // Set the new version
        Properties properties = new Properties();
        properties.setProperty("newVersion", newVersion);
        request.setProperties(properties);

        return request;
    }
}