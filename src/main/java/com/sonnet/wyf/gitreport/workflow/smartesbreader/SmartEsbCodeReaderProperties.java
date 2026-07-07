package com.sonnet.wyf.gitreport.workflow.smartesbreader;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class SmartEsbCodeReaderProperties {
    private String out = "/home/wangyufeng/review-output/smartesb-code-reader";
    private Path localOut;
    private List<Path> serviceIdentify = new ArrayList<>();
    private Path xmlRoot = Path.of("/home/wangyufeng/upfs-production");
    private Path bizRoot;
    private Path javaRoot = Path.of("/home/wangyufeng/upfs-production");
    private String mode = "8583";
    private String taskMessage = "严格执行附件 worker-prompt.md 中的 SmartESB code-reader 单项阅读任务，写入要求的文件；完成后回复简短完成信息即可，Java 会校验输出。";
    private String synthesisTaskMessage = "严格执行附件 synthesis-prompt.md 中的 SmartESB code-reader 索引任务，生成中文 index.md；完成后回复简短完成信息即可，Java 会校验输出。";

    public String getOut() {
        return out;
    }

    public void setOut(String out) {
        this.out = out;
    }

    public Path getLocalOut() {
        return localOut;
    }

    @JsonProperty("local-out")
    public void setLocalOut(Path localOut) {
        this.localOut = localOut;
    }

    public List<Path> getServiceIdentify() {
        return serviceIdentify;
    }

    @JsonProperty("service-identify")
    public void setServiceIdentify(List<Path> serviceIdentify) {
        this.serviceIdentify = serviceIdentify == null ? new ArrayList<>() : new ArrayList<>(serviceIdentify);
    }

    public Path getXmlRoot() {
        return xmlRoot;
    }

    @JsonProperty("xml-root")
    public void setXmlRoot(Path xmlRoot) {
        this.xmlRoot = xmlRoot;
    }

    public Path getBizRoot() {
        return bizRoot == null ? xmlRoot : bizRoot;
    }

    @JsonProperty("biz-root")
    public void setBizRoot(Path bizRoot) {
        this.bizRoot = bizRoot;
    }

    public Path getJavaRoot() {
        return javaRoot;
    }

    @JsonProperty("java-root")
    public void setJavaRoot(Path javaRoot) {
        this.javaRoot = javaRoot;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getTaskMessage() {
        return taskMessage;
    }

    @JsonProperty("task-message")
    public void setTaskMessage(String taskMessage) {
        this.taskMessage = taskMessage;
    }

    public String getSynthesisTaskMessage() {
        return synthesisTaskMessage;
    }

    @JsonProperty("synthesis-task-message")
    public void setSynthesisTaskMessage(String synthesisTaskMessage) {
        this.synthesisTaskMessage = synthesisTaskMessage;
    }
}
