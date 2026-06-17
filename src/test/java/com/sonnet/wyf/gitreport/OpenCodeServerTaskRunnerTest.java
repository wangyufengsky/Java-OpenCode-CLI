package com.sonnet.wyf.gitreport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.core.ScheduledProbeWaiter;
import com.sonnet.wyf.gitreport.opencode.OpenCodeRunResult;
import com.sonnet.wyf.gitreport.opencode.OpenCodeServerClient;
import com.sonnet.wyf.gitreport.opencode.OpenCodeServerHandle;
import com.sonnet.wyf.gitreport.opencode.OpenCodeServerTaskRunner;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class OpenCodeServerTaskRunnerTest {
    private HttpServer server;
    private ThreadPoolTaskScheduler taskScheduler;
    private ExecutorService httpExecutor;

    @TempDir
    Path tempDir;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
        if (taskScheduler != null) {
            taskScheduler.shutdown();
        }
        if (httpExecutor != null) {
            httpExecutor.shutdownNow();
        }
    }

    @Test
    void sendsPersistedPromptToSessionAndCompletesByOutputProbe() throws Exception {
        Path output = tempDir.resolve("done.txt");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/session", exchange -> respond(exchange, 200, "{\"id\":\"task-session\"}"));
        server.createContext("/session/task-session/prompt_async", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (body.contains("worker message") && body.contains("persisted prompt")) {
                Files.writeString(output, "done");
            }
            respond(exchange, 204, "");
        });
        server.createContext("/session/task-session/message", exchange -> respond(exchange, 200, """
                [
                    {"id":"msg_1","type":"user","text":"ok","time":{"created":1}},
                    {"id":"msg_2","type":"assistant","agent":"build","model":{"providerID":"test","id":"model"},"content":[],"time":{"created":2}}
                ]
                """));
        server.start();

        Path promptFile = tempDir.resolve("worker-prompt.md");
        Files.writeString(promptFile, "persisted prompt");
        OpenCodeServerTaskRunner runner = new OpenCodeServerTaskRunner(new OpenCodeServerClient(new ObjectMapper()), scheduledProbeWaiter());
        OpenCodeServerHandle handle = new OpenCodeServerHandle(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), false);

        OpenCodeRunResult result = runner.runUntil(
                handle,
                tempDir,
                "test-title",
                promptFile,
                "worker message",
                tempDir.resolve("run"),
                () -> Files.exists(output),
                "",
                60,
                60,
                50,
                1
        );

        assertThat(result.sessionId()).isEqualTo("task-session");
        assertThat(result.serverUrl()).isEqualTo(handle.serverUrl().toString());
        assertThat(result.serverOwnedByJava()).isFalse();
        assertThat(result.timedOut()).isFalse();
        assertThat(result.completedByOutput()).isTrue();
    }

    @Test
    void waitsForOutputWhenAsyncPromptSubmissionReturnsBeforeServerLeavesIdle() throws Exception {
        Path output = tempDir.resolve("delayed-done.txt");
        AtomicInteger statusRequests = new AtomicInteger();
        httpExecutor = Executors.newCachedThreadPool();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(httpExecutor);
        server.createContext("/session", exchange -> respond(exchange, 200, "{\"id\":\"idle-race-session\"}"));
        server.createContext("/session/idle-race-session/prompt_async", exchange -> {
            exchange.getRequestBody().readAllBytes();
            sleep(300);
            Files.writeString(output, "done");
            respond(exchange, 204, "");
        });
        server.createContext("/session/idle-race-session/message", exchange -> {
            statusRequests.incrementAndGet();
            respond(exchange, 200, """
                    [
                        {"id":"msg_1","type":"user","text":"ok","time":{"created":1}},
                        {"id":"msg_2","type":"assistant","agent":"build","model":{"providerID":"test","id":"model"},"content":[],"finish":"stop","time":{"created":2,"completed":3}}
                    ]
                    """);
        });
        server.start();

        Path promptFile = tempDir.resolve("worker-prompt.md");
        Files.writeString(promptFile, "delayed output");
        OpenCodeServerTaskRunner runner = new OpenCodeServerTaskRunner(new OpenCodeServerClient(new ObjectMapper()), scheduledProbeWaiter());
        OpenCodeServerHandle handle = new OpenCodeServerHandle(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), false);

        OpenCodeRunResult result = runner.runUntil(
                handle,
                tempDir,
                "idle-race-title",
                promptFile,
                "worker message",
                tempDir.resolve("idle-race-run"),
                () -> Files.exists(output),
                "",
                60,
                60,
                50,
                1
        );

        assertThat(result.sessionId()).isEqualTo("idle-race-session");
        assertThat(result.completedByOutput()).isTrue();
        assertThat(statusRequests.get()).isGreaterThan(1);
    }

    @Test
    void recordsTimeoutWithoutAbortWhenUsingOpenCodeV2Api() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/session", exchange -> respond(exchange, 200, "{\"id\":\"timeout-session\"}"));
        server.createContext("/session/timeout-session/prompt_async", exchange -> respond(exchange, 204, ""));
        server.createContext("/session/timeout-session/message", exchange -> respond(exchange, 200, """
                [
                    {"id":"msg_1","type":"user","text":"ok","time":{"created":1}},
                    {"id":"msg_2","type":"assistant","agent":"build","model":{"providerID":"test","id":"model"},"content":[],"time":{"created":2}}
                ]
                """));
        server.start();

        Path promptFile = tempDir.resolve("worker-prompt.md");
        Files.writeString(promptFile, "never completes");
        OpenCodeServerTaskRunner runner = new OpenCodeServerTaskRunner(new OpenCodeServerClient(new ObjectMapper()), scheduledProbeWaiter());
        OpenCodeServerHandle handle = new OpenCodeServerHandle(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), true);

        OpenCodeRunResult result = runner.runUntil(
                handle,
                tempDir,
                "test-title",
                promptFile,
                "worker message",
                tempDir.resolve("run"),
                () -> false,
                "",
                60,
                60,
                50,
                0
        );

        assertThat(result.timedOut()).isTrue();
        assertThat(result.aborted()).isFalse();
    }

    @Test
    void writesSessionStatusHeartbeatWhilePolling() throws Exception {
        AtomicInteger statusRequests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/session", exchange -> respond(exchange, 200, "{\"id\":\"heartbeat-session\"}"));
        server.createContext("/session/heartbeat-session/prompt_async", exchange -> respond(exchange, 204, ""));
        server.createContext("/session/heartbeat-session/message", exchange -> {
            statusRequests.incrementAndGet();
            respond(exchange, 200, """
                    [
                        {"id":"msg_1","type":"user","text":"ok","time":{"created":1}},
                        {"id":"msg_2","type":"assistant","agent":"build","model":{"providerID":"test","id":"model"},"content":[],"time":{"created":2}}
                    ]
                    """);
        });
        server.start();

        Path promptFile = tempDir.resolve("worker-prompt.md");
        Path runDir = tempDir.resolve("heartbeat-run");
        Files.writeString(promptFile, "poll for heartbeat");
        OpenCodeServerTaskRunner runner = new OpenCodeServerTaskRunner(new OpenCodeServerClient(new ObjectMapper()), scheduledProbeWaiter());
        OpenCodeServerHandle handle = new OpenCodeServerHandle(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), false);

        OpenCodeRunResult result = runner.runUntil(
                handle,
                tempDir,
                "heartbeat-title",
                promptFile,
                "worker message",
                runDir,
                () -> statusRequests.get() >= 2,
                "",
                60,
                60,
                50,
                1
        );

        assertThat(result.completedByOutput()).isTrue();
        String status = Files.readString(runDir.resolve("session-status.json"));
        assertThat(status).contains("\"sessionId\" : \"heartbeat-session\"");
        assertThat(status).contains("\"title\" : \"heartbeat-title\"");
        assertThat(status).contains("\"phase\" : \"completed_by_output\"");
        assertThat(status).contains("\"serverState\" : \"running\"");
        assertThat(status).contains("\"pollCount\" : 2");
    }

    @Test
    void usesShortCreateTimeoutForSessionRecoveryWithoutShorteningOtherRequests() throws Exception {
        Path output = tempDir.resolve("recovered-done.txt");
        AtomicInteger promptRequests = new AtomicInteger();
        AtomicReference<String> sessionTitle = new AtomicReference<>("late-title");
        ObjectMapper objectMapper = new ObjectMapper();
        httpExecutor = Executors.newCachedThreadPool();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(httpExecutor);
        server.createContext("/session", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                sessionTitle.set(objectMapper.readTree(body).at("/title").asText());
                try {
                    Thread.sleep(1_500);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                respond(exchange, 200, "{\"id\":\"late-session\",\"title\":\"" + sessionTitle.get() + "\"}");
                return;
            }
            respond(exchange, 200, "[{\"id\":\"late-session\",\"title\":\"" + sessionTitle.get() + "\"}]");
        });
        server.createContext("/session/late-session/prompt_async", exchange -> {
            promptRequests.incrementAndGet();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (body.contains("worker message") && body.contains("recover me")) {
                Files.writeString(output, "done");
            }
            respond(exchange, 204, "");
        });
        server.start();

        Path promptFile = tempDir.resolve("worker-prompt.md");
        Files.writeString(promptFile, "recover me");
        OpenCodeServerTaskRunner runner = new OpenCodeServerTaskRunner(new OpenCodeServerClient(objectMapper), scheduledProbeWaiter());
        OpenCodeServerHandle handle = new OpenCodeServerHandle(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), false);

        OpenCodeRunResult result = runner.runUntil(
                handle,
                tempDir,
                "late-title",
                promptFile,
                "worker message",
                tempDir.resolve("recover-run"),
                () -> Files.exists(output),
                "",
                1,
                90,
                50,
                1
        );

        assertThat(result.sessionId()).isEqualTo("late-session");
        assertThat(result.completedByOutput()).isTrue();
        assertThat(promptRequests).hasValue(1);
    }

    @Test
    void continuesPollingWhenPromptAsyncResponseTimesOutAfterSubmission() throws Exception {
        Path output = tempDir.resolve("prompt-timeout-done.txt");
        AtomicInteger promptRequests = new AtomicInteger();
        httpExecutor = Executors.newCachedThreadPool();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(httpExecutor);
        server.createContext("/session", exchange -> respond(exchange, 200, "{\"id\":\"prompt-timeout-session\"}"));
        server.createContext("/session/prompt-timeout-session/prompt_async", exchange -> {
            promptRequests.incrementAndGet();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (body.contains("worker message") && body.contains("submitted before response")) {
                try {
                    Thread.sleep(1_500);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                Files.writeString(output, "done");
            }
            respond(exchange, 204, "");
        });
        server.createContext("/session/prompt-timeout-session/message", exchange -> respond(exchange, 200, """
                [
                    {"id":"msg_1","type":"user","text":"ok","time":{"created":1}}
                ]
                """));
        server.start();

        Path promptFile = tempDir.resolve("worker-prompt.md");
        Files.writeString(promptFile, "submitted before response");
        OpenCodeServerTaskRunner runner = new OpenCodeServerTaskRunner(new OpenCodeServerClient(new ObjectMapper()), scheduledProbeWaiter());
        OpenCodeServerHandle handle = new OpenCodeServerHandle(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), false);

        OpenCodeRunResult result = runner.runUntil(
                handle,
                tempDir,
                "prompt-timeout-title",
                promptFile,
                "worker message",
                tempDir.resolve("prompt-timeout-run"),
                () -> Files.exists(output),
                "",
                60,
                1,
                50,
                1
        );

        assertThat(result.sessionId()).isEqualTo("prompt-timeout-session");
        assertThat(result.completedByOutput()).isTrue();
        assertThat(promptRequests).hasValue(1);
    }

    @Test
    void pollingUsesSpringTaskSchedulerInsteadOfThreadSleep() throws Exception {
        Path source = Path.of("src/main/java/com/sonnet/wyf/gitreport/opencode/OpenCodeServerTaskRunner.java");
        String code = Files.readString(source);

        assertThat(code).contains("ScheduledProbeWaiter");
        assertThat(code).doesNotContain("TimeUnit.MILLISECONDS.sleep");
        assertThat(code).doesNotContain("Thread.sleep");
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        if (status == 204) {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
            return;
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private ThreadPoolTaskScheduler taskScheduler() {
        if (taskScheduler != null) {
            return taskScheduler;
        }
        taskScheduler = new ThreadPoolTaskScheduler();
        taskScheduler.setThreadNamePrefix("test-opencode-session-poll-");
        taskScheduler.setPoolSize(2);
        taskScheduler.initialize();
        return taskScheduler;
    }

    private ScheduledProbeWaiter scheduledProbeWaiter() {
        return new ScheduledProbeWaiter(taskScheduler());
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
