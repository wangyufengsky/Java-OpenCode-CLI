package com.sonnet.wyf.gitreport.console;

public final class WorkflowRunContext {
    private static final ThreadLocal<Long> CURRENT_RUN_ID = new ThreadLocal<>();
    private static final ThreadLocal<TaskIdentity> CURRENT_TASK = new ThreadLocal<>();
    private static final ThreadLocal<String> CURRENT_OUTPUT_PATH = new ThreadLocal<>();

    private WorkflowRunContext() {
    }

    public static Long currentRunId() {
        return CURRENT_RUN_ID.get();
    }

    public static TaskIdentity currentTask() {
        return CURRENT_TASK.get();
    }

    public static String currentOutputPath() {
        return CURRENT_OUTPUT_PATH.get();
    }

    public static void setCurrentOutputPath(String outputPath) {
        if (outputPath == null || outputPath.isBlank()) {
            CURRENT_OUTPUT_PATH.remove();
        } else {
            CURRENT_OUTPUT_PATH.set(outputPath);
        }
    }

    public static Scope open(long runId) {
        Long previous = CURRENT_RUN_ID.get();
        String previousOutputPath = CURRENT_OUTPUT_PATH.get();
        CURRENT_RUN_ID.set(runId);
        CURRENT_OUTPUT_PATH.remove();
        return () -> {
            restore(previous);
            restoreOutputPath(previousOutputPath);
        };
    }

    public static Scope openTask(String taskKey, String taskName) {
        TaskIdentity previous = CURRENT_TASK.get();
        CURRENT_TASK.set(new TaskIdentity(taskKey, taskName));
        return () -> restoreTask(previous);
    }

    public static Scope open(Long runId) {
        Long previous = CURRENT_RUN_ID.get();
        if (runId == null) {
            CURRENT_RUN_ID.remove();
        } else {
            CURRENT_RUN_ID.set(runId);
        }
        return () -> restore(previous);
    }

    private static void restore(Long previous) {
        if (previous == null) {
            CURRENT_RUN_ID.remove();
        } else {
            CURRENT_RUN_ID.set(previous);
        }
    }

    private static void restoreTask(TaskIdentity previous) {
        if (previous == null) {
            CURRENT_TASK.remove();
        } else {
            CURRENT_TASK.set(previous);
        }
    }

    private static void restoreOutputPath(String previous) {
        if (previous == null) {
            CURRENT_OUTPUT_PATH.remove();
        } else {
            CURRENT_OUTPUT_PATH.set(previous);
        }
    }

    public record TaskIdentity(String taskKey, String taskName) {
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
