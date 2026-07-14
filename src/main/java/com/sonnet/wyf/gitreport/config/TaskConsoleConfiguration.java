package com.sonnet.wyf.gitreport.config;

import com.sonnet.wyf.gitreport.console.ChainCatalog;
import com.sonnet.wyf.gitreport.console.ConsoleViewService;
import com.sonnet.wyf.gitreport.console.EventStreamService;
import com.sonnet.wyf.gitreport.console.PathPreflightService;
import com.sonnet.wyf.gitreport.console.RunConfigReader;
import com.sonnet.wyf.gitreport.console.RunConfigWriter;
import com.sonnet.wyf.gitreport.console.TaskConsoleProperties;
import com.sonnet.wyf.gitreport.console.WorkflowEventSink;
import com.sonnet.wyf.gitreport.console.WorkflowExecutionService;
import com.sonnet.wyf.gitreport.console.WorkflowScheduleRepository;
import com.sonnet.wyf.gitreport.console.WorkflowScheduleService;
import com.sonnet.wyf.gitreport.console.WorkflowScheduleServiceFactory;
import com.sonnet.wyf.gitreport.console.WorkflowRunRepository;
import com.sonnet.wyf.gitreport.console.WorkflowRunSchema;
import com.sonnet.wyf.gitreport.console.VisualQaDatabaseGuard;
import com.sonnet.wyf.gitreport.runner.AgentBridgeRunnerProperties;
import com.sonnet.wyf.gitreport.runner.WorkflowChain;
import org.sqlite.SQLiteDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;

@Configuration
public class TaskConsoleConfiguration {
    @Bean
    DataSource taskConsoleDataSource(TaskConsoleProperties properties, Environment environment) throws Exception {
        if (environment.matchesProfiles("visual-qa")) {
            VisualQaDatabaseGuard.requireDisposablePath(properties.getDatabasePath());
        }
        if (properties.getDatabasePath().getParent() != null) {
            Files.createDirectories(properties.getDatabasePath().getParent());
        }
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + properties.getDatabasePath());
        return dataSource;
    }

    @Bean
    JdbcTemplate taskConsoleJdbcTemplate(DataSource taskConsoleDataSource) {
        return new JdbcTemplate(taskConsoleDataSource);
    }

    @Bean
    WorkflowRunSchema workflowRunSchema(JdbcTemplate taskConsoleJdbcTemplate) {
        WorkflowRunSchema schema = new WorkflowRunSchema(taskConsoleJdbcTemplate);
        schema.initialize();
        return schema;
    }

    @Bean
    WorkflowRunRepository workflowRunRepository(JdbcTemplate taskConsoleJdbcTemplate, WorkflowRunSchema workflowRunSchema) {
        return new WorkflowRunRepository(taskConsoleJdbcTemplate);
    }

    @Bean
    WorkflowScheduleRepository workflowScheduleRepository(JdbcTemplate taskConsoleJdbcTemplate, WorkflowRunSchema workflowRunSchema) {
        return new WorkflowScheduleRepository(taskConsoleJdbcTemplate);
    }

    @Bean
    ChainCatalog chainCatalog(ResourceLoader resourceLoader, AgentBridgeRunnerProperties properties, List<WorkflowChain> chains) {
        return new ChainCatalog(resourceLoader, properties, chains);
    }

    @Bean
    EventStreamService eventStreamService() {
        return new EventStreamService();
    }

    @Bean
    WorkflowEventSink workflowEventSink(WorkflowRunRepository repository, EventStreamService eventStreamService) {
        return new WorkflowEventSink(repository, eventStreamService);
    }

    @Bean
    RunConfigWriter runConfigWriter(TaskConsoleProperties properties) {
        return new RunConfigWriter(properties);
    }

    @Bean
    RunConfigReader runConfigReader() {
        return new RunConfigReader();
    }

    @Bean
    PathPreflightService pathPreflightService() {
        return new PathPreflightService();
    }

    @Bean
    Clock taskConsoleClock(TaskConsoleProperties properties) {
        return properties.getClockInstant() == null
                ? Clock.systemDefaultZone()
                : Clock.fixed(properties.getClockInstant(), ZoneOffset.UTC);
    }

    @Bean
    ConsoleViewService consoleViewService(WorkflowRunRepository repository, Clock taskConsoleClock) {
        return new ConsoleViewService(repository, taskConsoleClock);
    }

    @Bean(destroyMethod = "close")
    WorkflowExecutionService workflowExecutionService(
            ChainCatalog chainCatalog,
            WorkflowRunRepository repository,
            WorkflowEventSink eventSink,
            RunConfigWriter configWriter,
            AgentBridgeRunnerProperties runnerProperties,
            TaskConsoleProperties consoleProperties
    ) {
        if (!consoleProperties.isExecutionEnabled()) {
            return new WorkflowExecutionService(chainCatalog, repository, eventSink, configWriter, runnerProperties) {
                @Override
                public long submit(com.sonnet.wyf.gitreport.console.WorkflowRunSubmission submission) {
                    throw new IllegalStateException("visual-qa disables workflow execution");
                }
            };
        }
        return new WorkflowExecutionService(chainCatalog, repository, eventSink, configWriter, runnerProperties);
    }

    @Bean(destroyMethod = "close")
    WorkflowScheduleService workflowScheduleService(
            WorkflowScheduleRepository repository,
            WorkflowExecutionService executionService,
            ChainCatalog chainCatalog,
            Clock taskConsoleClock,
            TaskConsoleProperties properties,
            WorkflowScheduleServiceFactory scheduleServiceFactory
    ) {
        return scheduleServiceFactory.create(repository, executionService, chainCatalog, taskConsoleClock, properties.isSchedulerEnabled());
    }
}
