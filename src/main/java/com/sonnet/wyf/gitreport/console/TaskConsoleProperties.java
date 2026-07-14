package com.sonnet.wyf.gitreport.console;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Instant;

@ConfigurationProperties(prefix = "task-console")
public class TaskConsoleProperties {
    private Path databasePath = Path.of("data/agentbridge-task-console.sqlite");
    private Path runConfigDir = Path.of("data/run-configs");
    private boolean schedulerEnabled = true;
    private boolean executionEnabled = true;
    private Instant clockInstant;

    public Path getDatabasePath() {
        return databasePath;
    }

    public void setDatabasePath(Path databasePath) {
        this.databasePath = databasePath;
    }

    public Path getRunConfigDir() {
        return runConfigDir;
    }

    public void setRunConfigDir(Path runConfigDir) {
        this.runConfigDir = runConfigDir;
    }

    public boolean isSchedulerEnabled() {
        return schedulerEnabled;
    }

    public void setSchedulerEnabled(boolean schedulerEnabled) {
        this.schedulerEnabled = schedulerEnabled;
    }

    public boolean isExecutionEnabled() {
        return executionEnabled;
    }

    public void setExecutionEnabled(boolean executionEnabled) {
        this.executionEnabled = executionEnabled;
    }

    public Instant getClockInstant() {
        return clockInstant;
    }

    public void setClockInstant(Instant clockInstant) {
        this.clockInstant = clockInstant;
    }
}
