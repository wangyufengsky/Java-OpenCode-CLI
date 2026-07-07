package com.sonnet.wyf.gitreport.workflow.smartesb;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.nio.file.Path;

public class SmartEsbRewriteProperties {
    private String out = "/home/wangyufeng/review-output/smartesb-rewrite-review";
    private Path localOut;
    private Path transactionPlanDir = Path.of("smartesb-transactions");
    private String newProject = "/home/wangyufeng/upfs-nl-json";
    private String docRoot = "/home/wangyufeng/upfs-nl-json/doc/docment";
    @JsonProperty("old-8583-doc")
    private String old8583Doc = "/home/wangyufeng/upfs-nl-json/doc/docment/old-8583.md";
    private String mappingDoc;
    private String reconstructedDesign;
    private String taskMessage = "严格执行附件 worker-prompt.md 中的 SmartESB 单项审查任务，写入要求的文件；完成后回复简短完成信息即可，Java 会校验输出。";
    private String synthesisTaskMessage = "严格执行附件 synthesis-prompt.md 中的 SmartESB 汇总任务，生成中文 index.md 和 summary.md；完成后回复简短完成信息即可，Java 会校验输出。";

    public String getOut() {
        return out;
    }

    public void setOut(String out) {
        this.out = out;
    }

    public Path getLocalOut() {
        return localOut;
    }

    public void setLocalOut(Path localOut) {
        this.localOut = localOut;
    }

    public Path getTransactionPlanDir() {
        return transactionPlanDir;
    }

    public void setTransactionPlanDir(Path transactionPlanDir) {
        this.transactionPlanDir = transactionPlanDir;
    }

    public String getNewProject() {
        return newProject;
    }

    public void setNewProject(String newProject) {
        this.newProject = newProject;
    }

    public String getDocRoot() {
        return docRoot;
    }

    public void setDocRoot(String docRoot) {
        this.docRoot = docRoot;
    }

    public String getOld8583Doc() {
        return old8583Doc;
    }

    public void setOld8583Doc(String old8583Doc) {
        this.old8583Doc = old8583Doc;
    }

    public String getMappingDoc() {
        return mappingDoc;
    }

    public void setMappingDoc(String mappingDoc) {
        this.mappingDoc = mappingDoc;
    }

    public String getReconstructedDesign() {
        return reconstructedDesign;
    }

    public void setReconstructedDesign(String reconstructedDesign) {
        this.reconstructedDesign = reconstructedDesign;
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
