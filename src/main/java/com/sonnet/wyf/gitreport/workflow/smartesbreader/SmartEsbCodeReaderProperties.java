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
    private String workerMessage = "严格执行附件 worker-prompt.md 中的 SmartESB code-reader 单项阅读任务，只输出 DONE 或 BLOCKED。";
    private String synthesisMessage = "严格执行附件 synthesis-prompt.md 中的 SmartESB code-reader 索引任务，生成中文 index.md。";

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

    public String getWorkerMessage() {
        return workerMessage;
    }

    @JsonProperty("worker-message")
    public void setWorkerMessage(String workerMessage) {
        this.workerMessage = workerMessage;
    }

    public String getSynthesisMessage() {
        return synthesisMessage;
    }

    @JsonProperty("synthesis-message")
    public void setSynthesisMessage(String synthesisMessage) {
        this.synthesisMessage = synthesisMessage;
    }
}
