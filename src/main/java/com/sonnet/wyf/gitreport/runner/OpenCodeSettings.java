package com.sonnet.wyf.gitreport.runner;

public class OpenCodeSettings {
    private String serverUrl = "http://127.0.0.1:4096";
    private boolean manageServer = true;
    private int serverStartTimeoutSeconds = 30;
    private int concurrency = 6;
    private int timeoutMinutes = 40;
    private int outputWaitSeconds = 30;
    private int maxRetries = 1;
    private int maxConcurrency = 6;
    private String model;
    private String opencodeBin = "opencode";

    public String getServerUrl() {
        return serverUrl;
    }

    public void setServerUrl(String serverUrl) {
        this.serverUrl = serverUrl;
    }

    public boolean isManageServer() {
        return manageServer;
    }

    public void setManageServer(boolean manageServer) {
        this.manageServer = manageServer;
    }

    public int getServerStartTimeoutSeconds() {
        return serverStartTimeoutSeconds;
    }

    public void setServerStartTimeoutSeconds(int serverStartTimeoutSeconds) {
        this.serverStartTimeoutSeconds = serverStartTimeoutSeconds;
    }

    public int getConcurrency() {
        return concurrency;
    }

    public void setConcurrency(int concurrency) {
        this.concurrency = concurrency;
    }

    public int getTimeoutMinutes() {
        return timeoutMinutes;
    }

    public void setTimeoutMinutes(int timeoutMinutes) {
        this.timeoutMinutes = timeoutMinutes;
    }

    public int getOutputWaitSeconds() {
        return outputWaitSeconds;
    }

    public void setOutputWaitSeconds(int outputWaitSeconds) {
        this.outputWaitSeconds = outputWaitSeconds;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public int getMaxConcurrency() {
        return maxConcurrency;
    }

    public void setMaxConcurrency(int maxConcurrency) {
        this.maxConcurrency = maxConcurrency;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getOpencodeBin() {
        return opencodeBin;
    }

    public void setOpencodeBin(String opencodeBin) {
        this.opencodeBin = opencodeBin;
    }
}
