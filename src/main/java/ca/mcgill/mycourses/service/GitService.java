package ca.mcgill.mycourses.service;

import org.eclipse.jgit.api.*;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.revwalk.RevCommit;

import java.io.*;
import java.nio.file.Path;
import java.time.LocalDate;

public class GitService implements AutoCloseable {

    private final Git git;

    private GitService(Git git) {
        this.git = git;
    }

    /**
     * Opens an existing Git repo at repoDir, or initializes a new one.
     * A new repo gets an empty initial commit so that diffs work immediately.
     */
    public static GitService open(Path repoDir) throws IOException, GitAPIException {
        File dir = repoDir.toFile();
        if (new File(dir, ".git").exists()) {
            return new GitService(Git.open(dir));
        }
        Git git = Git.init().setDirectory(dir).call();
        git.commit()
           .setMessage("Init: mycourses-sync repository")
           .setAllowEmpty(true)
           .call();
        System.out.println("Initialized Git repo at " + repoDir);
        return new GitService(git);
    }

    /**
     * Stages all changes (new files, modifications, deletions) and creates a commit.
     * Returns the abbreviated SHA, or null if the working tree was already clean.
     */
    public String commitAll(String message) throws GitAPIException {
        git.add().addFilepattern(".").call();              // new + modified files
        git.add().addFilepattern(".").setUpdate(true).call(); // deleted files

        Status status = git.status().call();
        if (status.isClean()) {
            System.out.println("Nothing to commit — no changes detected.");
            return null;
        }

        RevCommit commit = git.commit().setMessage(message).call();
        return commit.abbreviate(7).name();
    }

    /** Standard timestamped commit message used by `pull`. */
    public static String syncMessage() {
        return "Sync myCourses - " + LocalDate.now();
    }

    /**
     * Returns a short status summary (like `git status --short`).
     */
    public String status() throws GitAPIException {
        Status status = git.status().call();
        if (status.isClean()) return "Nothing to report — working tree clean.";

        StringBuilder sb = new StringBuilder();
        status.getAdded()    .forEach(f -> sb.append("A  ").append(f).append('\n'));
        status.getModified() .forEach(f -> sb.append("M  ").append(f).append('\n'));
        status.getRemoved()  .forEach(f -> sb.append("D  ").append(f).append('\n'));
        status.getUntracked().forEach(f -> sb.append("?? ").append(f).append('\n'));
        return sb.toString();
    }

    /**
     * Returns the unified diff of all unstaged changes (working tree vs index).
     * Binary files will appear as absent from the diff output — that is normal.
     */
    public String diff() throws GitAPIException, IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        git.diff().setOutputStream(out).call();
        String text = out.toString();
        return text.isBlank() ? "No uncommitted changes." : text;
    }

    /** Returns a short git log of the last {@code maxCount} commits. */
    public String log(int maxCount) throws GitAPIException {
        StringBuilder sb = new StringBuilder();
        for (RevCommit c : git.log().setMaxCount(maxCount).call()) {
            sb.append(c.abbreviate(7).name())
              .append("  ")
              .append(c.getShortMessage())
              .append('\n');
        }
        return sb.isEmpty() ? "(no commits yet)" : sb.toString();
    }

    public Path getWorkTree() {
        return git.getRepository().getWorkTree().toPath();
    }

    @Override
    public void close() {
        git.close();
    }
}
