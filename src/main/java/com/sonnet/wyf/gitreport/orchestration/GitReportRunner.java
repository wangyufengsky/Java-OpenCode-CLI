package com.sonnet.wyf.gitreport.orchestration;

import com.sonnet.wyf.gitreport.GitReportProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class GitReportRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(GitReportRunner.class);

    private final GitReportProperties properties;
    private final GitReportOrchestrator orchestrator;

    public GitReportRunner(GitReportProperties properties, GitReportOrchestrator orchestrator) {
        this.properties = properties;
        this.orchestrator = orchestrator;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (properties.isEnabled()) {
            String mode = properties.getRuntime().getMode() == null ? "full" : properties.getRuntime().getMode().toLowerCase(Locale.ROOT);
            if ("full".equals(mode)) {
                orchestrator.run(properties);
            } else if ("synthesis-only".equals(mode)) {
                orchestrator.runSynthesisOnly(properties);
            } else {
                throw new IllegalArgumentException("git-report.runtime.mode must be one of: full, synthesis-only");
            }
            return;
        }
        log.info("git-report.enabled=false, skipped git report orchestration. Enable it in application.yml, or run with --spring.profiles.active=example / --git-report.enabled=true.");
    }
}
