package com.sonnet.wyf.gitreport.runner;

import java.util.LinkedHashMap;
import java.util.Map;

public class OpenCodeSettings {
    private String serverUrl = "http://127.0.0.1:4096";
    private boolean manageServer = true;
    private int serverStartTimeoutSeconds = 30;
    private int createSessionTimeoutSeconds = 10;
    private int requestTimeoutSeconds = 60;
    private int concurrency = 6;
    private int timeoutMinutes = 40;
    private int outputWaitSeconds = 30;
    private int validationMaxCorrections = 2;
    private int maxRetries = 1;
    private int maxConcurrency = 6;
    private String opencodeBin = "opencode";
    private String sessionModel = "";
    private Map<String, String> environment = defaultEnvironment();

    private static Map<String, String> defaultEnvironment() {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("OPENCODE_DISABLE_MODELS_FETCH", "true");
        return environment;
    }

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

    public int getRequestTimeoutSeconds() {
        return requestTimeoutSeconds;
    }

    public void setRequestTimeoutSeconds(int requestTimeoutSeconds) {
        this.requestTimeoutSeconds = requestTimeoutSeconds;
    }

    public int getCreateSessionTimeoutSeconds() {
        return createSessionTimeoutSeconds;
    }

    public void setCreateSessionTimeoutSeconds(int createSessionTimeoutSeconds) {
        this.createSessionTimeoutSeconds = createSessionTimeoutSeconds;
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

    public int getValidationMaxCorrections() {
        return validationMaxCorrections;
    }

    public void setValidationMaxCorrections(int validationMaxCorrections) {
        this.validationMaxCorrections = validationMaxCorrections;
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

    public String getOpencodeBin() {
        return opencodeBin;
    }

    public void setOpencodeBin(String opencodeBin) {
        this.opencodeBin = opencodeBin;
    }

    public String getSessionModel() {
        return sessionModel;
    }

    public void setSessionModel(String sessionModel) {
        this.sessionModel = sessionModel;
    }

    public Map<String, String> getEnvironment() {
        return environment;
    }

    public void setEnvironment(Map<String, String> environment) {
        this.environment = environment == null ? new LinkedHashMap<>() : new LinkedHashMap<>(environment);
    }
}
