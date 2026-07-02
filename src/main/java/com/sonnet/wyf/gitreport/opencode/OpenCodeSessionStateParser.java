package com.sonnet.wyf.gitreport.opencode;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.regex.Pattern;

final class OpenCodeSessionStateParser {
    private static final Pattern DONE_TEXT = Pattern.compile("(?im)^\\s*DONE\\b");
    private static final Pattern BLOCKED_TEXT = Pattern.compile("(?im)^\\s*BLOCKED\\b");

    OpenCodeSessionState infer(JsonNode response) {
        JsonNode messages = response.isArray() ? response : response.path("data");
        if (!messages.isArray()) {
            return new OpenCodeSessionState("", false, false, "messages", "");
        }
        JsonNode lastAssistant = null;
        boolean hasMessage = false;
        for (JsonNode message : messages) {
            hasMessage = true;
            if ("assistant".equals(message.path("type").asText())) {
                lastAssistant = message;
            }
        }
        if (lastAssistant == null) {
            return new OpenCodeSessionState(hasMessage ? "submitted" : "", false, false, "messages", "");
        }
        if (messageHasError(lastAssistant)) {
            return new OpenCodeSessionState("error", true, false, "message_error", assistantText(lastAssistant));
        }
        String finish = lastAssistant.path("finish").asText("");
        if (isAbortFinish(finish)) {
            return new OpenCodeSessionState("aborted", true, false, "finish", assistantText(lastAssistant));
        }
        if (!finish.isBlank() || !lastAssistant.path("time").path("completed").isMissingNode()) {
            return new OpenCodeSessionState("idle", true, true, "finish", assistantText(lastAssistant));
        }
        String assistantText = assistantText(lastAssistant);
        if (DONE_TEXT.matcher(assistantText).find()) {
            return new OpenCodeSessionState("done", true, true, "assistant_text", assistantText);
        }
        if (BLOCKED_TEXT.matcher(assistantText).find()) {
            return new OpenCodeSessionState("blocked", true, false, "assistant_text", assistantText);
        }
        return new OpenCodeSessionState("running", false, false, "messages", assistantText);
    }

    private boolean messageHasError(JsonNode message) {
        String finish = message.path("finish").asText("");
        if (finish.equalsIgnoreCase("error") || finish.equalsIgnoreCase("failed")) {
            return true;
        }
        if (!message.path("error").isMissingNode()) {
            return true;
        }
        JsonNode content = message.path("content");
        if (!content.isArray()) {
            return false;
        }
        for (JsonNode part : content) {
            String type = part.path("type").asText("");
            if (type.equalsIgnoreCase("error")) {
                return true;
            }
            String status = part.path("state").path("status").asText("");
            if (status.equalsIgnoreCase("error")) {
                return true;
            }
        }
        return false;
    }

    private boolean isAbortFinish(String finish) {
        String normalized = finish == null ? "" : finish.toLowerCase();
        return normalized.equals("abort") || normalized.equals("aborted") || normalized.equals("cancel") || normalized.equals("canceled") || normalized.equals("cancelled");
    }

    private String assistantText(JsonNode message) {
        StringBuilder text = new StringBuilder();
        appendText(text, message.path("text"));
        JsonNode content = message.path("content");
        if (content.isArray()) {
            for (JsonNode part : content) {
                appendText(text, part.path("text"));
                appendText(text, part.path("content"));
            }
        }
        return text.toString().trim();
    }

    private void appendText(StringBuilder text, JsonNode value) {
        if (!value.isTextual()) {
            return;
        }
        if (text.length() > 0) {
            text.append('\n');
        }
        text.append(value.asText());
    }
}
