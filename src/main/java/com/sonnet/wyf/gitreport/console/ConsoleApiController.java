package com.sonnet.wyf.gitreport.console;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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

    public ConsoleApiController(
            ChainCatalog chainCatalog,
            WorkflowRunRepository repository,
            WorkflowExecutionService executionService,
            EventStreamService eventStreamService,
            WorkflowScheduleService scheduleService
    ) {
        this.chainCatalog = chainCatalog;
        this.repository = repository;
        this.executionService = executionService;
        this.eventStreamService = eventStreamService;
        this.scheduleService = scheduleService;
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

    @GetMapping("/schedules")
    public List<WorkflowScheduleRecord> schedules() {
        return scheduleService.list();
    }

    @PostMapping("/schedules")
    public Map<String, Long> createSchedule(@RequestBody WorkflowScheduleRequest request) {
        return Map.of("id", scheduleService.create(request));
    }

    @PostMapping("/schedules/{id}/enabled")
    public WorkflowScheduleRecord setScheduleEnabled(@PathVariable long id, @RequestBody Map<String, Boolean> body) {
        return scheduleService.setEnabled(id, Boolean.TRUE.equals(body.get("enabled")));
    }

    @GetMapping("/runs/{id}")
    public WorkflowRunRecord run(@PathVariable long id) {
        return repository.findRun(id).orElseThrow();
    }

    @GetMapping(path = "/runs/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable long id) throws Exception {
        return eventStreamService.subscribe(id, repository.listEvents(id));
    }

    private void validate(WorkflowRunSubmission submission) {
        chainCatalog.chain(submission.chainId());
        String mode = submission.mode() == null || submission.mode().isBlank() ? "full" : submission.mode();
        if (!"full".equals(mode) && !"rerun".equals(mode)) {
            throw new IllegalArgumentException("运行模式必须是 full 或 rerun");
        }
        if ("rerun".equals(mode)) {
            if (submission.rerunType() == null || submission.rerunType().isBlank()) {
                throw new IllegalArgumentException("重跑模式必须填写重跑类型");
            }
            if (submission.rerunId() == null || submission.rerunId().isBlank()) {
                throw new IllegalArgumentException("重跑模式必须填写重跑 ID");
            }
        }
    }
}
