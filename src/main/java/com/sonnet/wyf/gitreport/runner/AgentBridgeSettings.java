package com.sonnet.wyf.gitreport.runner;

public class AgentBridgeSettings {
    private String webBaseUrl = "https://127.0.0.1:9642";
    private String mcpUrl = "http://127.0.0.1:8642/mcp";
    private int concurrency = 1;
    private int timeoutMinutes = 40;
    private int pollMillis = 1000;
    private int validationSettleSeconds = 30;
    private int validationMaxCorrections = 2;
    private int maxConcurrency = 1;
    private String taskMessage = "严格执行任务 prompt，写入要求的文件；完成后回复简短完成信息即可，Java 会校验输出。";
    private String synthesisTaskMessage = "严格执行汇总 prompt，生成要求的中文报告；完成后回复简短完成信息即可，Java 会校验输出。";

    public String getWebBaseUrl() {
        return webBaseUrl;
    }

    public void setWebBaseUrl(String webBaseUrl) {
        this.webBaseUrl = webBaseUrl;
    }

    public String getMcpUrl() {
        return mcpUrl;
    }

    public void setMcpUrl(String mcpUrl) {
        this.mcpUrl = mcpUrl;
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

    public int getPollMillis() {
        return pollMillis;
    }

    public void setPollMillis(int pollMillis) {
        this.pollMillis = pollMillis;
    }

    public int getValidationSettleSeconds() {
        return validationSettleSeconds;
    }

    public void setValidationSettleSeconds(int validationSettleSeconds) {
        this.validationSettleSeconds = validationSettleSeconds;
    }

    public int getValidationMaxCorrections() {
        return validationMaxCorrections;
    }

    public void setValidationMaxCorrections(int validationMaxCorrections) {
        this.validationMaxCorrections = validationMaxCorrections;
    }

    public int getMaxConcurrency() {
        return maxConcurrency;
    }

    public void setMaxConcurrency(int maxConcurrency) {
        this.maxConcurrency = maxConcurrency;
    }

    public String getTaskMessage() {
        return taskMessage;
    }

    public void setTaskMessage(String taskMessage) {
        this.taskMessage = taskMessage;
    }

    public String getSynthesisTaskMessage() {
        return synthesisTaskMessage;
    }

    public void setSynthesisTaskMessage(String synthesisTaskMessage) {
        this.synthesisTaskMessage = synthesisTaskMessage;
    }
}
