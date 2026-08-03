package com.sonnet.wyf.gitreport.agentbridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class AgentBridgeClient {
    private static final String MCP_CLIENT_PROTOCOL_VERSION = "2025-03-26";
    public static final String DATABASE_MCP_MINIMUM_AGENTBRIDGE_VERSION = "1.202.1";
    private static final int SMALL_JSON_RESPONSE_BYTES = 64 * 1024;
    private static final int MCP_METADATA_RESPONSE_BYTES = 1024 * 1024;
    private static final int SQL_TOOL_RESPONSE_BYTES = 262_144 + 8 * 1024;
    private static final int GENERAL_TOOL_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final int TOOL_CALL_PAGE_RESPONSE_BYTES = 1024 * 1024;
    private static final int TOOL_CALL_DETAIL_RESPONSE_BYTES = 8 * 1024 * 1024;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final AtomicLong requestIds = new AtomicLong();
    private final Object mcpSessionLock = new Object();
    private final Map<URI, McpSession> mcpSessions = new HashMap<>();

    public AgentBridgeClient(ObjectMapper objectMapper) {
        this(objectMapper, localAgentBridgeHttpClient());
    }

    AgentBridgeClient(ObjectMapper objectMapper, HttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    public void postPrompt(URI webBaseUrl, String prompt) throws IOException, InterruptedException {
        requireLoopbackEndpoint(webBaseUrl);
        ObjectNode body = objectMapper.createObjectNode();
        body.put("text", prompt);
        HttpResponse<String> response = sendJson(webBaseUrl.resolve("/prompt"), body);
        requireSuccess(response, "AgentBridge POST /prompt failed");
    }

    public void clearSession(URI webBaseUrl) throws IOException, InterruptedException {
        postPrompt(webBaseUrl, "/session-clear");
    }

    public void waitUntilIdle(URI webBaseUrl, Duration timeout, Duration pollInterval) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        long startupGraceNanos = Math.min(Duration.ofSeconds(30).toNanos(), timeout.toNanos());
        long startedWaiting = System.nanoTime();
        boolean observedRunning = false;
        while (true) {
            boolean running = isRunning(webBaseUrl);
            if (running) {
                observedRunning = true;
            } else if (observedRunning || System.nanoTime() - startedWaiting >= startupGraceNanos) {
                return;
            }
            if (System.nanoTime() >= deadline) {
                throw new AgentBridgeTimeoutException(
                        "AgentBridge agent did not finish within " + timeout.toMinutes() + " minutes"
                );
            }
            Thread.sleep(Math.max(1, pollInterval.toMillis()));
        }
    }

    public boolean isRunning(URI webBaseUrl) throws IOException, InterruptedException {
        return agentBridgeInfo(webBaseUrl).path("running").asBoolean(false);
    }

    public void requireDatabaseMcpSupport(URI webBaseUrl)
            throws IOException, InterruptedException {
        requireLoopbackEndpoint(webBaseUrl);
        JsonNode info = agentBridgeInfo(webBaseUrl);
        requireDatabaseMcpVersion(info);
    }

    private void requireDatabaseMcpVersion(JsonNode info) {
        String version = info.path("version").asText("").strip();
        if (!isAtLeastVersion(version, DATABASE_MCP_MINIMUM_AGENTBRIDGE_VERSION)) {
            throw unsupportedDatabaseMcpVersion(version);
        }
    }

    private JsonNode agentBridgeInfo(URI webBaseUrl) throws IOException, InterruptedException {
        requireLoopbackEndpoint(webBaseUrl);
        HttpRequest request = HttpRequest.newBuilder(webBaseUrl.resolve("/info"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> response = sendBounded(
                request, SMALL_JSON_RESPONSE_BYTES, "AgentBridge GET /info response"
        );
        requireSuccess(response, "AgentBridge GET /info failed");
        JsonNode info = objectMapper.readTree(response.body());
        if (!info.isObject()) {
            throw new IllegalStateException("AgentBridge GET /info must return an object");
        }
        return info;
    }

    private IllegalStateException unsupportedDatabaseMcpVersion(String version) {
        String reported = version.isBlank() ? "unknown" : version;
        return new IllegalStateException(
                "AgentBridge " + reported + " does not support Database MCP; requires AgentBridge >= "
                        + DATABASE_MCP_MINIMUM_AGENTBRIDGE_VERSION
        );
    }

    private boolean isAtLeastVersion(String actual, String minimum) {
        String[] actualParts = actual.split("\\.");
        String[] minimumParts = minimum.split("\\.");
        if (actualParts.length != 3 || minimumParts.length != 3) {
            return false;
        }
        for (int index = 0; index < 3; index++) {
            if (!actualParts[index].matches("0|[1-9][0-9]*")
                    || !minimumParts[index].matches("0|[1-9][0-9]*")) {
                return false;
            }
            int actualPart;
            int minimumPart;
            try {
                actualPart = Integer.parseInt(actualParts[index]);
                minimumPart = Integer.parseInt(minimumParts[index]);
            } catch (NumberFormatException exception) {
                return false;
            }
            if (actualPart != minimumPart) {
                return actualPart > minimumPart;
            }
        }
        return true;
    }

    public ToolResponse callTool(URI mcpUrl, String name, JsonNode arguments) throws IOException, InterruptedException {
        requireLoopbackEndpoint(mcpUrl);
        return executeWithMcpSessionRecovery(
                mcpUrl,
                session -> callTool(mcpUrl, session, name, arguments)
        );
    }

    private ToolResponse callTool(
            URI mcpUrl,
            McpSession session,
            String name,
            JsonNode arguments
    ) throws IOException, InterruptedException {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("name", name);
        params.set("arguments", arguments == null ? objectMapper.createObjectNode() : arguments);

        int responseLimit = isNativeDatabaseEvidenceTool(name)
                ? SQL_TOOL_RESPONSE_BYTES
                : GENERAL_TOOL_RESPONSE_BYTES;
        ObjectNode body = objectMapper.createObjectNode();
        body.put("jsonrpc", "2.0");
        body.put("id", requestIds.incrementAndGet());
        body.put("method", "tools/call");
        body.set("params", params);

        HttpResponse<String> response = sendJson(
                mcpUrl,
                body,
                toolCallTimeout(arguments),
                session,
                responseLimit,
                "AgentBridge MCP tools/call response for " + name
        );
        requireMcpSuccess(response, "AgentBridge MCP tools/call failed: " + name);
        JsonNode root = objectMapper.readTree(response.body());
        if (root.hasNonNull("error")) {
            throw new IllegalStateException("AgentBridge MCP tool failed: " + root.path("error"));
        }
        JsonNode result = root.path("result");
        String text = contentText(result);
        return new ToolResponse(result, text, structured(text, result));
    }

    public List<ToolDefinition> listTools(URI mcpUrl) throws IOException, InterruptedException {
        requireLoopbackEndpoint(mcpUrl);
        return executeWithMcpSessionRecovery(mcpUrl, session -> listTools(mcpUrl, session));
    }

    private List<ToolDefinition> listTools(URI mcpUrl, McpSession session)
            throws IOException, InterruptedException {
        List<ToolDefinition> tools = new ArrayList<>();
        Set<String> observedCursors = new HashSet<>();
        String cursor = null;
        do {
            ObjectNode params = objectMapper.createObjectNode();
            if (cursor != null) {
                params.put("cursor", cursor);
            }
            ObjectNode body = objectMapper.createObjectNode();
            body.put("jsonrpc", "2.0");
            body.put("id", requestIds.incrementAndGet());
            body.put("method", "tools/list");
            body.set("params", params);

            HttpResponse<String> response = sendJson(
                    mcpUrl,
                    body,
                    Duration.ofSeconds(30),
                    session,
                    MCP_METADATA_RESPONSE_BYTES,
                    "AgentBridge MCP tools/list response"
            );
            requireMcpSuccess(response, "AgentBridge MCP tools/list failed");
            JsonNode root = objectMapper.readTree(response.body());
            if (root.hasNonNull("error")) {
                throw new IllegalStateException("AgentBridge MCP tools/list failed: " + root.path("error"));
            }
            JsonNode result = root.path("result");
            if (!result.path("tools").isArray()) {
                throw new IllegalStateException("AgentBridge MCP tools/list returned no tools array");
            }
            for (JsonNode tool : result.path("tools")) {
                tools.add(new ToolDefinition(
                        tool.path("name").asText(),
                        tool.path("description").asText(""),
                        tool.path("inputSchema").deepCopy()
                ));
            }
            cursor = result.path("nextCursor").asText("").strip();
            if (cursor.isEmpty()) {
                cursor = null;
            } else if (!observedCursors.add(cursor)) {
                throw new IllegalStateException("AgentBridge MCP tools/list repeated cursor: " + cursor);
            }
        } while (cursor != null);
        return List.copyOf(tools);
    }

    public List<ToolCallRecord> getToolCalls(URI webBaseUrl) throws IOException, InterruptedException {
        requireLoopbackEndpoint(webBaseUrl);
        requireDatabaseMcpVersion(agentBridgeInfo(webBaseUrl));
        HttpRequest request = HttpRequest.newBuilder(webBaseUrl.resolve("/tool-calls"))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = sendBounded(
                request,
                TOOL_CALL_PAGE_RESPONSE_BYTES,
                "AgentBridge GET /tool-calls response"
        );
        requireSuccess(response, "AgentBridge GET /tool-calls failed");
        JsonNode root = uniqueKeyHistoryResponse(response.body());
        if (!root.isObject() || root.has("toolCalls") || !root.path("items").isArray()) {
            if (root.isObject() && root.has("toolCalls")) {
                throw new IllegalStateException("AgentBridge GET /tool-calls must not contain toolCalls");
            }
            throw new IllegalStateException("AgentBridge GET /tool-calls must return an items array");
        }
        List<ToolCallRecord> calls = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonNode item : root.path("items")) {
            if (!item.isObject()) {
                throw new IllegalStateException("AgentBridge GET /tool-calls items must be objects");
            }
            String id = requiredHistoryId(item);
            if (!ids.add(id)) {
                throw new IllegalStateException("AgentBridge GET /tool-calls returned duplicate id: " + id);
            }
            String toolName = requiredHistoryText(item, "toolName");
            String status = requiredHistoryText(item, "status");
            Instant timestamp = requiredHistoryTimestamp(item);
            Long durationMs = requiredHistoryDuration(item);
            HistoryPayload payload = historyPayload(webBaseUrl, item);
            calls.add(new ToolCallRecord(
                    id,
                    item.path("title").asText(""),
                    toolName,
                    item.path("kind").asText(""),
                    status,
                    timestamp,
                    payload.arguments(),
                    payload.result(),
                    durationMs,
                    item.path("hooks")
            ));
        }
        return List.copyOf(calls);
    }

    private HistoryPayload historyPayload(URI webBaseUrl, JsonNode item)
            throws IOException, InterruptedException {
        boolean hasArgumentsTruncated = item.has("argumentsTruncated");
        boolean hasResultTruncated = item.has("resultTruncated");
        if (!hasArgumentsTruncated && !hasResultTruncated) {
            return legacyHistoryPayload(item);
        }
        if (!hasArgumentsTruncated || !hasResultTruncated) {
            throw new IllegalStateException(
                    "argumentsTruncated and resultTruncated must either both be present or both be absent"
            );
        }

        boolean argumentsTruncated = requiredHistoryBoolean(item, "argumentsTruncated");
        boolean resultTruncated = requiredHistoryBoolean(item, "resultTruncated");
        if (!argumentsTruncated && !resultTruncated) {
            return verifiedUntruncatedHistoryPayload(item);
        }
        if (!requiredHistoryBoolean(item, "fullPayloadAvailable")) {
            String reason = item.path("fullPayloadUnavailableReason").asText("unknown").strip();
            throw new IllegalStateException("AgentBridge full payload unavailable: " + reason);
        }

        JsonNode detail = fetchVerifiedToolCallDetail(webBaseUrl, item);
        String arguments = verifiedPayloadText(item, detail, "arguments");
        String result = verifiedPayloadText(item, detail, "result");
        return new HistoryPayload(
                structuredHistoryField(objectMapper.getNodeFactory().textNode(arguments), "arguments"),
                historyResultField(objectMapper.getNodeFactory().textNode(result), item)
        );
    }

    private HistoryPayload legacyHistoryPayload(JsonNode item) {
        return new HistoryPayload(
                structuredHistoryField(item.path("arguments"), "arguments"),
                historyResultField(item.path("result"), item)
        );
    }

    private HistoryPayload verifiedUntruncatedHistoryPayload(JsonNode item) {
        String arguments = verifiedUntruncatedSummaryText(item, "arguments");
        String result = verifiedUntruncatedSummaryText(item, "result");
        return new HistoryPayload(
                structuredHistoryField(objectMapper.getNodeFactory().textNode(arguments), "arguments"),
                historyResultField(objectMapper.getNodeFactory().textNode(result), item)
        );
    }

    private String verifiedUntruncatedSummaryText(JsonNode item, String field) {
        JsonNode value = item.path(field);
        if (!value.isTextual()) {
            throw new IllegalStateException(field + " summary must be a string");
        }
        long expectedBytes = requiredHistoryBytes(item, field + "Bytes");
        long actualBytes = value.textValue().getBytes(StandardCharsets.UTF_8).length;
        if (expectedBytes != actualBytes) {
            throw new IllegalStateException(field + " byte length mismatch");
        }
        String expectedSha256 = requiredHistorySha256(item, field + "Sha256");
        if (!expectedSha256.equals(sha256Utf8(value.textValue()))) {
            throw new IllegalStateException(field + " SHA-256 mismatch");
        }
        return value.textValue();
    }

    private boolean requiredHistoryBoolean(JsonNode item, String field) {
        JsonNode value = item.path(field);
        if (!value.isBoolean()) {
            throw new IllegalStateException(field + " must be a boolean");
        }
        return value.booleanValue();
    }

    private JsonNode fetchVerifiedToolCallDetail(URI webBaseUrl, JsonNode item)
            throws IOException, InterruptedException {
        URI detailUri = canonicalToolCallDetailUri(webBaseUrl, item);
        HttpRequest request = HttpRequest.newBuilder(detailUri)
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = sendBounded(
                request,
                TOOL_CALL_DETAIL_RESPONSE_BYTES,
                "AgentBridge tool-call detail response"
        );
        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                    "AgentBridge GET " + detailUri.getRawPath() + " failed: HTTP " + response.statusCode()
            );
        }
        if (!detailUri.equals(response.uri())) {
            throw new IllegalStateException("AgentBridge tool-call detail response URI mismatch");
        }

        JsonNode detail = uniqueKeyJsonResponse(response.body(), "AgentBridge tool-call detail");
        if (!detail.isObject()) {
            throw new IllegalStateException("AgentBridge tool-call detail must return an object");
        }
        verifyHistoryIdentity(item, detail);
        return detail;
    }

    private URI canonicalToolCallDetailUri(URI webBaseUrl, JsonNode item) {
        String id = requiredHistoryId(item);
        JsonNode detailUrlNode = item.path("detailUrl");
        if (!detailUrlNode.isTextual() || detailUrlNode.textValue().isBlank()) {
            throw new IllegalStateException("detailUrl must not be blank");
        }
        String detailUrl = detailUrlNode.textValue();
        URI detailReference;
        try {
            detailReference = URI.create(detailUrl);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("detailUrl must be a valid URI", exception);
        }
        if (detailReference.getRawQuery() != null) {
            throw new IllegalStateException("detailUrl must not contain a query");
        }
        if (detailReference.getRawFragment() != null) {
            throw new IllegalStateException("detailUrl must not contain a fragment");
        }
        if (detailReference.getUserInfo() != null) {
            throw new IllegalStateException("detailUrl must not contain user-info");
        }

        URI detailUri = webBaseUrl.resolve(detailReference);
        if (!sameOrigin(webBaseUrl, detailUri)) {
            throw new IllegalStateException("detailUrl must use the same origin as AgentBridge Web Access");
        }
        String expectedPath = "/tool-calls/" + id;
        if (!detailUrl.equals(expectedPath) || !expectedPath.equals(detailUri.getRawPath())) {
            throw new IllegalStateException("detailUrl must equal exactly " + expectedPath);
        }
        return detailUri;
    }

    private boolean sameOrigin(URI first, URI second) {
        return first.getScheme() != null
                && second.getScheme() != null
                && first.getScheme().equalsIgnoreCase(second.getScheme())
                && first.getHost() != null
                && second.getHost() != null
                && first.getHost().equalsIgnoreCase(second.getHost())
                && effectivePort(first) == effectivePort(second);
    }

    private int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        if ("http".equalsIgnoreCase(uri.getScheme())) {
            return 80;
        }
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            return 443;
        }
        return -1;
    }

    private void verifyHistoryIdentity(JsonNode summary, JsonNode detail) {
        if (!requiredHistoryId(summary).equals(requiredHistoryId(detail))) {
            throw new IllegalStateException("id mismatch between summary and detail");
        }
        requireMatchingHistoryText(summary, detail, "toolName");
        requireMatchingHistoryText(summary, detail, "status");
        requireMatchingHistoryText(summary, detail, "timestamp");
        Long summaryDuration = requiredHistoryDuration(summary);
        Long detailDuration = requiredHistoryDuration(detail);
        if (!Objects.equals(summaryDuration, detailDuration)) {
            throw new IllegalStateException("durationMs mismatch between summary and detail");
        }
    }

    private void requireMatchingHistoryText(JsonNode summary, JsonNode detail, String field) {
        String summaryValue = requiredHistoryText(summary, field);
        String detailValue = requiredHistoryText(detail, field);
        if (!summaryValue.equals(detailValue)) {
            throw new IllegalStateException(field + " mismatch between summary and detail");
        }
    }

    private String verifiedPayloadText(JsonNode summary, JsonNode detail, String field) {
        JsonNode value = detail.path(field);
        if (!value.isTextual()) {
            throw new IllegalStateException(field + " detail must be a string");
        }
        boolean summaryTruncated = requiredHistoryBoolean(summary, field + "Truncated");
        boolean detailTruncated = requiredHistoryBoolean(detail, field + "Truncated");
        if (summaryTruncated != detailTruncated) {
            throw new IllegalStateException(field + " truncation mismatch between summary and detail");
        }

        long summaryBytes = requiredHistoryBytes(summary, field + "Bytes");
        long detailBytes = requiredHistoryBytes(detail, field + "Bytes");
        long actualBytes = value.textValue().getBytes(StandardCharsets.UTF_8).length;
        if (summaryBytes != detailBytes || detailBytes != actualBytes) {
            throw new IllegalStateException(field + " byte length mismatch");
        }

        String summarySha256 = requiredHistorySha256(summary, field + "Sha256");
        String detailSha256 = requiredHistorySha256(detail, field + "Sha256");
        String actualSha256 = sha256Utf8(value.textValue());
        if (!summarySha256.equals(detailSha256) || !detailSha256.equals(actualSha256)) {
            throw new IllegalStateException(field + " SHA-256 mismatch");
        }
        return value.textValue();
    }

    private long requiredHistoryBytes(JsonNode item, String field) {
        JsonNode value = item.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
            throw new IllegalStateException(field + " must be a non-negative integer");
        }
        return value.longValue();
    }

    private String requiredHistorySha256(JsonNode item, String field) {
        JsonNode value = item.path(field);
        if (!value.isTextual() || !value.textValue().matches("[0-9a-f]{64}")) {
            throw new IllegalStateException(field + " must be a lowercase SHA-256");
        }
        return value.textValue();
    }

    private String sha256Utf8(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private JsonNode uniqueKeyHistoryResponse(String responseBody) {
        return uniqueKeyJsonResponse(responseBody, "AgentBridge GET /tool-calls");
    }

    private JsonNode uniqueKeyJsonResponse(String responseBody, String label) {
        try (JsonParser parser = objectMapper.getFactory().createParser(responseBody)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            return objectMapper.readTree(parser);
        } catch (IOException exception) {
            throw new IllegalStateException(label + " must contain unique JSON keys", exception);
        }
    }

    private String requiredHistoryText(JsonNode item, String field) {
        JsonNode value = item.path(field);
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalStateException(field + " must not be blank");
        }
        return value.textValue().strip();
    }

    private String requiredHistoryId(JsonNode item) {
        JsonNode value = item.path("id");
        if (value.isTextual() && !value.textValue().isBlank()) {
            return value.textValue().strip();
        }
        if (value.isIntegralNumber() && value.canConvertToLong() && value.longValue() > 0) {
            return Long.toString(value.longValue());
        }
        throw new IllegalStateException("AgentBridge GET /tool-calls returned an item without id");
    }

    private Instant requiredHistoryTimestamp(JsonNode item) {
        String timestamp = requiredHistoryText(item, "timestamp");
        try {
            return Instant.parse(timestamp);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("timestamp must be a valid ISO-8601 instant", exception);
        }
    }

    private Long requiredHistoryDuration(JsonNode item) {
        if (!item.has("durationMs") || item.path("durationMs").isNull()) {
            return null;
        }
        JsonNode duration = item.path("durationMs");
        if (!duration.isIntegralNumber() || !duration.canConvertToLong()) {
            throw new IllegalStateException("durationMs must be a non-negative integer");
        }
        long durationMs = duration.longValue();
        if (durationMs == -1 && isRunningHistory(item)) {
            return null;
        }
        if (durationMs < 0) {
            throw new IllegalStateException("durationMs must be a non-negative integer");
        }
        return durationMs;
    }

    private JsonNode structuredHistoryField(JsonNode value, String label) {
        if (value.isObject() || value.isArray()) {
            return value.deepCopy();
        }
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalStateException(label + " must contain JSON");
        }
        try {
            JsonNode parsed = objectMapper.readTree(value.textValue());
            if (!parsed.isObject() && !parsed.isArray()) {
                throw new IllegalStateException(label + " must contain an object or array");
            }
            return parsed;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(label + " must contain JSON", exception);
        }
    }

    private JsonNode historyResultField(JsonNode value, JsonNode item) {
        if (value.isObject() || value.isArray()) {
            return value.deepCopy();
        }
        if (value.isTextual() && value.textValue().isBlank() && isRunningHistory(item)) {
            return objectMapper.nullNode();
        }
        if (value.isTextual() && value.textValue().isEmpty() && isVerifiedEmptyHistoryResult(item)) {
            return value.deepCopy();
        }
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalStateException("result must not be blank");
        }
        try {
            JsonNode parsed = objectMapper.readTree(value.textValue());
            if (parsed.isObject() || parsed.isArray()) {
                return parsed;
            }
        } catch (JsonProcessingException ignored) {
            // AgentBridge records plain-text outputs for successful non-data tools.
        }
        return value.deepCopy();
    }

    private boolean isVerifiedEmptyHistoryResult(JsonNode item) {
        return item.has("argumentsTruncated")
                && item.has("resultTruncated")
                && !item.path("resultTruncated").asBoolean(true)
                && item.path("resultBytes").isIntegralNumber()
                && item.path("resultBytes").canConvertToLong()
                && item.path("resultBytes").longValue() == 0
                && sha256Utf8("").equals(item.path("resultSha256").asText(""));
    }

    private boolean isRunningHistory(JsonNode item) {
        return "running".equals(item.path("status").asText());
    }

    private HttpResponse<String> sendJson(URI uri, JsonNode body) throws IOException, InterruptedException {
        return sendJson(uri, body, Duration.ofSeconds(30));
    }

    private HttpResponse<String> sendJson(URI uri, JsonNode body, Duration timeout) throws IOException, InterruptedException {
        return sendJson(uri, body, timeout, null);
    }

    private HttpResponse<String> sendJson(URI uri, JsonNode body, Duration timeout, McpSession session) throws IOException, InterruptedException {
        return sendJson(
                uri,
                body,
                timeout,
                session,
                GENERAL_TOOL_RESPONSE_BYTES,
                "AgentBridge JSON response"
        );
    }

    private HttpResponse<String> sendJson(
            URI uri,
            JsonNode body,
            Duration timeout,
            McpSession session,
            int maximumResponseBytes,
            String responseLabel
    ) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream");
        if (session != null) {
            request.header("Mcp-Session-Id", session.id())
                    .header("MCP-Protocol-Version", session.protocolVersion());
        }
        return sendBounded(
                request.POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body))).build(),
                maximumResponseBytes,
                responseLabel
        );
    }

    private McpSession mcpSession(URI mcpUrl) throws IOException, InterruptedException {
        requireLoopbackEndpoint(mcpUrl);
        synchronized (mcpSessionLock) {
            McpSession existing = mcpSessions.get(mcpUrl);
            if (existing != null) {
                return existing;
            }
            McpSession initialized = initializeMcpSessionWithRecovery(mcpUrl);
            mcpSessions.put(mcpUrl, initialized);
            return initialized;
        }
    }

    private McpSession initializeMcpSessionWithRecovery(URI mcpUrl)
            throws IOException, InterruptedException {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return initializeMcpSession(mcpUrl);
            } catch (McpSessionExpiredException exception) {
                if (attempt == 1) {
                    throw exception;
                }
            }
        }
        throw new IllegalStateException("AgentBridge MCP initialization recovery exhausted");
    }

    private <T> T executeWithMcpSessionRecovery(
            URI mcpUrl,
            McpSessionOperation<T> operation
    ) throws IOException, InterruptedException {
        for (int attempt = 0; attempt < 2; attempt++) {
            McpSession session = mcpSession(mcpUrl);
            try {
                return operation.execute(session);
            } catch (McpSessionExpiredException exception) {
                invalidateMcpSession(mcpUrl, session);
                if (attempt == 1) {
                    throw exception;
                }
            }
        }
        throw new IllegalStateException("AgentBridge MCP session recovery exhausted");
    }

    private void invalidateMcpSession(URI mcpUrl, McpSession expiredSession) {
        synchronized (mcpSessionLock) {
            if (mcpSessions.get(mcpUrl) == expiredSession) {
                mcpSessions.remove(mcpUrl);
            }
        }
    }

    private void requireMcpSuccess(HttpResponse<String> response, String message) {
        if (response.statusCode() == 404) {
            throw new McpSessionExpiredException(
                    message + ": HTTP " + response.statusCode() + " " + response.body()
            );
        }
        requireSuccess(response, message);
    }

    private McpSession initializeMcpSession(URI mcpUrl) throws IOException, InterruptedException {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("protocolVersion", MCP_CLIENT_PROTOCOL_VERSION);
        params.set("capabilities", objectMapper.createObjectNode());
        ObjectNode clientInfo = params.putObject("clientInfo");
        clientInfo.put("name", "java-agentbridge-cli");
        clientInfo.put("version", "1.0");

        ObjectNode initialize = objectMapper.createObjectNode();
        initialize.put("jsonrpc", "2.0");
        initialize.put("id", requestIds.incrementAndGet());
        initialize.put("method", "initialize");
        initialize.set("params", params);
        HttpResponse<String> response = sendJson(
                mcpUrl,
                initialize,
                Duration.ofSeconds(30),
                null,
                MCP_METADATA_RESPONSE_BYTES,
                "AgentBridge MCP initialize response"
        );
        requireSuccess(response, "AgentBridge MCP initialize failed");

        String sessionId = response.headers().firstValue("Mcp-Session-Id")
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new IllegalStateException("AgentBridge MCP initialize did not return Mcp-Session-Id"));
        JsonNode initializeResult = objectMapper.readTree(response.body()).path("result");
        String protocolVersion = initializeResult.path("protocolVersion").asText(MCP_CLIENT_PROTOCOL_VERSION);
        McpSession session = new McpSession(sessionId, protocolVersion);

        ObjectNode initialized = objectMapper.createObjectNode();
        initialized.put("jsonrpc", "2.0");
        initialized.put("method", "notifications/initialized");
        initialized.set("params", objectMapper.createObjectNode());
        HttpResponse<String> notificationResponse = sendJson(
                mcpUrl,
                initialized,
                Duration.ofSeconds(30),
                session,
                SMALL_JSON_RESPONSE_BYTES,
                "AgentBridge MCP initialized notification response"
        );
        requireMcpSuccess(notificationResponse, "AgentBridge MCP initialization notification failed");
        return session;
    }

    private Duration toolCallTimeout(JsonNode arguments) {
        if (arguments != null && arguments.path("timeout").canConvertToInt()) {
            int seconds = arguments.path("timeout").asInt();
            if (seconds > 0) {
                return Duration.ofSeconds(seconds).plusSeconds(30);
            }
        }
        return Duration.ofSeconds(30);
    }

    private boolean isNativeDatabaseEvidenceTool(String name) {
        return "cmcp_db_database_execute_sql_query".equals(name);
    }

    private HttpResponse<String> sendBounded(
            HttpRequest request,
            int maximumResponseBytes,
            String responseLabel
    ) throws IOException, InterruptedException {
        return httpClient.send(
                request,
                responseInfo -> new LimitedStringBodySubscriber(
                        maximumResponseBytes,
                        responseLabel,
                        responseInfo.headers().firstValueAsLong("Content-Length").orElse(-1L)
                )
        );
    }

    private void requireLoopbackEndpoint(URI uri) {
        if (uri == null || uri.getUserInfo() != null || uri.getFragment() != null
                || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("AgentBridge endpoint must be an HTTP(S) loopback URL");
        }
        String host = uri.getHost();
        boolean loopback = host != null && (host.equalsIgnoreCase("localhost")
                || host.equals("::1")
                || host.equals("0:0:0:0:0:0:0:1")
                || host.matches("127(?:\\.[0-9]{1,3}){3}"));
        if (!loopback) {
            throw new IllegalArgumentException(
                    "AgentBridge endpoint must use a loopback host; remote/self-signed endpoints are rejected"
            );
        }
    }

    private void requireSuccess(HttpResponse<String> response, String message) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(message + ": HTTP " + response.statusCode() + " " + response.body());
        }
    }

    private String contentText(JsonNode result) {
        StringBuilder text = new StringBuilder();
        JsonNode content = result.path("content");
        if (content.isArray()) {
            for (JsonNode item : content) {
                String value = item.path("text").asText("");
                if (!value.isBlank()) {
                    if (!text.isEmpty()) {
                        text.append('\n');
                    }
                    text.append(value);
                }
            }
        }
        if (text.isEmpty() && result.has("structuredContent")) {
            text.append(result.path("structuredContent"));
        }
        return text.toString();
    }

    private JsonNode structured(String text, JsonNode result) {
        JsonNode structuredContent = result.path("structuredContent");
        boolean emptyStructuredContent = structuredContent.isMissingNode()
                || structuredContent.isNull()
                || structuredContent.isObject() && structuredContent.isEmpty()
                || structuredContent.isArray() && structuredContent.isEmpty()
                || structuredContent.isTextual() && structuredContent.asText().isBlank();
        JsonNode textJson = null;
        if (!text.isBlank()) {
            textJson = completeJsonValue(text);
        }
        if (!emptyStructuredContent) {
            if (structuredContent.isObject() && textJson != null && textJson.isObject()) {
                ObjectNode merged = (ObjectNode) textJson.deepCopy();
                structuredContent.fields().forEachRemaining(
                        entry -> merged.set(entry.getKey(), entry.getValue().deepCopy())
                );
                return merged;
            }
            return structuredContent.deepCopy();
        }
        if (textJson != null) {
            return textJson.deepCopy();
        }
        return objectMapper.createObjectNode();
    }

    private JsonNode completeJsonValue(String text) {
        try (JsonParser parser = objectMapper.getFactory().createParser(text)) {
            JsonNode parsed = objectMapper.readTree(parser);
            return parsed != null && parser.nextToken() == null ? parsed : null;
        } catch (IOException ignored) {
            // Non-JSON text remains available through ToolResponse.text().
            return null;
        }
    }

    public record ToolResponse(JsonNode rawResult, String text, JsonNode structured) {
        public ToolResponse {
            rawResult = copyJson(rawResult);
            structured = copyJson(structured);
        }

        @Override
        public JsonNode rawResult() {
            return copyJson(rawResult);
        }

        @Override
        public JsonNode structured() {
            return copyJson(structured);
        }
    }

    public record ToolDefinition(String name, String description, JsonNode inputSchema) {
        public ToolDefinition {
            inputSchema = copyJson(inputSchema);
        }

        @Override
        public JsonNode inputSchema() {
            return copyJson(inputSchema);
        }
    }

    public record ToolCallRecord(
            String id,
            String title,
            String toolName,
            String kind,
            String status,
            Instant timestamp,
            JsonNode arguments,
            JsonNode result,
            Long durationMs,
            JsonNode hooks) {
        public ToolCallRecord {
            arguments = copyJson(arguments);
            result = copyJson(result);
            hooks = copyJson(hooks);
        }

        @Override
        public JsonNode arguments() {
            return copyJson(arguments);
        }

        @Override
        public JsonNode result() {
            return copyJson(result);
        }

        @Override
        public JsonNode hooks() {
            return copyJson(hooks);
        }
    }

    private static JsonNode copyJson(JsonNode value) {
        return value == null ? null : value.deepCopy();
    }

    private record HistoryPayload(JsonNode arguments, JsonNode result) {
    }

    private record McpSession(String id, String protocolVersion) {
    }

    @FunctionalInterface
    private interface McpSessionOperation<T> {
        T execute(McpSession session) throws IOException, InterruptedException;
    }

    private static final class McpSessionExpiredException extends IllegalStateException {
        private McpSessionExpiredException(String message) {
            super(message);
        }
    }

    private static final class LimitedStringBodySubscriber implements HttpResponse.BodySubscriber<String> {
        private final int maximumBytes;
        private final String label;
        private final long declaredBytes;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<String> body = new CompletableFuture<>();
        private Flow.Subscription subscription;

        private LimitedStringBodySubscriber(int maximumBytes, String label, long declaredBytes) {
            this.maximumBytes = maximumBytes;
            this.label = label;
            this.declaredBytes = declaredBytes;
        }

        @Override
        public CompletionStage<String> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            if (declaredBytes > maximumBytes) {
                subscription.cancel();
                body.completeExceptionally(limitException(declaredBytes));
                return;
            }
            subscription.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (body.isDone()) {
                return;
            }
            long incoming = 0;
            for (ByteBuffer buffer : buffers) {
                incoming += buffer.remaining();
            }
            if ((long) bytes.size() + incoming > maximumBytes) {
                subscription.cancel();
                body.completeExceptionally(limitException((long) bytes.size() + incoming));
                return;
            }
            for (ByteBuffer buffer : buffers) {
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable throwable) {
            body.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            if (!body.isDone()) {
                body.complete(bytes.toString(StandardCharsets.UTF_8));
            }
        }

        private IOException limitException(long observedBytes) {
            return new IOException(label + " exceeded byte limit of " + maximumBytes
                    + " bytes (observed at least " + observedBytes + ")");
        }
    }

    private static HttpClient localAgentBridgeHttpClient() {
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }}, new SecureRandom());
            SSLParameters sslParameters = new SSLParameters();
            sslParameters.setEndpointIdentificationAlgorithm("");
            return HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .sslContext(sslContext)
                    .sslParameters(sslParameters)
                    .build();
        } catch (Exception exception) {
            throw new IllegalStateException("failed to create AgentBridge HTTP client", exception);
        }
    }
}
