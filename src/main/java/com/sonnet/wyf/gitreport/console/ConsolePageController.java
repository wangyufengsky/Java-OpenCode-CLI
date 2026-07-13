package com.sonnet.wyf.gitreport.console;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Controller
public class ConsolePageController {
    private final ChainCatalog chainCatalog;
    private final WorkflowRunRepository repository;
    private final WorkflowScheduleService scheduleService;
    private final ConsoleViewService viewService;

    public ConsolePageController(
            ChainCatalog chainCatalog,
            WorkflowRunRepository repository,
            WorkflowScheduleService scheduleService,
            ConsoleViewService viewService
    ) {
        this.chainCatalog = chainCatalog;
        this.repository = repository;
        this.scheduleService = scheduleService;
        this.viewService = viewService;
    }

    @GetMapping("/")
    public String dashboard(Model model) {
        model.addAttribute("chains", chainCatalog.chainIds());
        model.addAttribute("runs", repository.listRuns());
        model.addAttribute("summary", viewService.dashboard());
        return "dashboard";
    }

    @GetMapping("/runs/new")
    public String newRun(
            @RequestParam(required = false, defaultValue = "git-code-contribution-report") String chainId,
            @RequestParam(required = false, defaultValue = "full") String mode,
            @RequestParam(required = false, defaultValue = "") String rerunType,
            @RequestParam(required = false, defaultValue = "") String rerunId,
            @RequestParam(required = false) Long copyFrom,
            Model model
    ) {
        WorkflowRunRecord copiedRun = copyFrom == null ? null : repository.findRun(copyFrom).orElseThrow();
        String selectedChainId = copiedRun == null ? chainId : copiedRun.chainId();
        String requestedMode = copiedRun == null ? mode : copiedRun.mode();
        String requestedRerunType = copiedRun == null ? rerunType : copiedRun.rerunType();
        String requestedRerunId = copiedRun == null ? rerunId : copiedRun.rerunId();
        String selectedMode = "rerun".equals(requestedMode) ? "rerun" : "full";
        String selectedRerunType = WorkflowRerunContract.normalizeType(selectedChainId, requestedRerunType);
        if (!"rerun".equals(selectedMode) || !WorkflowRerunContract.isKnownType(selectedChainId, selectedRerunType)) {
            selectedRerunType = "";
        }
        String selectedRerunId = WorkflowRerunContract.requiresRerunId(selectedChainId, selectedRerunType)
                ? trimmed(requestedRerunId)
                : "";
        model.addAttribute("chains", chainCatalog.chainIds());
        model.addAttribute("chainId", selectedChainId);
        model.addAttribute("rerunTypes", WorkflowRerunContract.typeOptions(selectedChainId));
        model.addAttribute("selectedMode", selectedMode);
        model.addAttribute("selectedRerunType", selectedRerunType);
        model.addAttribute("selectedRerunId", selectedRerunId);
        model.addAttribute("selectedRunDate", copiedRun == null ? null : copiedRun.runDate());
        model.addAttribute("copiedRun", copiedRun);
        return "run-new";
    }

    @GetMapping("/runs/{id}")
    public String runDetail(
            @PathVariable long id,
            @RequestParam(required = false, defaultValue = "all") String eventFilter,
            Model model
    ) {
        WorkflowRunRecord run = repository.findRun(id).orElseThrow();
        List<WorkflowRunEvent> allEvents = repository.listEvents(id);
        List<WorkflowTaskStatus> tasks = new ArrayList<>(repository.listTaskStatuses(id));
        tasks.sort(Comparator.comparing((WorkflowTaskStatus task) -> !"FAILED".equalsIgnoreCase(task.state()))
                .thenComparing(WorkflowTaskStatus::taskKey));
        model.addAttribute("run", run);
        List<WorkflowRunEvent> visibleEvents = filterEvents(allEvents, eventFilter);
        model.addAttribute("events", allEvents);
        model.addAttribute("visibleEventIds", visibleEvents.stream().map(WorkflowRunEvent::id).collect(java.util.stream.Collectors.toSet()));
        model.addAttribute("visibleEventsEmpty", visibleEvents.isEmpty());
        model.addAttribute("eventFilter", eventFilter);
        model.addAttribute("tasks", tasks);
        ConsoleRunDetailSummary summary = viewService.runDetail(id);
        String failedTaskPhase = tasks.stream()
                .filter(task -> task.taskKey().equals(summary.failedTaskKey()))
                .map(WorkflowTaskStatus::phase)
                .findFirst()
                .orElse(null);
        model.addAttribute("summary", summary);
        model.addAttribute("failedTaskRerunType", summary.failedTaskKey() == null
                ? null
                : WorkflowRerunContract.failedTaskRerunType(run.chainId(), failedTaskPhase).orElse(null));
        model.addAttribute("stages", classifyStages(run, allEvents, tasks));
        return "run-detail";
    }

    @GetMapping("/history")
    public String history(
            @RequestParam(required = false, defaultValue = "") String q,
            @RequestParam(required = false, defaultValue = "") String state,
            @RequestParam(required = false, defaultValue = "") String chainId,
            @RequestParam(name = "from", required = false, defaultValue = "") String createdFrom,
            @RequestParam(name = "until", required = false, defaultValue = "") String createdUntil,
            Model model
    ) {
        WorkflowRunFilter filter = new WorkflowRunFilter(
                q,
                parseRunState(state),
                chainId,
                parseDate(createdFrom),
                parseDate(createdUntil)
        );
        model.addAttribute("runs", repository.listRuns(filter));
        model.addAttribute("filter", filter);
        model.addAttribute("chains", chainCatalog.chainIds());
        model.addAttribute("runStates", RunState.values());
        return "history";
    }

    @GetMapping("/schedules")
    public String schedules(@RequestParam(required = false, defaultValue = "git-code-contribution-report") String chainId, Model model) {
        model.addAttribute("chains", chainCatalog.chainIds());
        model.addAttribute("chainId", chainId);
        model.addAttribute("rerunTypes", WorkflowRerunContract.typeOptions(chainId));
        model.addAttribute("schedules", scheduleService.list());
        return "schedules";
    }

    private static List<WorkflowRunEvent> filterEvents(List<WorkflowRunEvent> events, String filter) {
        return switch (filter) {
            case "failed" -> events.stream().filter(event -> normalized(event.eventType()).contains("FAILED")).toList();
            case "task" -> events.stream().filter(event -> normalized(event.eventType()).startsWith("TASK_")).toList();
            default -> events;
        };
    }

    private static List<Map<String, String>> classifyStages(
            WorkflowRunRecord run,
            List<WorkflowRunEvent> events,
            List<WorkflowTaskStatus> tasks
    ) {
        Set<String> eventTypes = events.stream().map(event -> normalized(event.eventType())).collect(java.util.stream.Collectors.toSet());
        boolean queuedObserved = run.state() == RunState.QUEUED
                || eventTypes.stream().anyMatch(type -> type.contains("QUEUED"));
        boolean executionObserved = run.state() == RunState.RUNNING
                || eventTypes.stream().anyMatch(type -> type.contains("STARTED") || type.contains("RUNNING"))
                || tasks.stream().map(WorkflowTaskStatus::phase).anyMatch(ConsolePageController::isExecutionPhase);
        boolean taskObserved = !tasks.isEmpty() || eventTypes.stream().anyMatch(type -> type.startsWith("TASK_"));

        return List.of(
                stage("提交", "已观察"),
                stage("排队", run.state() == RunState.QUEUED ? "进行中" : queuedObserved ? "已观察" : "状态未知"),
                stage("执行", run.state() == RunState.RUNNING ? "进行中" : executionObserved ? "已观察" : "状态未知"),
                stage("任务", taskState(tasks, eventTypes, taskObserved)),
                stage("完成", terminalState(run.state()))
        );
    }

    private static Map<String, String> stage(String name, String status) {
        return Map.of("name", name, "status", status);
    }

    private static String taskState(List<WorkflowTaskStatus> tasks, Set<String> eventTypes, boolean observed) {
        if (eventTypes.contains("TASK_FAILED") || tasks.stream().anyMatch(task -> hasTaskState(task, "FAILED"))) {
            return "已失败";
        }
        if (tasks.stream().anyMatch(task -> hasTaskState(task, "RUNNING", "QUEUED"))) {
            return "进行中";
        }
        if (eventTypes.contains("TASK_GROUP_SUCCEEDED")) {
            return "已完成";
        }
        if (!tasks.isEmpty() && tasks.stream().allMatch(task -> hasTaskState(task, "SUCCEEDED"))) {
            return "已完成";
        }
        if (eventTypes.contains("TASK_RUNNING") || eventTypes.contains("TASK_QUEUED")) {
            return "进行中";
        }
        return observed ? "已观察" : "未开始";
    }

    private static boolean hasTaskState(WorkflowTaskStatus task, String... states) {
        return Set.of(states).contains(normalized(task.state()));
    }

    private static String terminalState(RunState state) {
        return switch (state) {
            case SUCCEEDED -> "已完成";
            case FAILED -> "已失败";
            default -> "未开始";
        };
    }

    private static boolean isExecutionPhase(String phase) {
        return Set.of("started", "submitted", "running").contains(normalized(phase).toLowerCase(Locale.ROOT));
    }

    private static String normalized(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT);
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }

    private static RunState parseRunState(String value) {
        try {
            return RunState.valueOf(trimmed(value).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(trimmed(value));
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }
}
