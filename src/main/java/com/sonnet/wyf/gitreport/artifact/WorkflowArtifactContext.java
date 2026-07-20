package com.sonnet.wyf.gitreport.artifact;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class WorkflowArtifactContext {
    private static final ThreadLocal<WorkflowArtifactWorkspace> CURRENT = new ThreadLocal<>();

    private WorkflowArtifactContext() {
    }

    public static WorkflowArtifactWorkspace current() {
        WorkflowArtifactWorkspace workspace = CURRENT.get();
        if (workspace == null) {
            throw new IllegalStateException("workflow artifact workspace is not active");
        }
        return workspace;
    }

    public static WorkflowArtifactWorkspace currentOrNull() {
        return CURRENT.get();
    }

    public static TaskArtifactLayout nextTaskAttempt(String taskKey, Path legacyRoot) throws IOException {
        WorkflowArtifactWorkspace workspace = CURRENT.get();
        if (workspace != null) {
            return workspace.nextTaskAttempt(taskKey);
        }
        Files.createDirectories(legacyRoot);
        return new TaskArtifactLayout(taskKey, 1, legacyRoot, legacyRoot.resolve("candidate"));
    }

    public static TaskArtifactLayout nextSynthesisAttempt(String taskKey, Path legacyRoot) throws IOException {
        WorkflowArtifactWorkspace workspace = CURRENT.get();
        if (workspace != null) {
            return workspace.nextSynthesisAttempt(taskKey);
        }
        Files.createDirectories(legacyRoot);
        return new TaskArtifactLayout(taskKey, 1, legacyRoot, legacyRoot.resolve("candidate"));
    }

    public static Path diagnosticPath(Path legacyPath) {
        WorkflowArtifactWorkspace workspace = CURRENT.get();
        return workspace == null
                ? legacyPath
                : workspace.runRoot().resolve("diagnostics").resolve(legacyPath.getFileName());
    }

    public static Scope open(WorkflowArtifactWorkspace workspace) {
        WorkflowArtifactWorkspace previous = CURRENT.get();
        CURRENT.set(workspace);
        return () -> restore(previous);
    }

    private static void restore(WorkflowArtifactWorkspace previous) {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
