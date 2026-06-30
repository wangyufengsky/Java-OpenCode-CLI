package com.sonnet.wyf.gitreport.console;

import java.util.Locale;

public enum ScheduleFrequency {
    DAILY,
    WEEKLY,
    ONCE;

    public static ScheduleFrequency parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("定时频率不能为空");
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "daily" -> DAILY;
            case "weekly" -> WEEKLY;
            case "once" -> ONCE;
            default -> throw new IllegalArgumentException("不支持的定时频率: " + value);
        };
    }
}
