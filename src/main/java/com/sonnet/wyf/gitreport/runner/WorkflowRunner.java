package com.sonnet.wyf.gitreport.runner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public class WorkflowRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(WorkflowRunner.class);

    private final OpenCodeRunnerProperties properties;
    private final Map<String, WorkflowChain> chains;

    public WorkflowRunner(OpenCodeRunnerProperties properties, List<WorkflowChain> chains) {
        this.properties = properties;
        this.chains = chains.stream().collect(Collectors.toMap(WorkflowChain::id, Function.identity()));
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!properties.isEnabled()) {
            log.info("opencode-runner.enabled=false, skipped workflow orchestration.");
            return;
        }
        String chainId = normalize(properties.getActiveChain());
        WorkflowChain chain = chains.get(chainId);
        if (chain == null) {
            throw new IllegalArgumentException("Unknown opencode-runner.active-chain: " + properties.getActiveChain() + ", available=" + chains.keySet());
        }
        WorkflowRunRequest request = new WorkflowRunRequest(
                normalize(properties.getMode()),
                normalize(properties.getRerun().getType()),
                properties.getRerun().getId(),
                properties.getRunDate(),
                properties.getOpencode(),
                properties.getConfigDir()
        );
        log.info("Starting workflow chain: id={}, mode={}, rerunType={}, rerunId={}, runDate={}",
                chain.id(), request.mode(), request.rerunType(), request.rerunId(), request.effectiveRunDate());
        chain.run(request);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
