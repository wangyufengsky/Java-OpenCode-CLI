package com.sonnet.wyf.gitreport.preparation;

import java.util.Locale;
import java.util.Set;

public class CommentLineCounter {
    private static final Set<String> BLOCK_COMMENT_EXTS = Set.of(".java", ".kt", ".kts", ".scala", ".groovy", ".js", ".jsx", ".ts", ".tsx", ".c", ".cc", ".cpp", ".cxx", ".h", ".hpp", ".cs", ".go", ".rs", ".swift", ".php", ".css", ".scss", ".sass", ".less", ".proto", ".sql");
    private static final Set<String> HASH_COMMENT_EXTS = Set.of(".py", ".rb", ".sh", ".bash", ".zsh", ".ps1", ".yaml", ".yml", ".toml", ".ini", ".conf", ".r", ".pl", ".graphql", ".gql");
    private static final Set<String> XML_COMMENT_EXTS = Set.of(".xml", ".html", ".htm", ".xhtml", ".vue", ".jsp", ".jspx");
    private static final Set<String> PROPERTIES_EXTS = Set.of(".properties");

    public boolean isCountableCodeLine(String path, String line, CommentState state) {
        String suffix = suffix(path);
        String text = line.replaceAll("[\\r\\n]+$", "");
        if (text.trim().isEmpty()) {
            return false;
        }
        if (XML_COMMENT_EXTS.contains(suffix)) {
            text = removeBlockComments(text, state, "<!--", "-->");
        } else if (BLOCK_COMMENT_EXTS.contains(suffix)) {
            text = removeBlockComments(text, state, "/*", "*/");
            text = removeInlineComment(text, suffix.equals(".sql") ? "--" : "//");
        } else if (HASH_COMMENT_EXTS.contains(suffix)) {
            text = removeInlineComment(text, "#");
        } else if (PROPERTIES_EXTS.contains(suffix)) {
            String stripped = text.stripLeading();
            if (stripped.startsWith("#") || stripped.startsWith("!")) {
                return false;
            }
        }
        return !text.trim().isEmpty();
    }

    private String removeBlockComments(String line, CommentState state, String start, String end) {
        StringBuilder result = new StringBuilder();
        int index = 0;
        while (index < line.length()) {
            if (state.inBlock) {
                int closeAt = line.indexOf(state.blockEnd, index);
                if (closeAt == -1) {
                    return result.toString();
                }
                state.inBlock = false;
                index = closeAt + state.blockEnd.length();
                continue;
            }
            int openAt = line.indexOf(start, index);
            if (openAt == -1) {
                result.append(line.substring(index));
                break;
            }
            result.append(line, index, openAt);
            int closeAt = line.indexOf(end, openAt + start.length());
            if (closeAt == -1) {
                state.inBlock = true;
                state.blockEnd = end;
                break;
            }
            index = closeAt + end.length();
        }
        return result.toString();
    }

    private String removeInlineComment(String line, String marker) {
        String scan = stripStringLiterals(line);
        int at = scan.indexOf(marker);
        return at == -1 ? line : line.substring(0, at);
    }

    private String stripStringLiterals(String line) {
        return line.replaceAll("(['\"])(?:\\\\.|(?!\\1).)*\\1", "\"\"");
    }

    private String suffix(String path) {
        String name = path.toLowerCase(Locale.ROOT);
        int at = name.lastIndexOf('.');
        return at == -1 ? "" : name.substring(at);
    }

    public static class CommentState {
        private boolean inBlock;
        private String blockEnd = "*/";
    }
}
