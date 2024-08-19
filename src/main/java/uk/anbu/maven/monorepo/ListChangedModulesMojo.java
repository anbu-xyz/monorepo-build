package uk.anbu.maven.monorepo;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
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
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Mojo(name = "list-changed-modules", defaultPhase = LifecyclePhase.INITIALIZE)
public class ListChangedModulesMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project.basedir}", readonly = true)
    private File basedir;

    @Parameter(property = "project", readonly = true)
    private MavenProject project;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
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
        try {
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
                    return;
                }

                // Find changed submodules
                List<String> changedModules = findChangedModules(git, lastSuccessfulBuildCommit);

                // Log changed modules
                getLog().info("Changed modules since last successful build:");
                for (String module : changedModules) {
                    getLog().info("- " + module);
                }
            }
        } catch (IOException | GitAPIException e) {
            throw new MojoExecutionException("Error executing list-changed-modules goal", e);
        }
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

    private List<String> findChangedModules(Git git, RevCommit sinceCommit) throws GitAPIException, IOException {
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
}