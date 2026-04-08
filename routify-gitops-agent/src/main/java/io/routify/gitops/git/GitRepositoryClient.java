package io.routify.gitops.git;

import io.routify.gitops.config.GitOpsProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * JGit-based Git repository client that clones/fetches a remote repository
 * and reads the configuration YAML file.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GitRepositoryClient {

    private final GitOpsProperties properties;
    private final GitCredentialsProvider credentialsProvider;

    private Git git;
    private Path localRepoPath;

    @PostConstruct
    void init() {
        if (!properties.isEnabled()) {
            log.info("GitOps agent is disabled — skipping repository initialisation");
            return;
        }
        credentialsProvider.configureSshAuth();
    }

    @PreDestroy
    void cleanup() {
        if (git != null) {
            git.close();
        }
    }

    /**
     * Ensures the local repository clone exists. If not, performs an initial clone.
     * On subsequent calls, fetches the latest changes from the remote.
     *
     * @throws GitAPIException if a Git operation fails
     * @throws IOException     if filesystem operations fail
     */
    public void fetchLatest() throws GitAPIException, IOException {
        if (git == null) {
            cloneRepository();
        } else {
            fetchAndCheckout();
        }
    }

    /**
     * Reads the configuration file from the checked-out repository.
     *
     * @return the file contents, or empty if the file does not exist
     */
    public Optional<String> readConfigFile() {
        if (git == null) {
            log.warn("Repository not initialised — cannot read config file");
            return Optional.empty();
        }

        try {
            Repository repo = git.getRepository();
            ObjectId head = repo.resolve("HEAD");
            if (head == null) {
                log.warn("HEAD is null — repository may be empty");
                return Optional.empty();
            }

            try (RevWalk revWalk = new RevWalk(repo)) {
                RevCommit commit = revWalk.parseCommit(head);
                try (TreeWalk treeWalk = new TreeWalk(repo)) {
                    treeWalk.addTree(commit.getTree());
                    treeWalk.setRecursive(true);
                    treeWalk.setFilter(PathFilter.create(properties.getConfigPath()));

                    if (!treeWalk.next()) {
                        log.warn("Config file '{}' not found in repository at branch '{}'",
                                properties.getConfigPath(), properties.getBranch());
                        return Optional.empty();
                    }

                    ObjectId objectId = treeWalk.getObjectId(0);
                    byte[] content = repo.open(objectId).getBytes();
                    return Optional.of(new String(content, StandardCharsets.UTF_8));
                }
            }
        } catch (IOException e) {
            log.error("Failed to read config file from repository: {}", e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * Returns the current HEAD commit hash of the local repository.
     */
    public Optional<String> getHeadCommitHash() {
        if (git == null) return Optional.empty();
        try {
            ObjectId head = git.getRepository().resolve("HEAD");
            return head != null ? Optional.of(head.getName()) : Optional.empty();
        } catch (IOException e) {
            log.error("Failed to resolve HEAD: {}", e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * Computes the SHA-256 hash of the given content.
     */
    public static String computeSha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is always available in JDK
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private void cloneRepository() throws GitAPIException, IOException {
        localRepoPath = Files.createTempDirectory("routify-gitops-");
        log.info("Cloning repository {} (branch: {}) to {}",
                properties.getRepositoryUrl(), properties.getBranch(), localRepoPath);

        var cloneCommand = Git.cloneRepository()
                .setURI(properties.getRepositoryUrl())
                .setDirectory(localRepoPath.toFile())
                .setBranch(properties.getBranch())
                .setCloneAllBranches(false);

        CredentialsProvider creds = credentialsProvider.getHttpsCredentials();
        if (creds != null) {
            cloneCommand.setCredentialsProvider(creds);
        }

        git = cloneCommand.call();
        log.info("Repository cloned successfully. HEAD: {}",
                getHeadCommitHash().orElse("unknown"));
    }

    private void fetchAndCheckout() throws GitAPIException {
        log.debug("Fetching latest from remote for branch: {}", properties.getBranch());

        var fetchCommand = git.fetch()
                .setRemote("origin");

        CredentialsProvider creds = credentialsProvider.getHttpsCredentials();
        if (creds != null) {
            fetchCommand.setCredentialsProvider(creds);
        }

        fetchCommand.call();

        // Checkout the remote tracking branch (detached HEAD)
        git.checkout()
                .setName("origin/" + properties.getBranch())
                .setForced(true)
                .call();

        log.debug("Checked out origin/{}. HEAD: {}",
                properties.getBranch(), getHeadCommitHash().orElse("unknown"));
    }
}

