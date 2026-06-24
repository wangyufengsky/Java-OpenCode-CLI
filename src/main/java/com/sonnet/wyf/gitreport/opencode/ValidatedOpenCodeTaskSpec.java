package com.sonnet.wyf.gitreport.opencode;

import java.nio.file.Path;

public record ValidatedOpenCodeTaskSpec(
        OpenCodeServerHandle server,
        Path repo,
        String title,
        Path promptFile,
        String message,
        Path runDir,
        ValidationProbe validationProbe,
        String sessionModel,
        int createSessionTimeoutSeconds,
        int requestTimeoutSeconds,
        int pollMillis,
        int timeoutMinutes,
        int validationSettleSeconds,
        int validationMaxCorrections
) {
}
