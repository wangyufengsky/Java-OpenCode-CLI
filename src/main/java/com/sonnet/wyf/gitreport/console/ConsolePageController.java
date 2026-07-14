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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Controller
public class ConsolePageController {
    private static final int HISTORY_PAGE_SIZE = 20;
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
        ConsoleDashboardView dashboard = viewService.dashboardView();
        model.addAttribute("dashboardMetrics", dashboard.metrics());
        model.addAttribute("dashboardRuns", dashboard.runs());
        model.addAttribute("dashboardAttentionRuns", dashboard.attentionRuns());
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
        model.addAttribute("summary", summary);
        model.addAttribute("rerunAction", WorkflowRerunContract.failedTaskAction(run.chainId(), summary, tasks));
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
            @RequestParam(required = false, defaultValue = "1") int page,
            Model model
    ) {
        WorkflowRunFilter filter = new WorkflowRunFilter(
                q,
                parseRunState(state),
                chainId,
                parseDate(createdFrom),
                parseDate(createdUntil)
        );
        long total = repository.countRuns(filter);
        int totalPages = Math.max(1, (int) Math.ceil(total / (double) HISTORY_PAGE_SIZE));
        int normalizedPage = Math.min(Math.max(1, page), totalPages);
        List<ConsoleRunListItemView> runs = viewService.historyRuns(repository.listRuns(
                filter,
                HISTORY_PAGE_SIZE,
                (normalizedPage - 1) * HISTORY_PAGE_SIZE
        ));
        ConsolePage<ConsoleRunListItemView> historyPage = new ConsolePage<>(
                normalizedPage, HISTORY_PAGE_SIZE, total, totalPages, runs
        );
        model.addAttribute("runs", historyPage.items());
        model.addAttribute("historyPage", historyPage);
        model.addAttribute("filter", filter);
        model.addAttribute("chains", chainCatalog.chainIds());
        model.addAttribute("runStates", RunState.values());
        model.addAttribute("clearFiltersUrl", canonicalUrl("/history", WorkflowRunFilter.empty(), 1, Map.of()));
        model.addAttribute("previousPageUrl", canonicalUrl("/history", filter, normalizedPage - 1, Map.of()));
        model.addAttribute("nextPageUrl", canonicalUrl("/history", filter, normalizedPage + 1, Map.of()));
        Map<Long, String> detailUrls = new LinkedHashMap<>();
        Map<Long, String> copyUrls = new LinkedHashMap<>();
        for (ConsoleRunListItemView run : runs) {
            detailUrls.put(run.id(), canonicalUrl("/runs/" + run.id(), filter, normalizedPage, Map.of()));
            copyUrls.put(run.id(), canonicalUrl("/runs/new", filter, normalizedPage, Map.of("copyFrom", String.valueOf(run.id()))));
        }
        model.addAttribute("historyDetailUrls", detailUrls);
        model.addAttribute("historyCopyUrls", copyUrls);
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

    private static String canonicalUrl(
            String path,
            WorkflowRunFilter filter,
            int page,
            Map<String, String> extraParameters
    ) {
        List<String> parameters = new ArrayList<>();
        extraParameters.forEach((name, value) -> addParameter(parameters, name, value));
        addParameter(parameters, "q", filter.query());
        if (filter.state() != null) {
            addParameter(parameters, "state", filter.state().name());
        }
        addParameter(parameters, "chainId", filter.chainId());
        if (filter.createdFrom() != null) {
            addParameter(parameters, "from", filter.createdFrom().toString());
        }
        if (filter.createdUntil() != null) {
            addParameter(parameters, "until", filter.createdUntil().toString());
        }
        if (page > 1) {
            addParameter(parameters, "page", String.valueOf(page));
        }
        return parameters.isEmpty() ? path : path + "?" + String.join("&", parameters);
    }

    private static void addParameter(List<String> parameters, String name, String value) {
        if (value != null && !value.isBlank()) {
            parameters.add(URLEncoder.encode(name, StandardCharsets.UTF_8) + "="
                    + URLEncoder.encode(value, StandardCharsets.UTF_8));
        }
    }
}
