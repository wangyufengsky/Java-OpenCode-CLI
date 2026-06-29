package com.sonnet.wyf.gitreport.console;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties(prefix = "task-console")
public class TaskConsoleProperties {
    private Path databasePath = Path.of("data/opencode-task-console.sqlite");
    private Path runConfigDir = Path.of("data/run-configs");

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
}
