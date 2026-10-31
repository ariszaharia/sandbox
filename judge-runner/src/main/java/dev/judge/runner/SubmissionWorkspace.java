package dev.judge.runner;

import dev.judge.runner.language.Language;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The host directory holding one submission's source and compiled artifact. */
public final class SubmissionWorkspace implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SubmissionWorkspace.class);
    private static final String PREFIX = "sub-";

    private final Path directory;

    private SubmissionWorkspace(Path directory) {
        this.directory = directory;
    }

    public static SubmissionWorkspace create(Path root, UUID submissionId, Language language, String sourceCode) {
        try {
            Path directory = Files.createDirectories(root).resolve(PREFIX + submissionId);
            Files.createDirectory(directory);
            // The compile container runs as nobody and has to write its artifact here.
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxrwxrwx"));
            }
            Files.writeString(directory.resolve(language.spec().sourceFileName()), sourceCode);
            return new SubmissionWorkspace(directory);
        } catch (IOException e) {
            throw new SandboxException("Could not prepare workspace for " + submissionId, e);
        }
    }

    /** Deletes workspaces abandoned by a process that died mid-submission. */
    public static void purgeStale(Path root) {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> entries = Files.list(root)) {
            entries.filter(path -> path.getFileName().toString().startsWith(PREFIX))
                    .forEach(SubmissionWorkspace::deleteRecursively);
        } catch (IOException e) {
            log.warn("Could not purge stale workspaces in {}", root, e);
        }
    }

    public Path directory() {
        return directory;
    }

    @Override
    public void close() {
        deleteRecursively(directory);
    }

    private static void deleteRecursively(Path directory) {
        try (Stream<Path> tree = Files.walk(directory)) {
            List<Path> deepestFirst = tree.sorted(Comparator.reverseOrder()).toList();
            for (Path path : deepestFirst) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            log.warn("Could not delete workspace {}", directory, e);
        }
    }
}
