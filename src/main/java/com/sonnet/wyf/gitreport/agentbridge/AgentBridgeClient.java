package com.sonnet.wyf.gitreport.agentbridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class AgentBridgeClient {
    private static final String MCP_CLIENT_PROTOCOL_VERSION = "2025-03-26";
    public static final String MYBATIS_SQL_REVIEW_MINIMUM_AGENTBRIDGE_VERSION = "1.200.0";
    private static final int SMALL_JSON_RESPONSE_BYTES = 64 * 1024;
    private static final int MCP_METADATA_RESPONSE_BYTES = 1024 * 1024;
    private static final int SQL_TOOL_RESPONSE_BYTES = 262_144 + 8 * 1024;
    private static final int GENERAL_TOOL_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final int TOOL_CALL_PAGE_RESPONSE_BYTES = 1024 * 1024;
    private static final int TOOL_CALL_HISTORY_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_TOOL_CALL_PAGES = 1_000;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final AtomicLong requestIds = new AtomicLong();
    private final Object mcpSessionLock = new Object();
    private final Map<URI, McpSession> mcpSessions = new HashMap<>();
    private final Map<URI, MyBatisAuditBinding> myBatisWebBindings = new ConcurrentHashMap<>();
    private final Map<URI, MyBatisAuditBinding> myBatisMcpBindings = new ConcurrentHashMap<>();

    public AgentBridgeClient(ObjectMapper objectMapper) {
        this(objectMapper, localAgentBridgeHttpClient());
    }

    AgentBridgeClient(ObjectMapper objectMapper, HttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    public void postPrompt(URI webBaseUrl, String prompt) throws IOException, InterruptedException {
        MyBatisAuditBinding strictBinding = myBatisWebBindings.get(normalizedWebBase(webBaseUrl));
        if (strictBinding != null) {
            requireLoopbackEndpoint(webBaseUrl);
            agentBridgeInfo(webBaseUrl);
        }
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
                throw new IllegalStateException("AgentBridge agent did not finish within " + timeout.toMinutes() + " minutes");
            }
            Thread.sleep(Math.max(1, pollInterval.toMillis()));
        }
    }

    public boolean isRunning(URI webBaseUrl) throws IOException, InterruptedException {
        return agentBridgeInfo(webBaseUrl).path("running").asBoolean(false);
    }

    public void requireMyBatisSqlReviewCapabilities(URI webBaseUrl)
            throws IOException, InterruptedException {
        requireLoopbackEndpoint(webBaseUrl);
        JsonNode info = agentBridgeInfo(webBaseUrl);
        strictMyBatisAuditBinding(info);
    }

    public MyBatisAuditBinding bindMyBatisSqlReviewEndpoints(URI webBaseUrl, URI mcpUrl)
            throws IOException, InterruptedException {
        requireLoopbackEndpoint(webBaseUrl);
        requireLoopbackEndpoint(mcpUrl);
        MyBatisAuditBinding binding = strictMyBatisAuditBinding(agentBridgeInfo(webBaseUrl));
        synchronized (mcpSessionLock) {
            myBatisWebBindings.put(normalizedWebBase(webBaseUrl), binding);
            myBatisMcpBindings.put(mcpUrl, binding);
            mcpSessions.remove(mcpUrl);
        }
        McpSession session = mcpSession(mcpUrl);
        if (!binding.equals(session.auditBinding())) {
            throw new IllegalStateException("AgentBridge Web/MCP identity mismatch");
        }
        return binding;
    }

    private MyBatisAuditBinding strictMyBatisAuditBinding(JsonNode info) {
        String version = info.path("version").asText("").strip();
        if (!isAtLeastVersion(version, MYBATIS_SQL_REVIEW_MINIMUM_AGENTBRIDGE_VERSION)) {
            throw incompatibleMyBatisAuditProtocol(version);
        }
        JsonNode capability = info.path("capabilities").path("mybatisSqlReviewAudit");
        List<String> requiredBooleans = List.of(
                "untruncatedStructuredToolArguments",
                "untruncatedStructuredToolResults",
                "immutableToolCallSnapshot",
                "stableToolCallTotal",
                "explicitToolCallHistoryComplete",
                "previewMaxRowsRequired"
        );
        if (!capability.isObject()
                || !capability.path("contractVersion").isIntegralNumber()
                || capability.path("contractVersion").intValue() != 1) {
            throw incompatibleMyBatisAuditProtocol(version);
        }
        for (String requirement : requiredBooleans) {
            if (!capability.path(requirement).isBoolean()
                    || !capability.path(requirement).booleanValue()) {
                throw new IllegalStateException(
                        "AgentBridge " + version + " MyBatis SQL review capability is missing required "
                                + requirement + "; strict audit protocol is incompatible"
                );
            }
        }
        if (!capability.path("serverEnforcedPreviewMaxRows").isIntegralNumber()
                || capability.path("serverEnforcedPreviewMaxRows").intValue() != 20) {
            throw incompatibleMyBatisAuditProtocol(version);
        }
        JsonNode policy = capability.path("executeSqlQueryPolicy");
        for (String requirement : List.of(
                "simpleSelectGrammar",
                "safeRelationAllowlist",
                "functionsForbidden",
                "systemSideEffectsForbidden"
        )) {
            if (!policy.path(requirement).isBoolean() || !policy.path(requirement).booleanValue()) {
                throw new IllegalStateException(
                        "AgentBridge " + version + " execute_sql_query policy is missing required " + requirement
                );
            }
        }
        if (!policy.path("maxScenarios").isIntegralNumber() || policy.path("maxScenarios").intValue() != 3
                || !policy.path("maxRows").isIntegralNumber() || policy.path("maxRows").intValue() != 20
                || !policy.path("maxTimeoutSeconds").isIntegralNumber()
                || policy.path("maxTimeoutSeconds").intValue() != 30) {
            throw new IllegalStateException(
                    "AgentBridge " + version
                            + " execute_sql_query policy must enforce scenarios<=3, rows<=20, timeout<=30"
            );
        }
        String fingerprint = requiredFingerprint(policy, "policyFingerprint", "execute_sql_query policy");
        return new MyBatisAuditBinding(requiredIdentity(info.path("identity"), "AgentBridge /info"), fingerprint);
    }

    private JsonNode agentBridgeInfo(URI webBaseUrl) throws IOException, InterruptedException {
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
        MyBatisAuditBinding expected = myBatisWebBindings.get(normalizedWebBase(webBaseUrl));
        if (expected != null && !expected.equals(strictMyBatisAuditBinding(info))) {
            throw new IllegalStateException(
                    "AgentBridge GET /info identity or policy fingerprint mismatch"
            );
        }
        return info;
    }

    private BridgeIdentity requiredIdentity(JsonNode value, String label) {
        String instanceId = value.path("instanceId").asText("").strip();
        String projectId = value.path("projectId").asText("").strip();
        String instanceNonce = value.path("instanceNonce").asText("").strip();
        if (!isIdentityToken(instanceId, 3) || !isIdentityToken(projectId, 3)
                || !isIdentityToken(instanceNonce, 16)) {
            throw new IllegalStateException(label + " must provide non-reusable instanceId/projectId/instanceNonce identity");
        }
        return new BridgeIdentity(instanceId, projectId, instanceNonce);
    }

    private boolean isIdentityToken(String value, int minimumLength) {
        return value.length() >= minimumLength
                && value.length() <= 256
                && value.matches("[A-Za-z0-9._:-]+");
    }

    private String requiredFingerprint(JsonNode node, String field, String label) {
        String value = node.path(field).asText("").strip().toLowerCase(java.util.Locale.ROOT);
        if (!value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalStateException(label + " must provide a SHA-256 " + field);
        }
        return value;
    }

    private IllegalStateException incompatibleMyBatisAuditProtocol(String version) {
        String reported = version.isBlank() ? "unknown" : version;
        return new IllegalStateException(
                "AgentBridge " + reported + " is incompatible with MyBatis SQL review; requires AgentBridge >= "
                        + MYBATIS_SQL_REVIEW_MINIMUM_AGENTBRIDGE_VERSION
                        + " and explicit capabilities for untruncated structured arguments/results, immutable "
                        + "paginated tool-call snapshots with stable total/complete, and server-enforced preview max 20"
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
        McpSession session = mcpSession(mcpUrl);
        ObjectNode params = objectMapper.createObjectNode();
        params.put("name", name);
        params.set("arguments", arguments == null ? objectMapper.createObjectNode() : arguments);
        ObjectNode body = objectMapper.createObjectNode();
        body.put("jsonrpc", "2.0");
        body.put("id", requestIds.incrementAndGet());
        body.put("method", "tools/call");
        body.set("params", params);

        int responseLimit = isSqlEvidenceTool(name)
                ? SQL_TOOL_RESPONSE_BYTES
                : GENERAL_TOOL_RESPONSE_BYTES;
        HttpResponse<String> response = sendJson(
                mcpUrl,
                body,
                toolCallTimeout(arguments),
                session,
                responseLimit,
                "AgentBridge MCP tools/call response for " + name
        );
        requireSuccess(response, "AgentBridge MCP tools/call failed: " + name);
        JsonNode root = objectMapper.readTree(response.body());
        if (root.hasNonNull("error")) {
            throw new IllegalStateException("AgentBridge MCP tool failed: " + root.path("error"));
        }
        JsonNode result = root.path("result");
        requireBoundPayload(mcpUrl, result, "AgentBridge MCP tools/call result");
        String text = contentText(result);
        return new ToolResponse(result, text, structured(text, result));
    }

    public List<ToolDefinition> listTools(URI mcpUrl) throws IOException, InterruptedException {
        McpSession session = mcpSession(mcpUrl);
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
            requireSuccess(response, "AgentBridge MCP tools/list failed");
            JsonNode root = objectMapper.readTree(response.body());
            if (root.hasNonNull("error")) {
                throw new IllegalStateException("AgentBridge MCP tools/list failed: " + root.path("error"));
            }
            JsonNode result = root.path("result");
            MyBatisAuditBinding binding = requireBoundPayload(
                    mcpUrl, result, "AgentBridge MCP tools/list result"
            );
            if (!result.path("tools").isArray()) {
                throw new IllegalStateException("AgentBridge MCP tools/list returned no tools array");
            }
            for (JsonNode tool : result.path("tools")) {
                if (binding != null && isSqlEvidenceTool(tool.path("name").asText())
                        && !binding.policyFingerprint().equals(
                        tool.path("inputSchema").path("x-agentbridge-policyFingerprint").asText())) {
                    throw new IllegalStateException(
                            "AgentBridge MCP tool schema policy fingerprint mismatch: "
                                    + tool.path("name").asText("<unknown>")
                    );
                }
                tools.add(new ToolDefinition(
                        tool.path("name").asText(),
                        tool.path("description").asText(""),
                        tool.path("inputSchema")
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
        URI endpoint = webBaseUrl.resolve("/tool-calls");
        URI pageUri = endpoint;
        Map<String, JsonNode> uniqueItems = new LinkedHashMap<>();
        Set<String> observedTokens = new HashSet<>();
        String stableSnapshot = null;
        Long stableTotal = null;
        String paginationParameter = null;
        long accumulatedBytes = 0;
        boolean complete = false;

        for (int pageNumber = 0; pageNumber < MAX_TOOL_CALL_PAGES && !complete; pageNumber++) {
            HttpRequest request = HttpRequest.newBuilder(pageUri)
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = sendBounded(
                    request,
                    TOOL_CALL_PAGE_RESPONSE_BYTES,
                    "AgentBridge GET /tool-calls page response"
            );
            requireSuccess(response, "AgentBridge GET /tool-calls failed");
            accumulatedBytes += response.body().getBytes(StandardCharsets.UTF_8).length;
            if (accumulatedBytes > TOOL_CALL_HISTORY_RESPONSE_BYTES) {
                throw new IllegalStateException(
                        "AgentBridge GET /tool-calls history exceeded cumulative byte limit of "
                                + TOOL_CALL_HISTORY_RESPONSE_BYTES
                );
            }

            JsonNode root = objectMapper.readTree(response.body());
            MyBatisAuditBinding historyBinding = myBatisWebBindings.get(normalizedWebBase(webBaseUrl));
            if (historyBinding != null) {
                requireBinding(root, historyBinding, "AgentBridge GET /tool-calls snapshot");
            }
            JsonNode items;
            String nextToken = null;
            String nextParameter = null;
            if (root.isArray()) {
                throw new IllegalStateException(
                        "AgentBridge GET /tool-calls array response cannot prove an immutable complete snapshot"
                );
            } else if (root.isObject()) {
                boolean toolCallsArray = root.path("toolCalls").isArray();
                boolean itemsArray = root.path("items").isArray();
                if (toolCallsArray == itemsArray) {
                    throw new IllegalStateException(
                            "AgentBridge GET /tool-calls must return exactly one toolCalls or items array"
                    );
                }
                items = toolCallsArray ? root.path("toolCalls") : root.path("items");
                if (!root.path("complete").isBoolean()) {
                    throw new IllegalStateException(
                            "AgentBridge GET /tool-calls response cannot prove complete history without boolean complete"
                    );
                }
                complete = root.path("complete").booleanValue();
                if (root.has("hasMore") && (!root.path("hasMore").isBoolean()
                        || root.path("hasMore").booleanValue() == complete)) {
                    throw new IllegalStateException(
                            "AgentBridge GET /tool-calls complete/hasMore contract is inconsistent"
                    );
                }

                String snapshot = root.path("snapshotToken").asText("").strip();
                Long total = requiredOptionalTotal(root);
                if (snapshot.isBlank() || total == null) {
                    throw new IllegalStateException(
                            "AgentBridge GET /tool-calls lacks an immutable snapshotToken/total boundary"
                    );
                }
                if (stableSnapshot == null) {
                    stableSnapshot = snapshot;
                    stableTotal = total;
                } else if (!stableSnapshot.equals(snapshot) || !stableTotal.equals(total)) {
                    throw new IllegalStateException(
                            "AgentBridge GET /tool-calls page drift changed snapshotToken or total"
                    );
                }

                String cursor = root.path("nextCursor").asText("").strip();
                String pageToken = root.path("nextPageToken").asText("").strip();
                if (!cursor.isBlank() && !pageToken.isBlank()) {
                    throw new IllegalStateException(
                            "AgentBridge GET /tool-calls returned multiple continuation token fields"
                    );
                }
                if (complete) {
                    if (!cursor.isBlank() || !pageToken.isBlank()) {
                        throw new IllegalStateException(
                                "AgentBridge GET /tool-calls complete page unexpectedly returned a continuation token"
                        );
                    }
                } else {
                    if (cursor.isBlank() && pageToken.isBlank()) {
                        throw new IllegalStateException(
                                "AgentBridge GET /tool-calls incomplete page is missing continuation token"
                        );
                    }
                    nextParameter = cursor.isBlank() ? "pageToken" : "cursor";
                    nextToken = cursor.isBlank() ? pageToken : cursor;
                    if (paginationParameter == null) {
                        paginationParameter = nextParameter;
                    } else if (!paginationParameter.equals(nextParameter)) {
                        throw new IllegalStateException(
                                "AgentBridge GET /tool-calls page drift changed continuation token type"
                        );
                    }
                    String observed = nextParameter + "=" + nextToken;
                    if (!observedTokens.add(observed)) {
                        throw new IllegalStateException(
                                "AgentBridge GET /tool-calls repeated continuation token: " + nextToken
                        );
                    }
                }
            } else {
                throw new IllegalStateException("AgentBridge GET /tool-calls returned no tool-call array");
            }

            for (JsonNode item : items) {
                if (historyBinding != null) {
                    requireBinding(item, historyBinding, "AgentBridge GET /tool-calls item");
                }
                String id = item.path("id").asText("");
                if (id.isBlank()) {
                    throw new IllegalStateException("AgentBridge GET /tool-calls returned an item without id");
                }
                JsonNode previous = uniqueItems.putIfAbsent(id, item.deepCopy());
                if (previous != null && !previous.equals(item)) {
                    throw new IllegalStateException(
                            "AgentBridge GET /tool-calls page drift returned conflicting duplicate id: " + id
                    );
                }
            }
            if (complete) {
                Long declaredTotal = root.isObject() ? requiredOptionalTotal(root) : null;
                Long expectedTotal = stableTotal == null ? declaredTotal : stableTotal;
                if (expectedTotal != null && expectedTotal != uniqueItems.size()) {
                    throw new IllegalStateException(
                            "AgentBridge GET /tool-calls complete history total does not match unique records"
                    );
                }
            } else {
                pageUri = continuationUri(endpoint, nextParameter, nextToken);
            }
        }
        if (!complete) {
            throw new IllegalStateException(
                    "AgentBridge GET /tool-calls exceeded page limit without proving complete history"
            );
        }

        List<ToolCallRecord> calls = new ArrayList<>();
        for (JsonNode item : uniqueItems.values()) {
            calls.add(new ToolCallRecord(
                    item.path("id").asText(),
                    item.path("title").asText(""),
                    item.path("toolName").asText(),
                    item.path("kind").asText(""),
                    item.path("status").asText(""),
                    Instant.parse(item.path("timestamp").asText()),
                    item.path("arguments"),
                    item.path("result"),
                    item.hasNonNull("durationMs") ? item.path("durationMs").longValue() : null,
                    item.path("hooks"),
                    item.path("identity").path("instanceId").asText(""),
                    item.path("identity").path("projectId").asText(""),
                    item.path("identity").path("instanceNonce").asText(""),
                    item.path("policyFingerprint").asText(""),
                    item.path("databaseHostFingerprint").asText(""),
                    item.path("databaseInstanceFingerprint").asText(""),
                    item.path("topologyFingerprint").asText("")
            ));
        }
        return List.copyOf(calls);
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
        synchronized (mcpSessionLock) {
            McpSession existing = mcpSessions.get(mcpUrl);
            if (existing != null) {
                return existing;
            }
            McpSession initialized = initializeMcpSession(mcpUrl);
            mcpSessions.put(mcpUrl, initialized);
            return initialized;
        }
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
        MyBatisAuditBinding expectedBinding = myBatisMcpBindings.get(mcpUrl);
        MyBatisAuditBinding actualBinding = null;
        if (expectedBinding != null) {
            actualBinding = new MyBatisAuditBinding(
                    requiredIdentity(initializeResult.path("identity"), "AgentBridge MCP initialize"),
                    requiredFingerprint(initializeResult, "policyFingerprint", "AgentBridge MCP initialize")
            );
            if (!expectedBinding.equals(actualBinding)) {
                throw new IllegalStateException("AgentBridge Web/MCP identity mismatch");
            }
        }
        McpSession session = new McpSession(sessionId, protocolVersion, actualBinding);

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
        requireSuccess(notificationResponse, "AgentBridge MCP initialization notification failed");
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

    private boolean isSqlEvidenceTool(String name) {
        return "execute_sql_query".equals(name) || "preview_table_data".equals(name);
    }

    private Long requiredOptionalTotal(JsonNode root) {
        if (!root.has("total")) {
            return null;
        }
        if (!root.path("total").isIntegralNumber() || root.path("total").longValue() < 0) {
            throw new IllegalStateException(
                    "AgentBridge GET /tool-calls total must be a non-negative integer"
            );
        }
        return root.path("total").longValue();
    }

    private MyBatisAuditBinding requireBoundPayload(URI mcpUrl, JsonNode payload, String label) {
        MyBatisAuditBinding expected = myBatisMcpBindings.get(mcpUrl);
        if (expected != null) {
            requireBinding(payload, expected, label);
        }
        return expected;
    }

    private void requireBinding(JsonNode payload, MyBatisAuditBinding expected, String label) {
        MyBatisAuditBinding actual = new MyBatisAuditBinding(
                requiredIdentity(payload.path("identity"), label),
                requiredFingerprint(payload, "policyFingerprint", label)
        );
        if (!expected.equals(actual)) {
            throw new IllegalStateException(label + " identity or policy fingerprint mismatch");
        }
    }

    private URI continuationUri(URI endpoint, String parameter, String token) {
        String query = parameter + "=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
        return URI.create(endpoint.toString() + "?" + query);
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

    private URI normalizedWebBase(URI uri) {
        String value = uri.toString();
        return URI.create(value.endsWith("/") ? value.substring(0, value.length() - 1) : value);
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
        if (result.has("structuredContent")) {
            return result.path("structuredContent");
        }
        if (!text.isBlank()) {
            try {
                return objectMapper.readTree(text);
            } catch (Exception ignored) {
                return objectMapper.createObjectNode();
            }
        }
        return objectMapper.createObjectNode();
    }

    public record ToolResponse(JsonNode rawResult, String text, JsonNode structured) {
    }

    public record ToolDefinition(String name, String description, JsonNode inputSchema) {
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
            JsonNode hooks,
            String bridgeInstanceId,
            String bridgeProjectId,
            String bridgeInstanceNonce,
            String policyFingerprint,
            String databaseHostFingerprint,
            String databaseInstanceFingerprint,
            String topologyFingerprint) {
        public ToolCallRecord(
                String id,
                String title,
                String toolName,
                String kind,
                String status,
                Instant timestamp,
                JsonNode arguments,
                JsonNode result,
                Long durationMs,
                JsonNode hooks
        ) {
            this(id, title, toolName, kind, status, timestamp, arguments, result, durationMs, hooks,
                    "", "", "", "", "", "", "");
        }
    }

    public record BridgeIdentity(String instanceId, String projectId, String instanceNonce) {
    }

    public record MyBatisAuditBinding(BridgeIdentity identity, String policyFingerprint) {
    }

    private record McpSession(String id, String protocolVersion, MyBatisAuditBinding auditBinding) {
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
