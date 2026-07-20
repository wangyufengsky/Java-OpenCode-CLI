package com.sonnet.wyf.gitreport.console;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.file.Path;
import java.nio.file.InvalidPathException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ConsoleApiController {
    private final ChainCatalog chainCatalog;
    private final WorkflowRunRepository repository;
    private final WorkflowExecutionService executionService;
    private final EventStreamService eventStreamService;
    private final WorkflowScheduleService scheduleService;
    private final RunConfigReader configReader;
    private final PathPreflightService pathPreflightService;
    private final ConsoleViewService viewService;

    public ConsoleApiController(
            ChainCatalog chainCatalog,
            WorkflowRunRepository repository,
            WorkflowExecutionService executionService,
            EventStreamService eventStreamService,
            WorkflowScheduleService scheduleService,
            RunConfigReader configReader,
            PathPreflightService pathPreflightService,
            ConsoleViewService viewService
    ) {
        this.chainCatalog = chainCatalog;
        this.repository = repository;
        this.executionService = executionService;
        this.eventStreamService = eventStreamService;
        this.scheduleService = scheduleService;
        this.configReader = configReader;
        this.pathPreflightService = pathPreflightService;
        this.viewService = viewService;
    }

    @GetMapping("/chains")
    public Map<String, List<String>> chains() {
        return Map.of("chains", chainCatalog.chainIds());
    }

    @GetMapping("/chains/{chainId}/defaults")
    public Map<String, Map<String, Object>> chainDefaults(@PathVariable String chainId) throws Exception {
        return Map.of("defaults", chainCatalog.defaultValues(chainId));
    }

    @PostMapping("/runs")
    public Map<String, Long> submit(@RequestBody WorkflowRunSubmission submission) throws Exception {
        validate(submission);
        return Map.of("id", executionService.submit(submission));
    }

    @GetMapping("/runs")
    public List<WorkflowRunRecord> runs() {
        return repository.listRuns();
    }

    @DeleteMapping("/runs/{id}")
    public Map<String, Integer> deleteRunHistory(@PathVariable long id) {
        WorkflowRunRecord run = repository.findRun(id).orElseThrow();
        requireTerminal(run);
        return Map.of("deleted", repository.deleteTerminalRun(id) ? 1 : 0);
    }

    @DeleteMapping("/runs")
    public Map<String, Integer> clearRunHistory() {
        return Map.of("deleted", repository.clearTerminalRuns());
    }

    @PostMapping("/runs/{id}/rerun")
    public Map<String, Long> rerunHistory(@PathVariable long id) throws Exception {
        WorkflowRunRecord source = repository.findRun(id).orElseThrow();
        requireTerminal(source);
        WorkflowRunSubmission submission = new WorkflowRunSubmission(
                source.chainId(),
                source.mode(),
                source.rerunType(),
                source.rerunId(),
                source.runDate(),
                readRunConfig(source),
                null
        );
        validate(submission);
        return Map.of("id", executionService.submit(submission));
    }

    @GetMapping("/schedules")
    public List<WorkflowScheduleRecord> schedules() {
        return scheduleService.list();
    }

    @PostMapping("/schedules")
    public Map<String, Long> createSchedule(@RequestBody WorkflowScheduleRequest request) {
        return Map.of("id", scheduleService.create(request));
    }

    @PostMapping("/schedules/{id}")
    public WorkflowScheduleRecord updateSchedule(@PathVariable long id, @RequestBody WorkflowScheduleRequest request) {
        return scheduleService.update(id, request);
    }

    @PostMapping("/schedules/{id}/enabled")
    public WorkflowScheduleRecord setScheduleEnabled(@PathVariable long id, @RequestBody Map<String, Boolean> body) {
        return scheduleService.setEnabled(id, Boolean.TRUE.equals(body.get("enabled")));
    }

    @GetMapping("/runs/{id}")
    public WorkflowRunRecord run(@PathVariable long id) {
        return repository.findRun(id).orElseThrow();
    }

    @GetMapping("/runs/{id}/config")
    public RunConfigResponse runConfig(@PathVariable long id) throws Exception {
        WorkflowRunRecord run = repository.findRun(id).orElseThrow();
        return new RunConfigResponse(
                run.id(),
                run.chainId(),
                run.mode(),
                run.rerunType(),
                run.rerunId(),
                run.runDate(),
                readRunConfig(run)
        );
    }

    private Map<String, Object> readRunConfig(WorkflowRunRecord run) {
        if (run.configPath() == null || run.configPath().isBlank()) {
            throw new IllegalArgumentException("运行没有配置文件");
        }
        try {
            return configReader.readFlat(Path.of(run.configPath()));
        } catch (InvalidPathException exception) {
            throw new IllegalArgumentException("运行配置文件不存在或不可读取", exception);
        }
    }

    @GetMapping("/path-preflight")
    public PathPreflightService.Result pathPreflight(@RequestParam String path) {
        return pathPreflightService.inspect(path);
    }

    @GetMapping(path = "/runs/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable long id) throws Exception {
        return eventStreamService.subscribe(id, repository.listEvents(id));
    }

    @GetMapping("/runs/{id}/snapshot")
    public RunSnapshotResponse runSnapshot(
            @PathVariable long id,
            @RequestParam(required = false, defaultValue = "0") long afterEventId
    ) {
        long normalizedAfterEventId = Math.max(0L, afterEventId);
        // Producers persist task/run state before their corresponding event, so capture the event watermark first.
        List<WorkflowRunEvent> events = repository.listEventsAfter(id, normalizedAfterEventId);
        WorkflowRunRecord run = repository.findRun(id).orElseThrow();
        ConsoleRunDetailSummary summary = viewService.runDetail(id);
        List<WorkflowTaskStatus> tasks = repository.listTaskStatuses(id);
        return new RunSnapshotResponse(
                run,
                summary,
                tasks,
                events,
                WorkflowRerunContract.failedTaskAction(run.chainId(), summary, tasks)
        );
    }

    private void validate(WorkflowRunSubmission submission) {
        String chainId = submission.chainId() == null ? "" : submission.chainId().trim();
        chainCatalog.chain(chainId);
        String mode = submission.mode() == null || submission.mode().isBlank() ? "full" : submission.mode().trim().toLowerCase();
        if (!"full".equals(mode) && !"rerun".equals(mode)) {
            throw new IllegalArgumentException("运行模式必须是 full 或 rerun");
        }
        if ("rerun".equals(mode)) {
            String rerunType = WorkflowRerunContract.normalizeType(chainId, submission.rerunType());
            if (rerunType.isBlank()) {
                throw new IllegalArgumentException("重跑模式必须填写重跑类型");
            }
            if (!WorkflowRerunContract.isKnownType(chainId, rerunType)) {
                throw new IllegalArgumentException("不支持的重跑类型: " + submission.rerunType());
            }
            if (WorkflowRerunContract.requiresRerunId(chainId, rerunType)
                    && (submission.rerunId() == null || submission.rerunId().isBlank())) {
                throw new IllegalArgumentException("重跑模式必须填写重跑 ID");
            }
        }
    }

    private static void requireTerminal(WorkflowRunRecord run) {
        if (run.state() != RunState.SUCCEEDED && run.state() != RunState.FAILED) {
            throw new IllegalArgumentException("运行中或排队中的记录不能清理或重跑");
        }
    }

    public record RunConfigResponse(
            long sourceRunId,
            String chainId,
            String mode,
            String rerunType,
            String rerunId,
            LocalDate runDate,
            Map<String, Object> config
    ) {
    }

    public record RunSnapshotResponse(
            WorkflowRunRecord run,
            ConsoleRunDetailSummary summary,
            List<WorkflowTaskStatus> tasks,
            List<WorkflowRunEvent> events,
            FailedTaskRerunAction rerunAction
    ) {
    }
}
