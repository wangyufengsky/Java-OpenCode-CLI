package com.sonnet.wyf.gitreport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenCodeServerManagerTest {
    private HttpServer server;

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void reusesHealthyExternalServer() throws Exception {
        server = healthyServer();
        GitReportProperties properties = properties("http://127.0.0.1:" + server.getAddress().getPort());
        properties.getPaths().setOpencodeBin(tempDir.resolve("must-not-run").toString());

        OpenCodeServerManager manager = new OpenCodeServerManager(new OpenCodeServerClient(new ObjectMapper()));

        OpenCodeServerHandle handle = manager.ensureReady(properties, tempDir.resolve("out"));

        assertThat(handle.ownedByJava()).isFalse();
        assertThat(handle.serverUrl().toString()).isEqualTo(properties.getOpencode().getServerUrl());
    }

    @Test
    void failsFastWhenServerIsUnavailableAndManagementDisabled() {
        GitReportProperties properties = properties("http://127.0.0.1:9");
        properties.getOpencode().setManageServer(false);

        OpenCodeServerManager manager = new OpenCodeServerManager(new OpenCodeServerClient(new ObjectMapper()));

        assertThatThrownBy(() -> manager.ensureReady(properties, tempDir.resolve("out")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OpenCode Server is not healthy")
                .hasMessageContaining("manage-server=false");
    }

    @Test
    void startsManagedServerAndWritesServerLogs() throws Exception {
        int port = freePort();
        Path fakeOpencode = tempDir.resolve("fake-opencode.sh");
        Files.writeString(fakeOpencode, """
                #!/bin/sh
                printf started
                sleep 30
                """);
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
        OpenCodeServerManager manager = new OpenCodeServerManager(new OpenCodeServerClient(new ObjectMapper()));

        OpenCodeServerHandle handle = manager.ensureReady(properties, tempDir.resolve("out"));

        assertThat(handle.ownedByJava()).isTrue();
        assertThat(tempDir.resolve("out/runs/opencode-server/stdout.log")).hasContent("started");

        manager.shutdown();
        healthThread.join();
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
        OpenCodeServerManager manager = new OpenCodeServerManager(new OpenCodeServerClient(new ObjectMapper()));

        assertThatThrownBy(() -> manager.ensureReady(properties, tempDir.resolve("out")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("timed out");
    }

    private GitReportProperties properties(String serverUrl) {
        GitReportProperties properties = new GitReportProperties();
        properties.getOpencode().setServerUrl(serverUrl);
        return properties;
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
}
