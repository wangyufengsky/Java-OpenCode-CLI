package com.sonnet.wyf.gitreport.console;

import org.springframework.stereotype.Component;

import java.util.Map;

@Component("consoleText")
public class ConsoleText {
    private static final Map<String, String> CHAINS = Map.of(
            "git-code-contribution-report", "代码贡献报告",
            "smartesb-rewrite-code-review", "SmartESB 改造评审",
            "smartesb-code-reader", "SmartESB 代码阅读",
            "weekly-engineering-report", "研发周报"
    );

    private static final Map<String, String> MODES = Map.of(
            "full", "全量",
            "rerun", "重跑"
    );

    private static final Map<String, String> STATES = Map.of(
            "QUEUED", "排队中",
            "RUNNING", "运行中",
            "SUCCEEDED", "已成功",
            "FAILED", "已失败"
    );

    private static final Map<String, String> RERUN_TYPES = Map.of(
            "author", "作者",
            "transaction", "交易",
            "module", "模块",
            "index", "总报告"
    );

    private static final Map<String, String> EVENT_TYPES = Map.of(
            "QUEUED", "已排队",
            "STARTED", "已开始",
            "SUCCEEDED", "已成功",
            "FAILED", "已失败",
            "TASK_GROUP_STARTED", "任务组已开始",
            "TASK_GROUP_SUCCEEDED", "任务组已完成",
            "TASK_QUEUED", "任务已排队",
            "TASK_RUNNING", "任务运行中",
            "TASK_SUCCEEDED", "任务已成功",
            "TASK_FAILED", "任务已失败"
    );

    private static final Map<String, String> FREQUENCIES = Map.of(
            "DAILY", "每天",
            "WEEKLY", "每周",
            "ONCE", "一次性"
    );

    private static final Map<String, String> PHASES = Map.of(
            "started", "已开始",
            "submitted", "已提交",
            "queued", "排队中",
            "running", "运行中",
            "idle", "空闲",
            "complete", "已完成",
            "completed", "已完成",
            "timeout", "已超时",
            "failed", "已失败"
    );

    public String chain(String value) {
        return CHAINS.getOrDefault(value, value);
    }

    public String mode(String value) {
        return MODES.getOrDefault(value, value);
    }

    public String state(RunState value) {
        return value == null ? "" : state(value.name());
    }

    public String state(String value) {
        return STATES.getOrDefault(value, value);
    }

    public String rerunType(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        return RERUN_TYPES.getOrDefault(value, value);
    }

    public String eventType(String value) {
        return EVENT_TYPES.getOrDefault(value, value);
    }

    public String frequency(String value) {
        return FREQUENCIES.getOrDefault(value, value);
    }

    public String phase(String value) {
        return PHASES.getOrDefault(value, value);
    }
}
