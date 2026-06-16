package com.sonnet.wyf.gitreport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class OpenCodeServerTaskRunnerTest {
    private HttpServer server;
    private boolean abortCalled;

    @TempDir
    Path tempDir;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
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
            respond(exchange, 200, "{\"ok\":true}");
        });
        server.createContext("/session/task-session", exchange -> respond(exchange, 200, "{\"status\":\"busy\"}"));
        server.start();

        Path promptFile = tempDir.resolve("worker-prompt.md");
        Files.writeString(promptFile, "persisted prompt");
        OpenCodeServerTaskRunner runner = new OpenCodeServerTaskRunner(new OpenCodeServerClient(new ObjectMapper()));
        OpenCodeServerHandle handle = new OpenCodeServerHandle(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), false);

        OpenCodeRunResult result = runner.runUntil(
                handle,
                tempDir,
                "test-title",
                promptFile,
                "worker message",
                null,
                tempDir.resolve("run"),
                () -> Files.exists(output),
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
    void abortsSessionOnTimeout() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/session", exchange -> respond(exchange, 200, "{\"id\":\"timeout-session\"}"));
        server.createContext("/session/timeout-session/prompt_async", exchange -> respond(exchange, 200, "{\"ok\":true}"));
        server.createContext("/session/timeout-session", exchange -> respond(exchange, 200, "{\"status\":\"busy\"}"));
        server.createContext("/session/timeout-session/abort", exchange -> {
            abortCalled = true;
            respond(exchange, 200, "{\"ok\":true}");
        });
        server.start();

        Path promptFile = tempDir.resolve("worker-prompt.md");
        Files.writeString(promptFile, "never completes");
        OpenCodeServerTaskRunner runner = new OpenCodeServerTaskRunner(new OpenCodeServerClient(new ObjectMapper()));
        OpenCodeServerHandle handle = new OpenCodeServerHandle(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), true);

        OpenCodeRunResult result = runner.runUntil(
                handle,
                tempDir,
                "test-title",
                promptFile,
                "worker message",
                null,
                tempDir.resolve("run"),
                () -> false,
                50,
                0
        );

        assertThat(result.timedOut()).isTrue();
        assertThat(result.aborted()).isTrue();
        assertThat(abortCalled).isTrue();
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
