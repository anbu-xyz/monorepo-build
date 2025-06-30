package uk.anbu.maven.monorepo

import org.apache.maven.plugin.logging.Log
import spock.lang.Ignore
import spock.lang.Specification
import spock.lang.TempDir

class GitHelperSpec extends Specification {

    @TempDir
    File tempDir

    Log log
    GitHelper gitHelper
    File repoDir
    File remoteRepoDir

    def setup() {
        log = Mock(Log)
        repoDir = new File(tempDir, "test-repo")
        remoteRepoDir = new File(tempDir, "remote-repo")

        // Create and initialize remote repository
        remoteRepoDir.mkdirs()
        executeCommand(remoteRepoDir, "git init --bare")

        // Initialize local repo
        repoDir.mkdirs()
        gitHelper = new GitHelper(log, repoDir)

        // Initialize local git repo
        executeCommand(repoDir, "git init")
        executeCommand(repoDir, "git config user.email 'test@example.com'")
        executeCommand(repoDir, "git config user.name 'Test User'")

        // Add remote
        executeCommand(repoDir, "git remote add origin ${remoteRepoDir.absolutePath}")

        // Create initial module structure
        createModule("module1", "1.0.0")
        createModule("module2", "2.0.0")

        // Initial commit and push
        executeCommand(repoDir, "git add .")
        executeCommand(repoDir, """git commit -m "Initial commit" """)
        executeCommand(repoDir, "git tag last-successful-build-1")
        executeCommand(repoDir, "git push origin master")
        executeCommand(repoDir, "git push --tags")
    }

    def cleanup() {
        // Clean up git repositories
        if (repoDir.exists()) {
            new File(repoDir, ".git").deleteDir()
        }
        if (remoteRepoDir.exists()) {
            remoteRepoDir.deleteDir()
        }
    }

    def "changedModuleList should detect modified modules"() {
        given:
        // Modify module1
        new File(repoDir, "module1/src/main/java/Test.java") << "public class Test {}"
        executeCommand(repoDir, "git add .")
        executeCommand(repoDir, """git commit -m "Modified module1" """)
        executeCommand(repoDir, "git push origin master")

        when:
        def result = gitHelper.changedModuleList()

        then:
        result == ["module1"]
    }

    def "changedModuleList should return null when no last-successful-build tag exists"() {
        given:
        // Remove the last-successful-build tag
        executeCommand(repoDir, "git tag -d last-successful-build-1")
        executeCommand(repoDir, "git push origin :refs/tags/last-successful-build-1")

        when:
        def result = gitHelper.changedModuleList()

        then:
        result == null

        and:
        (1.._) * log.warn("No commit found with prefix 'last-successful-build-'")
    }

    @Ignore("Need to mock the MavenEnvironment class")
    def "incrementRevisionOfSubModule should increment patch version"() {
        given:
        def mavenEnv = Mock(MavenEnvironment) {
            // find maven home from environment variable
            getHome() >> System.getenv("MAVEN_HOME")
            getLocalRepositoryPath() >> new File(tempDir, "m2-repo").absolutePath
            getUserSettingsFile() >> new File(tempDir, "settings.xml")
        }

        when:
        def newVersion = gitHelper.incrementRevisionOfSubModule("module1", mavenEnv)

        then:
        newVersion == "1.0.1"
    }

    private void createModule(String moduleName, String version) {
        def moduleDir = new File(repoDir, moduleName)
        moduleDir.mkdirs()
        new File(moduleDir, "src/main/java").mkdirs()

        // Create pom.xml
        def pomFile = new File(moduleDir, "pom.xml")
        pomFile.text = """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <groupId>test.group</groupId>
    <artifactId>${moduleName}</artifactId>
    <version>${version}</version>
</project>"""
    }

    private void executeCommand(File workingDir, String command) {
        def process = command.execute(null, workingDir)
        process.waitFor()
        if (process.exitValue() != 0) {
            throw new RuntimeException("Command failed: ${command}\n${process.err.text}")
        }
    }
}