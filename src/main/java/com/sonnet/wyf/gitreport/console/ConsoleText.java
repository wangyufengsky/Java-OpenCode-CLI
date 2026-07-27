package com.sonnet.wyf.gitreport.console;

import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.Map;

@Component("consoleText")
public class ConsoleText {
    private static final DateTimeFormatter SCHEDULE_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final Map<String, String> CHAINS = Map.of(
            "git-code-contribution-report", "代码贡献报告",
            "smartesb-rewrite-code-review", "SmartESB 改造评审",
            "smartesb-code-reader", "SmartESB 代码阅读",
            "weekly-engineering-report", "研发周报",
            "project-unit-test-generation", "单元测试生成",
            "mybatis-sql-review", "MyBatis SQL 审查"
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
            "index", "总报告",
            "synthesis", "总报告",
            "review-batch", "审查批次",
            "test-batch", "测试批次",
            "verification", "验证",
            "sql", "SQL 语句",
            "xml", "Mapper XML"
    );

    private static final Map<String, String> EVENT_TYPES = Map.ofEntries(
            Map.entry("QUEUED", "已排队"),
            Map.entry("STARTED", "已开始"),
            Map.entry("SUCCEEDED", "已成功"),
            Map.entry("FAILED", "已失败"),
            Map.entry("TASK_GROUP_STARTED", "任务组已开始"),
            Map.entry("TASK_GROUP_SUCCEEDED", "任务组已完成"),
            Map.entry("TASK_QUEUED", "任务已排队"),
            Map.entry("TASK_RUNNING", "任务运行中"),
            Map.entry("TASK_SUCCEEDED", "任务已成功"),
            Map.entry("TASK_FAILED", "任务已失败"),
            Map.entry("SESSION_FAILED", "会话已失败")
    );

    private static final Map<String, String> FREQUENCIES = Map.of(
            "DAILY", "每天",
            "WEEKLY", "每周",
            "ONCE", "一次性"
    );

    private static final Map<String, String> PHASES = Map.ofEntries(
            Map.entry("started", "已开始"),
            Map.entry("submitted", "已提交"),
            Map.entry("queued", "排队中"),
            Map.entry("running", "运行中"),
            Map.entry("idle", "空闲"),
            Map.entry("complete", "已完成"),
            Map.entry("completed", "已完成"),
            Map.entry("timeout", "已超时"),
            Map.entry("failed", "已失败"),
            Map.entry("session_failed", "会话失败，正在重试")
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

    public String scheduleTitle(WorkflowScheduleRecord schedule) {
        return chain(schedule.chainId()) + " · " + mode(schedule.mode()) + " #" + schedule.id();
    }

    public String scheduleFrequency(WorkflowScheduleRecord schedule) {
        if (schedule.frequency() == null) {
            return "—";
        }
        return switch (schedule.frequency()) {
            case DAILY -> "每天 · " + schedule.runTime();
            case WEEKLY -> "每周" + weekday(schedule.dayOfWeek()) + " · " + schedule.runTime();
            case ONCE -> schedule.runAt() == null ? "一次性" : "一次性 · " + SCHEDULE_DATE_TIME.format(schedule.runAt());
        };
    }

    public String phase(String value) {
        return PHASES.getOrDefault(value, value);
    }

    private static String weekday(Integer dayOfWeek) {
        return switch (dayOfWeek == null ? 0 : dayOfWeek) {
            case 1 -> "一";
            case 2 -> "二";
            case 3 -> "三";
            case 4 -> "四";
            case 5 -> "五";
            case 6 -> "六";
            case 7 -> "日";
            default -> "";
        };
    }
}
