package com.sonnet.wyf.gitreport.workflow.smartesb;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.nio.file.Path;

public class SmartEsbRewriteProperties {
    private String out = "D:\\review-output\\smartesb-rewrite-review";
    private Path localOut;
    private Path transactionPlanDir = Path.of("smartesb-transactions");
    private String oldProject = "D:\\upfs\\qianzhi\\upfs-cloud-xc";
    private String newProject = "D:\\upfs-nl-json";
    private String legacyIndex = "D:\\upfs-nl-json\\doc\\index.md";
    private String docRoot = "D:\\upfs-nl-json\\doc\\docment";
    @JsonProperty("old-8583-doc")
    private String old8583Doc;
    private String jsonDoc;
    private String mappingDoc;
    private String reconstructedDesign;
    private int batchSize = 5;
    private String workerMessage = "严格执行附件 worker-prompt.md 中的 SmartESB 单交易审查任务，只输出 DONE 或 BLOCKED。";
    private String synthesisMessage = "严格执行附件 synthesis-prompt.md 中的 SmartESB 汇总任务，生成中文 index.md 和 summary.md。";

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

    public String getOldProject() {
        return oldProject;
    }

    public void setOldProject(String oldProject) {
        this.oldProject = oldProject;
    }

    public String getNewProject() {
        return newProject;
    }

    public void setNewProject(String newProject) {
        this.newProject = newProject;
    }

    public String getLegacyIndex() {
        return legacyIndex;
    }

    public void setLegacyIndex(String legacyIndex) {
        this.legacyIndex = legacyIndex;
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

    public String getJsonDoc() {
        return jsonDoc;
    }

    public void setJsonDoc(String jsonDoc) {
        this.jsonDoc = jsonDoc;
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

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public String getWorkerMessage() {
        return workerMessage;
    }

    public void setWorkerMessage(String workerMessage) {
        this.workerMessage = workerMessage;
    }

    public String getSynthesisMessage() {
        return synthesisMessage;
    }

    public void setSynthesisMessage(String synthesisMessage) {
        this.synthesisMessage = synthesisMessage;
    }
}
