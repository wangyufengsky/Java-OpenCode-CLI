package com.sonnet.wyf.gitreport.workflow.mybatissqlreview;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class ReadOnlySqlPolicy {
    private static final Set<String> STRUCTURAL_WORDS = Set.of(
            "SELECT", "FROM", "AS", "LIMIT", "OFFSET", "WHERE", "WITH", "JOIN", "ON",
            "GROUP", "HAVING", "ORDER", "BY", "UNION", "INTERSECT", "EXCEPT", "INTO",
            "FOR", "UPDATE", "SHARE", "INSERT", "DELETE", "MERGE", "UPSERT", "CREATE",
            "ALTER", "DROP", "TRUNCATE", "COPY", "CALL", "VALUES", "RETURNING"
    );

    private final int maximumRows;

    ReadOnlySqlPolicy(int maximumRows) {
        if (maximumRows < 1) {
            throw new IllegalArgumentException("maximumRows must be positive");
        }
        this.maximumRows = maximumRows;
    }

    void validate(String sql, String callId) {
        if (sql == null || sql.isBlank()) {
            throw violation(callId, "queryText is required");
        }
        List<Token> tokens;
        try {
            tokens = tokenize(sql);
        } catch (IllegalArgumentException exception) {
            throw violation(callId, exception.getMessage());
        }
        Parser parser = new Parser(tokens, callId);
        parser.parse();
    }

    private List<Token> tokenize(String sql) {
        List<Token> tokens = new ArrayList<>();
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
                tokens.add(new Token(sql.substring(start, index), TokenKind.STRING));
                continue;
            }
            if (current == '"' || current == '`' || current == '[' || current == ']') {
                throw new IllegalArgumentException("unsupported quoting form");
            }
            if (Character.isLetter(current) || current == '_') {
                int start = index++;
                while (index < sql.length()
                        && (Character.isLetterOrDigit(sql.charAt(index)) || sql.charAt(index) == '_')) {
                    index++;
                }
                tokens.add(new Token(sql.substring(start, index).toUpperCase(Locale.ROOT), TokenKind.WORD));
                continue;
            }
            if (Character.isDigit(current)) {
                int start = index++;
                while (index < sql.length() && Character.isDigit(sql.charAt(index))) {
                    index++;
                }
                if (index < sql.length()
                        && (sql.charAt(index) == '.' || Character.isLetter(sql.charAt(index)))) {
                    while (index < sql.length()
                            && (Character.isLetterOrDigit(sql.charAt(index)) || sql.charAt(index) == '.')) {
                        index++;
                    }
                }
                tokens.add(new Token(sql.substring(start, index), TokenKind.NUMBER));
                continue;
            }
            if (current == ';') {
                throw new IllegalArgumentException("multiple statements and semicolons are forbidden");
            }
            tokens.add(new Token(String.valueOf(current), TokenKind.SYMBOL));
            index++;
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

    private IllegalStateException violation(String callId, String message) {
        return new IllegalStateException("tool call " + callId + " rejected: " + message);
    }

    private final class Parser {
        private final List<Token> tokens;
        private final String callId;
        private int index;

        private Parser(List<Token> tokens, String callId) {
            this.tokens = tokens;
            this.callId = callId;
        }

        private void parse() {
            requireWord("SELECT", "only SELECT using the simple-read grammar is allowed");
            parseProjection();
            requireWord("FROM", "simple-read grammar requires FROM after plain columns");
            parseRelation();
            if (acceptWord("AS")) {
                requireIdentifier("simple-read grammar requires a plain table alias after AS");
            }
            if (peekWord("WHERE")) {
                reject("WHERE and expression clauses are forbidden by the simple-read grammar");
            }
            requireWord("LIMIT", "a literal top-level LIMIT is required by the simple-read grammar");
            int limit = requireInteger("a literal top-level LIMIT is required by the simple-read grammar");
            if (limit < 1 || limit > maximumRows) {
                reject("simple-read grammar requires LIMIT <= 20");
            }
            if (acceptWord("OFFSET")) {
                requireInteger("simple-read grammar requires a literal non-negative OFFSET");
            }
            if (index != tokens.size()) {
                reject(classifyExpression(tokens.get(index)));
            }
        }

        private void parseProjection() {
            parseColumnReference(true);
            while (acceptSymbol(",")) {
                parseColumnReference(true);
            }
        }

        private void parseRelation() {
            requireIdentifier("simple-read grammar requires one plain table name");
            if (acceptSymbol(".")) {
                requireIdentifier("simple-read grammar requires a plain schema-qualified table name");
            }
        }

        private void parseColumnReference(boolean allowStar) {
            if (allowStar && acceptSymbol("*")) {
                return;
            }
            Token first = peek();
            if (!isIdentifier(first)) {
                reject(classifyExpression(first));
            }
            index++;
            if (peekSymbol("(")) {
                String name = first.text();
                reject("function or CAST expression is forbidden by the simple-read grammar: " + name);
            }
            if (acceptSymbol(".")) {
                if (allowStar && acceptSymbol("*")) {
                    return;
                }
                requireIdentifier("simple-read grammar requires a plain qualified column name");
            }
            if (index < tokens.size() && !peekSymbol(",") && !peekWord("FROM")) {
                reject(classifyExpression(tokens.get(index)));
            }
        }

        private int requireInteger(String message) {
            Token token = peek();
            if (token == null || token.kind() != TokenKind.NUMBER || !token.text().matches("\\d+")) {
                reject(message);
            }
            index++;
            try {
                return Integer.parseInt(token.text());
            } catch (NumberFormatException exception) {
                reject(message);
                return -1;
            }
        }

        private void requireIdentifier(String message) {
            if (!isIdentifier(peek())) {
                reject(message);
            }
            index++;
        }

        private boolean isIdentifier(Token token) {
            return token != null && token.kind() == TokenKind.WORD
                    && !STRUCTURAL_WORDS.contains(token.text());
        }

        private void requireWord(String expected, String message) {
            if (!acceptWord(expected)) {
                reject(message);
            }
        }

        private boolean acceptWord(String expected) {
            if (peekWord(expected)) {
                index++;
                return true;
            }
            return false;
        }

        private boolean peekWord(String expected) {
            Token token = peek();
            return token != null && token.kind() == TokenKind.WORD && expected.equals(token.text());
        }

        private boolean acceptSymbol(String expected) {
            if (peekSymbol(expected)) {
                index++;
                return true;
            }
            return false;
        }

        private boolean peekSymbol(String expected) {
            Token token = peek();
            return token != null && token.kind() == TokenKind.SYMBOL && expected.equals(token.text());
        }

        private Token peek() {
            return index < tokens.size() ? tokens.get(index) : null;
        }

        private String classifyExpression(Token token) {
            if (token == null) {
                return "incomplete simple-read grammar";
            }
            if (token.kind() == TokenKind.STRING || token.kind() == TokenKind.NUMBER) {
                return "operator or literal expression is forbidden by the simple-read grammar";
            }
            if (token.kind() == TokenKind.SYMBOL) {
                if (":".equals(token.text())) {
                    return "cast syntax is forbidden by the simple-read grammar";
                }
                if ("(".equals(token.text())) {
                    return "function expression is forbidden by the simple-read grammar";
                }
                return "operator expression is forbidden by the simple-read grammar";
            }
            if ("WHERE".equals(token.text())) {
                return "WHERE and expression clauses are forbidden by the simple-read grammar";
            }
            return "clause is forbidden by the simple-read grammar: " + token.text();
        }

        private void reject(String message) {
            throw violation(callId, message);
        }
    }

    private record Token(String text, TokenKind kind) {
    }

    private enum TokenKind {
        WORD,
        STRING,
        NUMBER,
        SYMBOL
    }
}
