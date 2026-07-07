package com.sonnet.wyf.gitreport.agentbridge;

import java.net.URI;
import java.nio.file.Path;

public record ValidatedAgentBridgeTaskSpec(
        Path repo,
        String title,
        Path promptFile,
        String message,
        Path runDir,
        ValidationProbe validationProbe,
        int pollMillis,
        int timeoutMinutes,
        int validationSettleSeconds,
        int validationMaxCorrections,
        URI webBaseUrl
) {
}
