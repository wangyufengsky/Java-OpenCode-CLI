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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class OpenCodeServerTaskRunnerTest {
    private HttpServer server;
    private ThreadPoolTaskScheduler taskScheduler;

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
    }

    @Test
    void sendsPersistedPromptToSessionAndCompletesByOutputProbe() throws Exception {
        Path output = tempDir.resolve("done.txt");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/session", exchange -> respond(exchange, 200, "{\"data\":{\"id\":\"task-session\"}}"));
        server.createContext("/api/session/task-session/prompt", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (body.contains("worker message") && body.contains("persisted prompt")) {
                Files.writeString(output, "done");
            }
            respond(exchange, 200, "{\"data\":{\"id\":\"msg_1\",\"sessionID\":\"task-session\",\"admittedSeq\":1,\"prompt\":{\"text\":\"ok\"},\"delivery\":\"queue\",\"timeCreated\":1}}");
        });
        server.createContext("/api/session/task-session/message", exchange -> respond(exchange, 200, """
                {
                  "data": [
                    {"id":"msg_1","type":"user","text":"ok","time":{"created":1}},
                    {"id":"msg_2","type":"assistant","agent":"build","model":{"providerID":"test","id":"model"},"content":[],"time":{"created":2}}
                  ],
                  "cursor": {"previous": null, "next": null}
                }
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
    void recordsTimeoutWithoutAbortWhenUsingOpenCodeV2Api() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/session", exchange -> respond(exchange, 200, "{\"data\":{\"id\":\"timeout-session\"}}"));
        server.createContext("/api/session/timeout-session/prompt", exchange -> respond(exchange, 200, "{\"data\":{\"id\":\"msg_1\",\"sessionID\":\"timeout-session\",\"admittedSeq\":1,\"prompt\":{\"text\":\"ok\"},\"delivery\":\"queue\",\"timeCreated\":1}}"));
        server.createContext("/api/session/timeout-session/message", exchange -> respond(exchange, 200, """
                {
                  "data": [
                    {"id":"msg_1","type":"user","text":"ok","time":{"created":1}},
                    {"id":"msg_2","type":"assistant","agent":"build","model":{"providerID":"test","id":"model"},"content":[],"time":{"created":2}}
                  ],
                  "cursor": {"previous": null, "next": null}
                }
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
        server.createContext("/api/session", exchange -> respond(exchange, 200, "{\"data\":{\"id\":\"heartbeat-session\"}}"));
        server.createContext("/api/session/heartbeat-session/prompt", exchange -> respond(exchange, 200, "{\"data\":{\"id\":\"msg_1\",\"sessionID\":\"heartbeat-session\",\"admittedSeq\":1,\"prompt\":{\"text\":\"ok\"},\"delivery\":\"queue\",\"timeCreated\":1}}"));
        server.createContext("/api/session/heartbeat-session/message", exchange -> {
            statusRequests.incrementAndGet();
            respond(exchange, 200, """
                    {
                      "data": [
                        {"id":"msg_1","type":"user","text":"ok","time":{"created":1}},
                        {"id":"msg_2","type":"assistant","agent":"build","model":{"providerID":"test","id":"model"},"content":[],"time":{"created":2}}
                      ],
                      "cursor": {"previous": null, "next": null}
                    }
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
    void pollingUsesSpringTaskSchedulerInsteadOfThreadSleep() throws Exception {
        Path source = Path.of("src/main/java/com/sonnet/wyf/gitreport/opencode/OpenCodeServerTaskRunner.java");
        String code = Files.readString(source);

        assertThat(code).contains("ScheduledProbeWaiter");
        assertThat(code).doesNotContain("TimeUnit.MILLISECONDS.sleep");
        assertThat(code).doesNotContain("Thread.sleep");
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
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
}
