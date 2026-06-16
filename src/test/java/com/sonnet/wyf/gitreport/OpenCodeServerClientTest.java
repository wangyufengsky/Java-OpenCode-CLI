package com.sonnet.wyf.gitreport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.opencode.OpenCodeServerClient;
import com.sonnet.wyf.gitreport.opencode.OpenCodeSession;
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
import java.util.concurrent.atomic.AtomicInteger;

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
    void callsOpenCodeServerV2SessionPromptAndMessageEndpoints() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/global/health", exchange -> respond(exchange, 200, "{\"ok\":true}"));
        server.createContext("/api/session", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode json = objectMapper.readTree(body);
            requests.add(exchange.getRequestMethod()
                    + " " + exchange.getRequestURI().getPath()
                    + " directory=" + json.at("/location/directory").asText()
                    + " modelProvider=" + json.at("/model/providerID").asText()
                    + " modelId=" + json.at("/model/id").asText()
                    + " hasLegacyModelId=" + json.at("/model/modelID").isTextual());
            respond(exchange, 200, "{\"data\":{\"id\":\"session-1\"}}");
        });
        server.createContext("/api/session/session-1/prompt", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode json = objectMapper.readTree(body);
            requests.add(exchange.getRequestMethod()
                    + " " + exchange.getRequestURI().getPath()
                    + " text=" + json.at("/prompt/text").asText()
                    + " delivery=" + json.at("/delivery").asText()
                    + " resume=" + json.at("/resume").asBoolean());
            respond(exchange, 200, "{\"data\":{\"id\":\"msg_1\",\"sessionID\":\"session-1\",\"admittedSeq\":1,\"prompt\":{\"text\":\"hello prompt\"},\"delivery\":\"queue\",\"timeCreated\":1}}");
        });
        server.createContext("/api/session/session-1/message", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath() + " query=" + exchange.getRequestURI().getRawQuery());
            respond(exchange, 200, """
                    {
                      "data": [
                        {"id":"msg_1","type":"user","text":"hello prompt","time":{"created":1}},
                        {"id":"msg_2","type":"assistant","agent":"build","model":{"providerID":"test-provider","id":"test-model"},"content":[],"finish":"stop","time":{"created":2,"completed":3}}
                      ],
                      "cursor": {"previous": null, "next": null}
                    }
                    """);
        });
        server.start();

        URI serverUrl = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        OpenCodeServerClient client = new OpenCodeServerClient(objectMapper);

        assertThat(client.isHealthy(serverUrl)).isTrue();
        OpenCodeSession session = client.createSession(serverUrl, tempDir, "git-report-author", "spdb-new-api/minimax-m2.7", 60);
        client.sendPromptAsync(serverUrl, tempDir, session.id(), "hello prompt");
        assertThat(client.getSessionStatus(serverUrl, tempDir, session.id())).isEqualTo("idle");
        assertThat(client.abortSession(serverUrl, tempDir, session.id())).isFalse();

        assertThat(requests).containsExactly(
                "POST /api/session directory=" + tempDir + " modelProvider=spdb-new-api modelId=minimax-m2.7 hasLegacyModelId=false",
                "POST /api/session/session-1/prompt text=hello prompt delivery=queue resume=true",
                "GET /api/session/session-1/message query=order=asc&limit=100"
        );
    }

    @Test
    void infersSubmittedWhenV2MessageListHasNoAssistantMessage() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/session/session-1/message", exchange -> respond(exchange, 200, """
                {
                  "data": [
                    {"id":"msg_1","type":"user","text":"hello prompt","time":{"created":1}}
                  ],
                  "cursor": {"previous": null, "next": null}
                }
                """));
        server.start();

        URI serverUrl = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        OpenCodeServerClient client = new OpenCodeServerClient(objectMapper);

        assertThat(client.getSessionStatus(serverUrl, tempDir, "session-1")).isEqualTo("submitted");
    }

    @Test
    void infersSessionStatusFromOpenCodeV2MessageList() throws Exception {
        AtomicInteger messageCalls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/session/session-1/message", exchange -> {
            int call = messageCalls.incrementAndGet();
            if (call == 1) {
                respond(exchange, 200, """
                        {
                          "data": [
                            {"id":"msg_1","type":"user","text":"hello prompt","time":{"created":1}},
                            {"id":"msg_2","type":"assistant","agent":"build","model":{"providerID":"test-provider","id":"test-model"},"content":[],"time":{"created":2}}
                          ],
                          "cursor": {"previous": null, "next": null}
                        }
                        """);
            } else {
                respond(exchange, 200, """
                        {
                          "data": [
                            {"id":"msg_1","type":"user","text":"hello prompt","time":{"created":1}},
                            {"id":"msg_2","type":"assistant","agent":"build","model":{"providerID":"test-provider","id":"test-model"},"content":[],"finish":"stop","time":{"created":2,"completed":3}}
                          ],
                          "cursor": {"previous": null, "next": null}
                        }
                        """);
            }
        });
        server.start();

        URI serverUrl = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        OpenCodeServerClient client = new OpenCodeServerClient(objectMapper);

        assertThat(client.getSessionStatus(serverUrl, tempDir, "session-1")).isEqualTo("running");
        assertThat(client.getSessionStatus(serverUrl, tempDir, "session-1")).isEqualTo("idle");
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
