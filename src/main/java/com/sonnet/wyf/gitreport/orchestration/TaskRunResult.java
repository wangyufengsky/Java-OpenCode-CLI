package com.sonnet.wyf.gitreport.orchestration;

import java.nio.file.Path;

public record TaskRunResult(String taskKey, String taskName, Path statusPath, boolean success, String error) {
    public static TaskRunResult success(String taskKey, String taskName, Path statusPath) {
        return new TaskRunResult(taskKey, taskName, statusPath, true, "");
    }

    public static TaskRunResult failed(String taskKey, String taskName, Path statusPath, String error) {
        return new TaskRunResult(taskKey, taskName, statusPath, false, error == null ? "" : error);
    }
}
