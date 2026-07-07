package com.sonnet.wyf.gitreport.workflow.weekly;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.agentbridge.AgentBridgeTaskRunner;
import com.sonnet.wyf.gitreport.agentbridge.ValidatedAgentBridgeTaskSpec;
import com.sonnet.wyf.gitreport.agentbridge.ValidationCheck;
import com.sonnet.wyf.gitreport.orchestration.ConcurrentWorkflowTaskRunner;
import com.sonnet.wyf.gitreport.orchestration.TaskRunResult;
import com.sonnet.wyf.gitreport.runner.AgentBridgeSettings;
import com.sonnet.wyf.gitreport.runner.WorkflowRunRequest;
import com.sonnet.wyf.gitreport.util.JsonMaps;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

public class WeeklyAgentBridgeReviewRunner implements WeeklyCodeReviewRunner {
    private final ObjectMapper objectMapper;
    private final AgentBridgeTaskRunner taskRunner;
    private final ConcurrentWorkflowTaskRunner concurrentTaskRunner;
    private final WeeklyCodeReviewOutputValidator outputValidator;

    public WeeklyAgentBridgeReviewRunner(
            ObjectMapper objectMapper,
            AgentBridgeTaskRunner taskRunner,
            ConcurrentWorkflowTaskRunner concurrentTaskRunner,
            WeeklyCodeReviewOutputValidator outputValidator
    ) {
        this.objectMapper = objectMapper;
        this.taskRunner = taskRunner;
        this.concurrentTaskRunner = concurrentTaskRunner;
        this.outputValidator = outputValidator;
    }

    @Override
    public void run(WeeklyEngineeringReportProperties properties, WorkflowRunRequest request, Path evidencePath, List<String> batchIds) throws Exception {
        Path out = evidencePath.toAbsolutePath().normalize().getParent();
        Map<String, Object> evidence = objectMapper.readValue(evidencePath.toFile(), new TypeReference<>() {});
        List<String> requestedBatchIds = batchIds == null ? List.of() : batchIds;
        if (!requestedBatchIds.isEmpty()) {
            List<String> available = listOfMaps(evidence.get("review_batches")).stream()
                    .map(batch -> string(batch.get("batch_id")))
                    .toList();
            List<String> missing = requestedBatchIds.stream()
                    .filter(id -> !available.contains(id))
                    .toList();
            if (!missing.isEmpty()) {
                throw new IllegalArgumentException("unknown weekly review batch id: " + String.join(", ", missing));
            }
        }
        List<Map<String, Object>> batches = listOfMaps(evidence.get("review_batches")).stream()
                .filter(batch -> requestedBatchIds.isEmpty() || requestedBatchIds.contains(string(batch.get("batch_id"))))
                .toList();
        if (batches.isEmpty()) {
            return;
        }
        AgentBridgeSettings agentBridge = effectiveAgentBridge(request.agentBridge(), properties);
        int concurrency = Math.max(1, Math.min(properties.getReview().getConcurrency(), agentBridge.getMaxConcurrency()));
        List<TaskRunResult> results = concurrentTaskRunner.run(
                "weekly code review",
                batches,
                concurrency,
                batch -> string(batch.get("batch_id")),
                batch -> batchCallable(properties, agentBridge, out, batch)
        );
        List<String> failures = results.stream().filter(result -> !result.success()).map(result -> result.taskName() + ": " + result.error()).toList();
        if (!failures.isEmpty()) {
            throw new IllegalStateException("weekly code review failed: " + String.join("; ", failures));
        }
    }

    private Callable<TaskRunResult> batchCallable(
            WeeklyEngineeringReportProperties properties,
            AgentBridgeSettings agentBridge,
            Path out,
            Map<String, Object> batch
    ) {
        return () -> {
            String batchId = string(batch.get("batch_id"));
            Path runDir = out.resolve("runs").resolve(batchId);
            Path statusPath = runDir.resolve("agent-status.json");
            Path summaryJson = Path.of(string(batch.get("summary_json")));
            try {
                Files.createDirectories(runDir);
                Files.createDirectories(summaryJson.getParent());
                Path promptFile = runDir.resolve("worker-prompt.md");
                Files.writeString(promptFile, buildPrompt(batch));
                taskRunner.runUntilValidated(new ValidatedAgentBridgeTaskSpec(
                        properties.getProject().getRepo(),
                        "weekly-code-review-" + batchId,
                        promptFile,
                        agentBridge.getTaskMessage(),
                        runDir,
                        () -> validationCheck(batch, summaryJson),
                        agentBridge.getPollMillis(),
                        agentBridge.getTimeoutMinutes(),
                        agentBridge.getValidationSettleSeconds(),
                        agentBridge.getValidationMaxCorrections(),
                        java.net.URI.create(agentBridge.getWebBaseUrl())
                ));
                var validation = outputValidator.validate(batch, summaryJson);
                if (validation.ok()) {
                    return TaskRunResult.success(batchId, batchId, statusPath);
                }
                return TaskRunResult.failed(batchId, batchId, statusPath, validation.error());
            } catch (Exception exception) {
                return TaskRunResult.failed(batchId, batchId, statusPath, exception.getMessage());
            }
        };
    }

    private AgentBridgeSettings effectiveAgentBridge(AgentBridgeSettings requestSettings, WeeklyEngineeringReportProperties properties) {
        AgentBridgeSettings settings = new AgentBridgeSettings();
        settings.setWebBaseUrl(firstNonBlank(properties.getAgentbridge().getWebBaseUrl(), requestSettings.getWebBaseUrl()));
        settings.setMcpUrl(firstNonBlank(properties.getAgentbridge().getMcpUrl(), requestSettings.getMcpUrl()));
        settings.setConcurrency(properties.getAgentbridge().getConcurrency());
        settings.setTimeoutMinutes(properties.getAgentbridge().getTimeoutMinutes());
        settings.setPollMillis(properties.getAgentbridge().getPollMillis());
        settings.setValidationSettleSeconds(properties.getAgentbridge().getValidationSettleSeconds());
        settings.setValidationMaxCorrections(properties.getAgentbridge().getValidationMaxCorrections());
        settings.setMaxConcurrency(properties.getAgentbridge().getMaxConcurrency());
        settings.setTaskMessage(firstNonBlank(properties.getAgentbridge().getTaskMessage(), requestSettings.getTaskMessage()));
        settings.setSynthesisTaskMessage(firstNonBlank(properties.getAgentbridge().getSynthesisTaskMessage(), requestSettings.getSynthesisTaskMessage()));
        return settings;
    }

    private String firstNonBlank(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }

    private ValidationCheck validationCheck(Map<String, Object> batch, Path summaryJson) {
        var validation = outputValidator.validate(batch, summaryJson);
        return validation.ok() ? ValidationCheck.success() : ValidationCheck.failed(validation.error());
    }

    private String buildPrompt(Map<String, Object> batch) {
        return """
                你是 weekly-engineering-report 的代码审查 worker。

                本任务是一个 review unit，不是单文件小批次。review unit 可能按模块/作者聚合了多文件、多 commit 的 changed regions。

                严格边界：
                - 只读取本 review unit 的 input_json。
                - 读取 input_json，并按其中的 changed_regions 审查。
                - 只审查 input_json.changed_regions 内的 hunk，不得把完整文件或其他历史链路产物作为归因依据。
                - reviewed_region_ids 必须覆盖 input_json.changed_regions 中的全部 region_id。
                - 不得只挑重点审查，不得因为文件多而跳过低风险 region；没有问题的 region 也必须计入 reviewed_region_ids。
                - finding 必须绑定 region_id、author_key、commit、file、line_start、line_end。
                - severity 只能是 P0、P1、P2。
                - summary 可以写模块级结论，但每条 finding 必须落回具体 region。
                - code-review.md 必须按模块/文件分节，保留完整审查说明，便于最终分卷报告用相对链接跳转。
                - 写入 summary_json 和 review_md 指定的文件。
                - 不要求使用指定读写工具名；使用当前 AgentBridge 环境可用的能力完成文件读取和写入。
                - 完成后回复简短完成信息即可，Java 会校验输出文件。

                input_json: %s
                summary_json: %s
                review_md: %s
                batch_id: %s
                unit_id: %s
                """.formatted(
                batch.get("input_json"),
                batch.get("summary_json"),
                batch.get("review_md"),
                batch.get("batch_id"),
                firstNonBlank(string(batch.get("unit_id")), string(batch.get("batch_id")))
        );
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listOfMaps(Object value) {
        return JsonMaps.listOfMaps(value);
    }

    private String string(Object value) {
        return JsonMaps.string(value);
    }
}
