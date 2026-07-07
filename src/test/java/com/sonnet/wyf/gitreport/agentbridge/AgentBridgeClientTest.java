package com.sonnet.wyf.gitreport.agentbridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AgentBridgeClientTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final AgentBridgeClient client = new AgentBridgeClient(objectMapper);
    private final List<HttpServer> servers = new ArrayList<>();

    @AfterEach
    void stopServers() {
        servers.forEach(server -> server.stop(0));
    }

    @Test
    void postsPromptAndPollsInfoUntilRunningFalse() throws Exception {
        AtomicInteger infoCalls = new AtomicInteger();
        List<String> promptBodies = new ArrayList<>();
        HttpServer server = server();
        server.createContext("/prompt", exchange -> {
            promptBodies.add(body(exchange));
            respond(exchange, 200, "{}");
        });
        server.createContext("/info", exchange -> {
            boolean running = infoCalls.getAndIncrement() == 0;
            respond(exchange, 200, "{\"running\":" + running + "}");
        });
        server.start();

        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        client.postPrompt(base, "write report");
        client.waitUntilIdle(base, Duration.ofSeconds(2), Duration.ofMillis(1));

        assertThat(promptBodies).hasSize(1);
        assertThat(objectMapper.readTree(promptBodies.get(0)).path("text").asText()).isEqualTo("write report");
        assertThat(infoCalls.get()).isEqualTo(2);
    }

    @Test
    void parsesStructuredToolResponseFromMcpContentText() throws Exception {
        List<JsonNode> requests = new ArrayList<>();
        HttpServer server = server();
        server.createContext("/mcp", exchange -> {
            JsonNode request = objectMapper.readTree(body(exchange));
            requests.add(request);
            respond(exchange, 200, """
                    {
                      "jsonrpc": "2.0",
                      "id": 1,
                      "result": {
                        "content": [
                          {"type": "text", "text": "{\\"ok\\":true,\\"tests\\":[\\"OrderServiceTest\\"]}"}
                        ]
                      }
                    }
                    """);
        });
        server.start();

        AgentBridgeClient.ToolResponse response = client.callTool(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp"),
                "list_tests",
                objectMapper.createObjectNode().put("file_pattern", "OrderServiceTest")
        );

        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).path("method").asText()).isEqualTo("tools/call");
        assertThat(requests.get(0).path("params").path("name").asText()).isEqualTo("list_tests");
        assertThat(response.text()).contains("OrderServiceTest");
        assertThat(response.structured().path("ok").asBoolean()).isTrue();
        assertThat(response.structured().path("tests")).hasSize(1);
    }

    private HttpServer server() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servers.add(server);
        return server;
    }

    private String body(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (exchange; var responseBody = exchange.getResponseBody()) {
            responseBody.write(bytes);
        }
    }
}
