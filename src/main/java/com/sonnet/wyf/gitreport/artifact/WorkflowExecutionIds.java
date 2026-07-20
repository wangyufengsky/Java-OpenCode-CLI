package com.sonnet.wyf.gitreport.artifact;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

public final class WorkflowExecutionIds {
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss.SSSxx");

    private WorkflowExecutionIds() {
    }

    public static String newExecutionId() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return "run-" + TIMESTAMP.format(OffsetDateTime.now()) + "-" + suffix;
    }
}
