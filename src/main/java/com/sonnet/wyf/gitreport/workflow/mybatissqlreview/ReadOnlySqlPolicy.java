package com.sonnet.wyf.gitreport.workflow.mybatissqlreview;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class ReadOnlySqlPolicy {
    private static final Set<String> DML = Set.of("INSERT", "UPDATE", "DELETE", "MERGE", "UPSERT");
    private static final Set<String> DDL = Set.of(
            "CREATE", "ALTER", "DROP", "TRUNCATE", "COMMENT", "GRANT", "REVOKE"
    );
    private static final Set<String> READ_ONLY_FUNCTIONS = Set.of(
            "ABS", "AVG", "CAST", "CEIL", "CEILING", "CHAR_LENGTH", "COALESCE", "CONCAT",
            "COUNT", "DATE_TRUNC", "EXTRACT", "FLOOR", "GREATEST", "LEAST", "LENGTH", "LOWER",
            "LTRIM", "MAX", "MIN", "NULLIF", "OCTET_LENGTH", "OVERLAY", "POSITION", "REPLACE",
            "ROUND", "RTRIM", "SUBSTRING", "SUM", "TO_CHAR", "TO_DATE", "TRIM", "UPPER"
    );
    private static final Set<String> PARENTHESIS_CONTROLS = Set.of(
            "ALL", "AND", "ANY", "AS", "DISTINCT", "EXISTS", "FILTER", "FROM", "HAVING", "IN",
            "JOIN", "NOT", "ON", "OR", "OVER", "SELECT", "SOME", "VALUES", "WHERE", "WITH"
    );

    private final int maximumRows;

    ReadOnlySqlPolicy(int maximumRows) {
        if (maximumRows < 1) {
            throw new IllegalArgumentException("maximumRows must be positive");
        }
        this.maximumRows = maximumRows;
    }

    void validate(String sql, String callId) {
        if (sql.isBlank()) {
            throw violation(callId, "queryText is required");
        }
        List<Token> tokens;
        try {
            tokens = tokenize(sql);
        } catch (IllegalArgumentException exception) {
            throw violation(callId, exception.getMessage());
        }
        if (tokens.isEmpty()) {
            throw violation(callId, "queryText is required");
        }
        String first = word(tokens.getFirst());
        if ("COPY".equals(first)) {
            throw violation(callId, "COPY is forbidden");
        }
        if ("CALL".equals(first)) {
            throw violation(callId, "CALL is forbidden");
        }
        if (containsWord(tokens, DDL)) {
            throw violation(callId, "DDL is forbidden");
        }
        if (!"SELECT".equals(first) && !"WITH".equals(first)) {
            throw violation(callId, "query must be a read-only SELECT or WITH...SELECT");
        }
        if (containsLockingClause(tokens)) {
            throw violation(callId, "locking clause FOR UPDATE/SHARE is forbidden");
        }
        if (containsSelectInto(tokens)) {
            throw violation(callId, "SELECT INTO is forbidden");
        }
        if ("WITH".equals(first)) {
            String main = mainWithStatement(tokens);
            if (!"SELECT".equals(main)) {
                throw violation(callId, "WITH main statement must be SELECT");
            }
            if (containsWord(tokens, DML)) {
                throw violation(callId, "DML CTE is forbidden");
            }
        } else if (containsWord(tokens, DML)) {
            throw violation(callId, "query must be a read-only SELECT or WITH...SELECT");
        }
        if (containsSequenceSyntax(tokens)) {
            throw violation(callId, "sequence mutation syntax is forbidden");
        }
        validateFunctions(tokens, callId);
        validateTopLevelLimit(tokens, callId);
    }

    private List<Token> tokenize(String sql) {
        List<Token> tokens = new ArrayList<>();
        int depth = 0;
        int index = 0;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            char next = index + 1 < sql.length() ? sql.charAt(index + 1) : '\0';
            if (Character.isWhitespace(current)) {
                index++;
                continue;
            }
            if (current == '-' && next == '-') {
                index += 2;
                while (index < sql.length() && sql.charAt(index) != '\n' && sql.charAt(index) != '\r') {
                    index++;
                }
                continue;
            }
            if (current == '/' && next == '*') {
                index += 2;
                boolean closed = false;
                while (index < sql.length()) {
                    char comment = sql.charAt(index);
                    char commentNext = index + 1 < sql.length() ? sql.charAt(index + 1) : '\0';
                    if (comment == '/' && commentNext == '*') {
                        throw new IllegalArgumentException("nested block comment is unsupported");
                    }
                    if (comment == '*' && commentNext == '/') {
                        index += 2;
                        closed = true;
                        break;
                    }
                    index++;
                }
                if (!closed) {
                    throw new IllegalArgumentException("unterminated block comment");
                }
                continue;
            }
            if (current == '$') {
                throw new IllegalArgumentException("dollar-quoted strings and dollar syntax are unsupported");
            }
            if ((current == 'U' || current == 'u') && next == '&'
                    && index + 2 < sql.length()
                    && (sql.charAt(index + 2) == '\'' || sql.charAt(index + 2) == '"')) {
                throw new IllegalArgumentException("unsupported string prefix or quoting form");
            }
            if (isUnsupportedStringPrefix(current, next)) {
                throw new IllegalArgumentException("unsupported string prefix or quoting form");
            }
            if (current == '\'') {
                int start = index++;
                boolean closed = false;
                while (index < sql.length()) {
                    char value = sql.charAt(index);
                    char valueNext = index + 1 < sql.length() ? sql.charAt(index + 1) : '\0';
                    if (value == '\\') {
                        throw new IllegalArgumentException("backslash escapes in strings are unsupported");
                    }
                    if (value == '\'' && valueNext == '\'') {
                        index += 2;
                    } else if (value == '\'') {
                        index++;
                        closed = true;
                        break;
                    } else {
                        index++;
                    }
                }
                if (!closed) {
                    throw new IllegalArgumentException("unterminated string literal");
                }
                tokens.add(new Token(sql.substring(start, index), TokenKind.STRING, depth));
                continue;
            }
            if (current == '"') {
                int start = index++;
                boolean closed = false;
                while (index < sql.length()) {
                    char value = sql.charAt(index);
                    char valueNext = index + 1 < sql.length() ? sql.charAt(index + 1) : '\0';
                    if (value == '"' && valueNext == '"') {
                        index += 2;
                    } else if (value == '"') {
                        index++;
                        closed = true;
                        break;
                    } else {
                        index++;
                    }
                }
                if (!closed) {
                    throw new IllegalArgumentException("unterminated quoted identifier");
                }
                tokens.add(new Token(sql.substring(start, index), TokenKind.QUOTED_IDENTIFIER, depth));
                continue;
            }
            if (current == '`' || current == '[' || current == ']') {
                throw new IllegalArgumentException("unsupported quoting form");
            }
            if (Character.isLetter(current) || current == '_') {
                int start = index++;
                while (index < sql.length()
                        && (Character.isLetterOrDigit(sql.charAt(index)) || sql.charAt(index) == '_')) {
                    index++;
                }
                tokens.add(new Token(
                        sql.substring(start, index).toUpperCase(Locale.ROOT),
                        TokenKind.WORD,
                        depth
                ));
                continue;
            }
            if (Character.isDigit(current)) {
                int start = index++;
                while (index < sql.length() && Character.isDigit(sql.charAt(index))) {
                    index++;
                }
                if (index < sql.length() && (sql.charAt(index) == '.' || Character.isLetter(sql.charAt(index)))) {
                    while (index < sql.length()
                            && (Character.isLetterOrDigit(sql.charAt(index)) || sql.charAt(index) == '.')) {
                        index++;
                    }
                }
                tokens.add(new Token(sql.substring(start, index), TokenKind.NUMBER, depth));
                continue;
            }
            if (current == ';') {
                throw new IllegalArgumentException("multiple statements and semicolons are forbidden");
            }
            if (current == '(') {
                tokens.add(new Token("(", TokenKind.SYMBOL, depth));
                depth++;
                index++;
                continue;
            }
            if (current == ')') {
                depth--;
                if (depth < 0) {
                    throw new IllegalArgumentException("unbalanced SQL parentheses");
                }
                tokens.add(new Token(")", TokenKind.SYMBOL, depth));
                index++;
                continue;
            }
            if (current == '?' || current == '#' || current == '{' || current == '}' || current == '@') {
                throw new IllegalArgumentException("ambiguous or unresolved SQL syntax is unsupported");
            }
            tokens.add(new Token(String.valueOf(current), TokenKind.SYMBOL, depth));
            index++;
        }
        if (depth != 0) {
            throw new IllegalArgumentException("unbalanced SQL parentheses");
        }
        return List.copyOf(tokens);
    }

    private boolean isUnsupportedStringPrefix(char current, char next) {
        if (next != '\'') {
            return false;
        }
        char upper = Character.toUpperCase(current);
        return upper == 'E' || upper == 'B' || upper == 'X' || upper == 'N';
    }

    private boolean containsWord(List<Token> tokens, Set<String> words) {
        for (Token token : tokens) {
            if (token.kind() == TokenKind.WORD && words.contains(token.text())) {
                return true;
            }
        }
        return false;
    }

    private boolean containsLockingClause(List<Token> tokens) {
        for (int index = 0; index < tokens.size(); index++) {
            if (!"FOR".equals(word(tokens.get(index)))) {
                continue;
            }
            List<String> following = followingWords(tokens, index + 1, 3);
            if (!following.isEmpty() && ("UPDATE".equals(following.getFirst())
                    || "SHARE".equals(following.getFirst()))) {
                return true;
            }
            if (following.size() >= 3
                    && "NO".equals(following.get(0))
                    && "KEY".equals(following.get(1))
                    && "UPDATE".equals(following.get(2))) {
                return true;
            }
            if (following.size() >= 2
                    && "KEY".equals(following.get(0))
                    && "SHARE".equals(following.get(1))) {
                return true;
            }
        }
        return false;
    }

    private List<String> followingWords(List<Token> tokens, int start, int maximum) {
        List<String> words = new ArrayList<>();
        for (int index = start; index < tokens.size() && words.size() < maximum; index++) {
            String word = word(tokens.get(index));
            if (word != null) {
                words.add(word);
            } else if (!",".equals(tokens.get(index).text())) {
                break;
            }
        }
        return words;
    }

    private boolean containsSelectInto(List<Token> tokens) {
        boolean selectSeen = false;
        for (Token token : tokens) {
            String word = word(token);
            if ("SELECT".equals(word)) {
                selectSeen = true;
            } else if (selectSeen && "INTO".equals(word)) {
                return true;
            }
        }
        return false;
    }

    private String mainWithStatement(List<Token> tokens) {
        for (int index = 1; index < tokens.size(); index++) {
            Token token = tokens.get(index);
            if (token.depth() == 0 && token.kind() == TokenKind.WORD
                    && ("SELECT".equals(token.text()) || DML.contains(token.text()))) {
                return token.text();
            }
        }
        return "";
    }

    private boolean containsSequenceSyntax(List<Token> tokens) {
        for (int index = 0; index + 2 < tokens.size(); index++) {
            if ("NEXT".equals(word(tokens.get(index)))
                    && "VALUE".equals(word(tokens.get(index + 1)))
                    && "FOR".equals(word(tokens.get(index + 2)))) {
                return true;
            }
        }
        return false;
    }

    private void validateFunctions(List<Token> tokens, String callId) {
        for (int index = 0; index + 1 < tokens.size(); index++) {
            Token candidate = tokens.get(index);
            if (!"(".equals(tokens.get(index + 1).text())) {
                continue;
            }
            if (index > 0 && ".".equals(tokens.get(index - 1).text())) {
                throw violation(callId, "schema-qualified function calls are forbidden");
            }
            if (candidate.kind() == TokenKind.QUOTED_IDENTIFIER) {
                throw violation(callId, "quoted function calls are forbidden");
            }
            if (candidate.kind() != TokenKind.WORD || PARENTHESIS_CONTROLS.contains(candidate.text())) {
                continue;
            }
            if (!READ_ONLY_FUNCTIONS.contains(candidate.text())) {
                throw violation(callId, "function is not allowlisted: " + candidate.text());
            }
        }
    }

    private void validateTopLevelLimit(List<Token> tokens, String callId) {
        int limitIndex = -1;
        for (int index = 0; index < tokens.size(); index++) {
            Token token = tokens.get(index);
            if (token.depth() == 0 && "LIMIT".equals(word(token))) {
                if (limitIndex >= 0) {
                    throw violation(callId, "query may contain only one top-level LIMIT");
                }
                limitIndex = index;
            }
        }
        if (limitIndex < 0 || limitIndex + 1 >= tokens.size()) {
            throw violation(callId, "every query requires a literal top-level LIMIT <= 20");
        }
        Token value = tokens.get(limitIndex + 1);
        if (value.kind() != TokenKind.NUMBER || !value.text().matches("\\d+")) {
            throw violation(callId, "query requires a literal top-level LIMIT <= 20");
        }
        int limit;
        try {
            limit = Integer.parseInt(value.text());
        } catch (NumberFormatException exception) {
            throw violation(callId, "query requires a literal top-level LIMIT <= 20");
        }
        if (limit < 1 || limit > maximumRows) {
            throw violation(callId, "every query requires LIMIT <= 20");
        }
        int suffix = limitIndex + 2;
        if (suffix < tokens.size() && "OFFSET".equals(word(tokens.get(suffix)))) {
            suffix++;
            if (suffix >= tokens.size()
                    || tokens.get(suffix).kind() != TokenKind.NUMBER
                    || !tokens.get(suffix).text().matches("\\d+")) {
                throw violation(callId, "OFFSET must be a literal non-negative integer");
            }
            suffix++;
        }
        if (suffix != tokens.size()) {
            throw violation(callId, "query requires a literal top-level LIMIT <= 20 as the final clause");
        }
    }

    private String word(Token token) {
        return token.kind() == TokenKind.WORD ? token.text() : null;
    }

    private IllegalStateException violation(String callId, String message) {
        return new IllegalStateException("tool call " + callId + " rejected: " + message);
    }

    private record Token(String text, TokenKind kind, int depth) {
    }

    private enum TokenKind {
        WORD,
        QUOTED_IDENTIFIER,
        STRING,
        NUMBER,
        SYMBOL
    }
}
