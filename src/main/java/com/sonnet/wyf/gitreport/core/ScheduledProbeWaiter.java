package com.sonnet.wyf.gitreport.core;

import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Predicate;
import java.util.function.Supplier;

public class ScheduledProbeWaiter {
    private final TaskScheduler taskScheduler;

    public ScheduledProbeWaiter(TaskScheduler taskScheduler) {
        this.taskScheduler = taskScheduler;
    }

    public <T> T waitFor(
            Callable<T> probe,
            Predicate<T> isComplete,
            Supplier<T> timeoutResult,
            Duration timeout,
            Duration interval
    ) throws Exception {
        T initial = probe.call();
        if (isComplete.test(initial)) {
            return initial;
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            return timeoutResult.get();
        }

        CompletableFuture<T> completion = new CompletableFuture<>();
        ScheduledFuture<?> pollFuture = taskScheduler.scheduleAtFixedRate(
                () -> completeFromProbe(probe, isComplete, completion),
                normalizeInterval(interval)
        );
        ScheduledFuture<?> timeoutFuture = taskScheduler.schedule(
                () -> completion.complete(timeoutResult.get()),
                Instant.now().plus(timeout)
        );
        try {
            return completion.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw exception;
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception checked) {
                throw checked;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("scheduled probe failed", cause);
        } finally {
            pollFuture.cancel(false);
            timeoutFuture.cancel(false);
        }
    }

    private <T> void completeFromProbe(
            Callable<T> probe,
            Predicate<T> isComplete,
            CompletableFuture<T> completion
    ) {
        if (completion.isDone()) {
            return;
        }
        try {
            T value = probe.call();
            if (isComplete.test(value)) {
                completion.complete(value);
            }
        } catch (Exception exception) {
            completion.completeExceptionally(exception);
        }
    }

    private Duration normalizeInterval(Duration interval) {
        if (interval == null || interval.isZero() || interval.isNegative()) {
            return Duration.ofMillis(50);
        }
        return interval;
    }
}
