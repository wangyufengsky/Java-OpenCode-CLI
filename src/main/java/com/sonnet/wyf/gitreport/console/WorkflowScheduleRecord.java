package com.sonnet.wyf.gitreport.console;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Map;

public record WorkflowScheduleRecord(
        long id,
        String chainId,
        String mode,
        String rerunType,
        String rerunId,
        LocalDate runDate,
        Map<String, Object> config,
        ScheduleFrequency frequency,
        Integer dayOfWeek,
        LocalTime runTime,
        LocalDateTime runAt,
        boolean enabled,
        Instant lastTriggeredAt,
        Instant nextTriggerAt,
        Instant createdAt,
        Instant updatedAt
) {
}
