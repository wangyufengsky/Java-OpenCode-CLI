package com.sonnet.wyf.gitreport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OpenCodeServerClientTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<String> requests = new ArrayList<>();
    private HttpServer server;

    @TempDir
    Path tempDir;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void callsOpenCodeServerSessionPromptStatusAndAbortEndpoints() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/global/health", exchange -> respond(exchange, 200, "{\"ok\":true}"));
        server.createContext("/session", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath() + " query=" + hasDirectoryQuery(exchange) + " dir=" + exchange.getRequestHeaders().getFirst("x-opencode-directory"));
            respond(exchange, 200, "{\"id\":\"session-1\"}");
        });
        server.createContext("/session/session-1/prompt_async", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode json = objectMapper.readTree(body);
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath() + " query=" + hasDirectoryQuery(exchange) + " text=" + json.at("/parts/0/text").asText() + " provider=" + json.at("/model/providerID").asText() + " model=" + json.at("/model/modelID").asText());
            respond(exchange, 200, "{\"ok\":true}");
        });
        server.createContext("/session/session-1", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath() + " query=" + hasDirectoryQuery(exchange));
            respond(exchange, 200, "{\"id\":\"session-1\",\"status\":\"idle\"}");
        });
        server.createContext("/session/session-1/abort", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath() + " query=" + hasDirectoryQuery(exchange));
            respond(exchange, 200, "{\"ok\":true}");
        });
        server.start();

        URI serverUrl = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        OpenCodeServerClient client = new OpenCodeServerClient(objectMapper);

        assertThat(client.isHealthy(serverUrl)).isTrue();
        OpenCodeSession session = client.createSession(serverUrl, tempDir, "git-report-author");
        client.sendPromptAsync(serverUrl, tempDir, session.id(), "hello prompt", "test-provider/test-model");
        assertThat(client.getSessionStatus(serverUrl, tempDir, session.id())).isEqualTo("idle");
        assertThat(client.abortSession(serverUrl, tempDir, session.id())).isTrue();

        assertThat(requests).containsExactly(
                "POST /session query=true dir=" + tempDir,
                "POST /session/session-1/prompt_async query=true text=hello prompt provider=test-provider model=test-model",
                "GET /session/session-1 query=true",
                "POST /session/session-1/abort query=true"
        );
    }

    private boolean hasDirectoryQuery(HttpExchange exchange) {
        String query = exchange.getRequestURI().getRawQuery();
        return query != null && query.startsWith("directory=");
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
