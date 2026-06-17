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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
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
        server.createContext("/session", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode json = objectMapper.readTree(body);
            String actualTitle = json.at("/title").asText();
            requests.add(exchange.getRequestMethod()
                    + " " + exchange.getRequestURI().getPath()
                    + " query=" + exchange.getRequestURI().getRawQuery()
                    + " titlePrefix=" + actualTitle.startsWith("git-report-author-")
                    + " titleChanged=" + !actualTitle.equals("git-report-author")
                    + " modelProvider=" + json.at("/model/providerID").asText()
                    + " modelId=" + json.at("/model/id").asText()
                    + " hasPromptModelId=" + json.at("/model/modelID").isTextual());
            respond(exchange, 200, "{\"id\":\"session-1\"}");
        });
        server.createContext("/session/session-1/prompt_async", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode json = objectMapper.readTree(body);
            requests.add(exchange.getRequestMethod()
                    + " " + exchange.getRequestURI().getPath()
                    + " query=" + exchange.getRequestURI().getRawQuery()
                    + " text=" + json.at("/parts/0/text").asText()
                    + " modelProvider=" + json.at("/model/providerID").asText()
                    + " modelId=" + json.at("/model/modelID").asText());
            respond(exchange, 204, "");
        });
        server.createContext("/session/session-1/message", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath() + " query=" + exchange.getRequestURI().getRawQuery());
            respond(exchange, 200, """
                    [
                        {"id":"msg_1","type":"user","text":"hello prompt","time":{"created":1}},
                        {"id":"msg_2","type":"assistant","agent":"build","model":{"providerID":"test-provider","id":"test-model"},"content":[],"finish":"stop","time":{"created":2,"completed":3}}
                    ]
                    """);
        });
        server.start();

        URI serverUrl = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        OpenCodeServerClient client = new OpenCodeServerClient(objectMapper);

        assertThat(client.isHealthy(serverUrl)).isTrue();
        OpenCodeSession session = client.createSession(serverUrl, tempDir, "git-report-author", "spdb-new-api/minimax-m2.7", 60);
        client.sendPromptAsync(serverUrl, tempDir, session.id(), "hello prompt", "spdb-new-api/minimax-m2.7", 60);
        assertThat(client.getSessionStatus(serverUrl, tempDir, session.id())).isEqualTo("idle");
        assertThat(client.abortSession(serverUrl, tempDir, session.id())).isFalse();

        assertThat(requests).containsExactly(
                "POST /session query=directory=" + urlEncodedTempDir() + " titlePrefix=true titleChanged=true modelProvider=spdb-new-api modelId=minimax-m2.7 hasPromptModelId=false",
                "POST /session/session-1/prompt_async query=directory=" + urlEncodedTempDir() + " text=hello prompt modelProvider=spdb-new-api modelId=minimax-m2.7",
                "GET /session/session-1/message query=directory=" + urlEncodedTempDir() + "&limit=100"
        );
    }

    @Test
    void discoversCreatedSessionWhenCreateResponseIsStillPending() throws Exception {
        AtomicReference<String> sessionTitle = new AtomicReference<>("");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/session", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                sessionTitle.set(objectMapper.readTree(body).at("/title").asText());
                requests.add(exchange.getRequestMethod()
                        + " " + exchange.getRequestURI().getPath()
                        + " query=" + exchange.getRequestURI().getRawQuery());
                sleep(1_500);
                respond(exchange, 200, "{\"id\":\"late-session\"}");
                return;
            }
            requests.add(exchange.getRequestMethod()
                    + " " + exchange.getRequestURI().getPath()
                    + " query=" + exchange.getRequestURI().getRawQuery());
            respond(exchange, 200, """
                    [
                      {"id":"recovered-session","title":"%s","time":{"created":2}},
                      {"id":"older-session","title":"git-report-author-timeout","time":{"created":1}}
                    ]
                    """.formatted(sessionTitle.get()));
        });
        server.start();

        URI serverUrl = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        OpenCodeServerClient client = new OpenCodeServerClient(objectMapper);

        OpenCodeSession session = client.createSession(serverUrl, tempDir, "git-report-author-timeout", "", 1);

        assertThat(session.id()).isEqualTo("recovered-session");
        assertThat(requests).contains("POST /session query=directory=" + urlEncodedTempDir());
        assertThat(requests).anySatisfy(request -> assertThat(request)
                .startsWith("GET /session query=directory=" + urlEncodedTempDir() + "&search=git-report-author-timeout-")
                .endsWith("&limit=100"));
    }

    @Test
    void discoversCreatedSessionBeforeCreateResponseCompletes() throws Exception {
        AtomicBoolean createStarted = new AtomicBoolean(false);
        AtomicReference<String> sessionTitle = new AtomicReference<>("");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/session", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                sessionTitle.set(objectMapper.readTree(body).at("/title").asText());
                createStarted.set(true);
                requests.add(exchange.getRequestMethod()
                        + " " + exchange.getRequestURI().getPath()
                        + " query=" + exchange.getRequestURI().getRawQuery());
                sleep(5_000);
                respond(exchange, 200, "{\"id\":\"never-wait-for-this-response\"}");
                return;
            }
            requests.add(exchange.getRequestMethod()
                    + " " + exchange.getRequestURI().getPath()
                    + " query=" + exchange.getRequestURI().getRawQuery());
            if (createStarted.get()) {
                respond(exchange, 200, """
                        [
                          {"id":"listed-before-response","title":"%s","time":{"created":2}},
                          {"id":"old-session","title":"git-report-author-inflight","time":{"created":1}}
                        ]
                        """.formatted(sessionTitle.get()));
            } else {
                respond(exchange, 200, """
                        [
                          {"id":"old-session","title":"git-report-author-inflight","time":{"created":1}}
                        ]
                        """);
            }
        });
        server.start();

        URI serverUrl = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        OpenCodeServerClient client = new OpenCodeServerClient(objectMapper);

        OpenCodeSession session = assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> client.createSession(serverUrl, tempDir, "git-report-author-inflight", "", 30));

        assertThat(session.id()).isEqualTo("listed-before-response");
        assertThat(requests).contains("POST /session query=directory=" + urlEncodedTempDir());
        assertThat(requests).anySatisfy(request -> assertThat(request)
                .startsWith("GET /session query=directory=" + urlEncodedTempDir() + "&search=git-report-author-inflight-")
                .endsWith("&limit=100"));
    }

    @Test
    void infersSubmittedWhenV2MessageListHasNoAssistantMessage() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/session/session-1/message", exchange -> respond(exchange, 200, """
                [
                    {"id":"msg_1","type":"user","text":"hello prompt","time":{"created":1}}
                ]
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
        server.createContext("/session/session-1/message", exchange -> {
            int call = messageCalls.incrementAndGet();
            if (call == 1) {
                respond(exchange, 200, """
                        [
                            {"id":"msg_1","type":"user","text":"hello prompt","time":{"created":1}},
                            {"id":"msg_2","type":"assistant","agent":"build","model":{"providerID":"test-provider","id":"test-model"},"content":[],"time":{"created":2}}
                        ]
                        """);
            } else {
                respond(exchange, 200, """
                        [
                            {"id":"msg_1","type":"user","text":"hello prompt","time":{"created":1}},
                            {"id":"msg_2","type":"assistant","agent":"build","model":{"providerID":"test-provider","id":"test-model"},"content":[],"finish":"stop","time":{"created":2,"completed":3}}
                        ]
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

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private String urlEncodedTempDir() {
        return tempDir.toString().replace("/", "%2F");
    }
}
