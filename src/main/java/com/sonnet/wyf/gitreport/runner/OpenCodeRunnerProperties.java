package com.sonnet.wyf.gitreport.runner;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.LocalDate;

@ConfigurationProperties(prefix = "opencode-runner")
public class OpenCodeRunnerProperties {
    private boolean enabled = false;
    private String activeChain = "git-code-contribution-report";
    private String mode = "full";
    private LocalDate runDate;
    private String configDir = "classpath:chains";
    private final Rerun rerun = new Rerun();
    private final OpenCodeSettings opencode = new OpenCodeSettings();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getActiveChain() {
        return activeChain;
    }

    public void setActiveChain(String activeChain) {
        this.activeChain = activeChain;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public LocalDate getRunDate() {
        return runDate;
    }

    public void setRunDate(LocalDate runDate) {
        this.runDate = runDate;
    }

    public String getConfigDir() {
        return configDir;
    }

    public void setConfigDir(String configDir) {
        this.configDir = configDir;
    }

    public Rerun getRerun() {
        return rerun;
    }

    public OpenCodeSettings getOpencode() {
        return opencode;
    }

    public static class Rerun {
        private String type;
        private String id;

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }
    }
}
