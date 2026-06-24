package com.sonnet.wyf.gitreport.orchestration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ConcurrentWorkflowTaskRunnerTest {
    private ThreadPoolTaskExecutor executor;

    @AfterEach
    void shutdownExecutor() {
        if (executor != null) {
            executor.shutdown();
        }
    }

    @Test
    void enforcesConfiguredConcurrencyLimit() throws Exception {
        ConcurrentWorkflowTaskRunner runner = new ConcurrentWorkflowTaskRunner(executor(4));
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();

        List<TaskRunResult> results = runner.run(
                "test workflow",
                List.of("a", "b", "c", "d"),
                2,
                item -> item,
                item -> () -> {
                    int running = active.incrementAndGet();
                    maxActive.accumulateAndGet(running, Math::max);
                    try {
                        Thread.sleep(100);
                        return TaskRunResult.success(item, item, Path.of("status-" + item + ".json"));
                    } finally {
                        active.decrementAndGet();
                    }
                }
        );

        assertThat(results).hasSize(4).allMatch(TaskRunResult::success);
        assertThat(maxActive).hasValue(2);
    }

    @Test
    void collectsFailuresWithoutDroppingSuccessfulTasks() throws Exception {
        ConcurrentWorkflowTaskRunner runner = new ConcurrentWorkflowTaskRunner(executor(2));

        List<TaskRunResult> results = runner.run(
                "test workflow",
                List.of("ok", "bad"),
                2,
                item -> item,
                item -> () -> "bad".equals(item)
                        ? TaskRunResult.failed(item, item, Path.of("bad-status.json"), "validation failed")
                        : TaskRunResult.success(item, item, Path.of("ok-status.json"))
        );

        assertThat(results).extracting(TaskRunResult::taskKey).containsExactly("ok", "bad");
        assertThat(results).filteredOn(TaskRunResult::success).hasSize(1);
        assertThat(results).filteredOn(result -> !result.success())
                .singleElement()
                .satisfies(result -> assertThat(result.error()).isEqualTo("validation failed"));
    }

    @Test
    void convertsCallableExceptionsIntoFailedResults() throws Exception {
        ConcurrentWorkflowTaskRunner runner = new ConcurrentWorkflowTaskRunner(executor(2));
        ConcurrentLinkedQueue<String> executed = new ConcurrentLinkedQueue<>();

        List<TaskRunResult> results = runner.run(
                "test workflow",
                List.of("throws", "ok"),
                2,
                item -> item,
                item -> () -> {
                    executed.add(item);
                    if ("throws".equals(item)) {
                        throw new IllegalStateException("boom");
                    }
                    return TaskRunResult.success(item, item, Path.of("ok-status.json"));
                }
        );

        assertThat(executed).containsExactlyInAnyOrder("throws", "ok");
        assertThat(results).filteredOn(result -> !result.success())
                .singleElement()
                .satisfies(result -> assertThat(result.error()).contains("IllegalStateException: boom"));
    }

    private ThreadPoolTaskExecutor executor(int concurrency) {
        executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("test-workflow-task-");
        executor.setCorePoolSize(concurrency);
        executor.setMaxPoolSize(concurrency);
        executor.initialize();
        return executor;
    }
}
