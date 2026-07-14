package com.sonnet.wyf.gitreport.console;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("visual-qa")
class VisualQaFixtureInitializerTest {
    @Autowired
    TaskConsoleProperties properties;

    @Autowired
    Clock taskConsoleClock;

    @Autowired
    WorkflowRunRepository runRepository;

    @Autowired
    WorkflowScheduleRepository scheduleRepository;

    @Autowired
    WorkflowExecutionService workflowExecutionService;

    @Autowired
    VisualQaFixtureInitializer fixtureInitializer;

    @Value("${agentbridge-runner.enabled}")
    boolean agentBridgeRunnerEnabled;

    @Test
    void visualQaProfileUsesOnlyItsFixedDatabaseAndSeedsDeterministicFixtures() {
        assertThat(properties.getDatabasePath()).isEqualTo(java.nio.file.Path.of("target/visual-qa.sqlite"));
        assertThat(properties.isSchedulerEnabled()).isFalse();
        assertThat(properties.isExecutionEnabled()).isFalse();
        assertThat(agentBridgeRunnerEnabled).isFalse();
        assertThat(taskConsoleClock.instant()).isEqualTo(java.time.Instant.parse("2026-07-13T00:00:00Z"));
        assertThat(runRepository.listRuns()).hasSize(3);
        assertThat(scheduleRepository.listSchedules()).hasSize(2);
        assertThatThrownBy(() -> workflowExecutionService.submit(new WorkflowRunSubmission(
                "git-code-contribution-report", "full", null, null, null, java.util.Map.of(), null
        ))).isInstanceOf(IllegalStateException.class)
                .hasMessage("visual-qa disables workflow execution");
    }

    @Test
    void reseedingRestoresStableRunAndScheduleIdentifiers() throws Exception {
        fixtureInitializer.run(new DefaultApplicationArguments());
        var firstRunIds = runRepository.listRuns().stream().map(WorkflowRunRecord::id).toList();
        var firstScheduleIds = scheduleRepository.listSchedules().stream().map(WorkflowScheduleRecord::id).toList();

        fixtureInitializer.run(new DefaultApplicationArguments());

        assertThat(runRepository.listRuns().stream().map(WorkflowRunRecord::id).toList()).isEqualTo(firstRunIds);
        assertThat(scheduleRepository.listSchedules().stream().map(WorkflowScheduleRecord::id).toList()).isEqualTo(firstScheduleIds);
        assertThat(firstRunIds).containsExactly(3L, 2L, 1L);
        assertThat(firstScheduleIds).containsExactly(2L, 1L);
    }
}
