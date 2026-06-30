package com.sonnet.wyf.gitreport.console;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Map;

public record WorkflowScheduleRequest(
        String chainId,
        String mode,
        String rerunType,
        String rerunId,
        LocalDate runDate,
        Map<String, Object> config,
        String frequency,
        Integer dayOfWeek,
        LocalTime runTime,
        LocalDateTime runAt,
        boolean enabled
) {
}
