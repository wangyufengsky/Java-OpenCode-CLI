package com.sonnet.wyf.gitreport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.core.ScheduledProbeWaiter;
import com.sonnet.wyf.gitreport.opencode.OpenCodeServerClient;
import com.sonnet.wyf.gitreport.opencode.OpenCodeServerHandle;
import com.sonnet.wyf.gitreport.opencode.OpenCodeServerManager;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenCodeServerManagerTest {
    private HttpServer server;
    private ThreadPoolTaskScheduler taskScheduler;

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        if (taskScheduler != null) {
            taskScheduler.shutdown();
        }
    }

    @Test
    void reusesHealthyExternalServer() throws Exception {
        server = healthyServer();
        GitReportProperties properties = properties("http://127.0.0.1:" + server.getAddress().getPort());
        properties.getPaths().setOpencodeBin(tempDir.resolve("must-not-run").toString());

        OpenCodeServerManager manager = manager();

        OpenCodeServerHandle handle = manager.ensureReady(properties, tempDir.resolve("out"));

        assertThat(handle.ownedByJava()).isFalse();
        assertThat(handle.serverUrl().toString()).isEqualTo(properties.getOpencode().getServerUrl());
    }

    @Test
    void failsFastWhenServerIsUnavailableAndManagementDisabled() {
        GitReportProperties properties = properties("http://127.0.0.1:9");
        properties.getOpencode().setManageServer(false);

        OpenCodeServerManager manager = manager();

        assertThatThrownBy(() -> manager.ensureReady(properties, tempDir.resolve("out")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OpenCode Server is not healthy")
                .hasMessageContaining("manage-server=false");
    }

    @Test
    void startsManagedServerAndWritesServerLogs() throws Exception {
        int port = freePort();
        Path fakeOpencode = tempDir.resolve("fake-opencode.sh");
        Path childPid = tempDir.resolve("child.pid");
        Files.writeString(fakeOpencode, """
                #!/bin/sh
                sleep 30 &
                echo $! > "%s"
                printf started
                wait
                """.formatted(childPid));
        fakeOpencode.toFile().setExecutable(true);
        Thread healthThread = new Thread(() -> {
            try {
                Thread.sleep(500);
                server = healthyServer(port);
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        });
        healthThread.start();

        GitReportProperties properties = properties("http://127.0.0.1:" + port);
        properties.getPaths().setOpencodeBin(fakeOpencode.toString());
        properties.getOpencode().setServerStartTimeoutSeconds(5);
        OpenCodeServerManager manager = manager();

        OpenCodeServerHandle handle = manager.ensureReady(properties, tempDir.resolve("out"));

        assertThat(handle.ownedByJava()).isTrue();
        waitForContent(tempDir.resolve("out/runs/opencode-server/stdout.log"), "started");
        long childProcessId = waitForProcessId(childPid);

        manager.shutdown();
        healthThread.join();

        assertThatProcessExits(childProcessId);
    }

    @Test
    void killsManagedProcessWhenStartupTimesOut() throws Exception {
        int port = freePort();
        Path fakeOpencode = tempDir.resolve("fake-sleep-opencode.sh");
        Files.writeString(fakeOpencode, """
                #!/bin/sh
                sleep 30
                """);
        fakeOpencode.toFile().setExecutable(true);
        GitReportProperties properties = properties("http://127.0.0.1:" + port);
        properties.getPaths().setOpencodeBin(fakeOpencode.toString());
        properties.getOpencode().setServerStartTimeoutSeconds(1);
        OpenCodeServerManager manager = manager();

        assertThatThrownBy(() -> manager.ensureReady(properties, tempDir.resolve("out")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("timed out");
    }

    private GitReportProperties properties(String serverUrl) {
        GitReportProperties properties = new GitReportProperties();
        properties.getOpencode().setServerUrl(serverUrl);
        return properties;
    }

    private OpenCodeServerManager manager() {
        return new OpenCodeServerManager(new OpenCodeServerClient(new ObjectMapper()), scheduledProbeWaiter());
    }

    private ScheduledProbeWaiter scheduledProbeWaiter() {
        return new ScheduledProbeWaiter(taskScheduler());
    }

    private ThreadPoolTaskScheduler taskScheduler() {
        if (taskScheduler != null) {
            return taskScheduler;
        }
        taskScheduler = new ThreadPoolTaskScheduler();
        taskScheduler.setThreadNamePrefix("test-opencode-server-health-");
        taskScheduler.setPoolSize(2);
        taskScheduler.initialize();
        return taskScheduler;
    }

    private HttpServer healthyServer() throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        configureHealth(httpServer);
        httpServer.start();
        return httpServer;
    }

    private HttpServer healthyServer(int port) throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        configureHealth(httpServer);
        httpServer.start();
        return httpServer;
    }

    private void configureHealth(HttpServer httpServer) {
        httpServer.createContext("/global/health", exchange -> {
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
    }

    private int freePort() throws IOException {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private void waitForContent(Path path, String expected) throws Exception {
        boolean found = scheduledProbeWaiter().waitFor(
                () -> Files.exists(path) && Files.readString(path).contains(expected),
                Boolean::booleanValue,
                () -> false,
                Duration.ofSeconds(2),
                Duration.ofMillis(20)
        );
        assertThat(found).isTrue();
    }

    private void assertThatProcessExits(long processId) throws Exception {
        boolean exited = scheduledProbeWaiter().waitFor(
                () -> ProcessHandle.of(processId).map(process -> !process.isAlive()).orElse(true),
                Boolean::booleanValue,
                () -> false,
                Duration.ofSeconds(2),
                Duration.ofMillis(20)
        );
        assertThat(exited).isTrue();
    }

    private long waitForProcessId(Path path) throws Exception {
        boolean found = scheduledProbeWaiter().waitFor(
                () -> Files.exists(path) && !Files.readString(path).trim().isBlank(),
                Boolean::booleanValue,
                () -> false,
                Duration.ofSeconds(2),
                Duration.ofMillis(20)
        );
        assertThat(found).isTrue();
        return Long.parseLong(Files.readString(path).trim());
    }
}
