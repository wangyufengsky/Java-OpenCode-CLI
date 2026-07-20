package com.sonnet.wyf.gitreport.artifact;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;

public final class RepositoryExecutionLock implements AutoCloseable {
    private final Path path;
    private final FileChannel channel;
    private final FileLock lock;

    private RepositoryExecutionLock(Path path, FileChannel channel, FileLock lock) {
        this.path = path;
        this.channel = channel;
        this.lock = lock;
    }

    public static RepositoryExecutionLock acquire(Path repository) throws IOException {
        Path repo = repository.toAbsolutePath().normalize();
        if (!Files.isDirectory(repo)) {
            throw new IllegalArgumentException("repository lock requires an existing directory: " + repo);
        }
        Path lockDir = Files.isDirectory(repo.resolve(".git")) ? repo.resolve(".git") : repo;
        Path lockPath = lockDir.resolve("java-agentbridge-workflow.lock");
        FileChannel channel = FileChannel.open(lockPath, CREATE, WRITE);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                throw new IllegalStateException("repository is already used by another unit-test workflow: " + repo);
            }
            return new RepositoryExecutionLock(lockPath, channel, lock);
        } catch (OverlappingFileLockException exception) {
            channel.close();
            throw new IllegalStateException("repository is already used by another unit-test workflow: " + repo, exception);
        } catch (RuntimeException | IOException exception) {
            channel.close();
            throw exception;
        }
    }

    public Path path() {
        return path;
    }

    @Override
    public void close() throws IOException {
        try {
            lock.release();
        } finally {
            channel.close();
        }
    }
}
