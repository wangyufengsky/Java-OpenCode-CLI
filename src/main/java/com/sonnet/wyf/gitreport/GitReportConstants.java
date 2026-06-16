package com.sonnet.wyf.gitreport;

import java.util.List;

final class GitReportConstants {
    static final String REPORT_MARKER = "<!-- CODE_CONTRIBUTION_REPORT_CONTENT -->";
    static final String AUTHOR_REPORT_MARKER = "<!-- AUTHOR_CODE_CONTRIBUTION_REPORT_CONTENT -->";
    static final String QUALITY_SUMMARY_MARKER = "\"__QUALITY_SUMMARY_JSON_CONTENT__\"";
    static final List<String> DEFAULT_INCLUDE_PATTERNS = List.of(
            "*.java", "*.kt", "*.kts", "*.scala", "*.groovy", "*.gradle", "*.py", "*.rb", "*.sh", "*.bash",
            "*.zsh", "*.ps1", "*.bat", "*.cmd", "*.js", "*.jsx", "*.ts", "*.tsx", "*.mjs", "*.cjs",
            "*.vue", "*.svelte", "*.html", "*.htm", "*.xhtml", "*.css", "*.scss", "*.sass", "*.less",
            "*.jsp", "*.jspx", "*.xml", "*.yml", "*.yaml", "*.json", "*.toml", "*.ini", "*.conf",
            "*.properties", "*.sql", "*.c", "*.cc", "*.cpp", "*.cxx", "*.h", "*.hpp", "*.cs", "*.go",
            "*.rs", "*.swift", "*.php", "*.lua", "*.r", "*.pl", "*.proto", "*.graphql", "*.gql",
            "Dockerfile", "Dockerfile.*", "Makefile", "makefile", "GNUmakefile", "Jenkinsfile", "Jenkinsfile.*",
            ".gitignore", ".gitattributes", ".dockerignore", ".editorconfig"
    );
    static final List<String> DEFAULT_EXCLUDE_PATTERNS = List.of(
            "*.md", "*.markdown", "*.mdown", "*.mkd", "*.doc", "*.docx", "*.xls", "*.xlsx", "*.xlsm",
            "*.ppt", "*.pptx", "*.pdf", "*.rtf", "*.txt", "*.csv", "*.png", "*.jpg", "*.jpeg", "*.gif",
            "*.bmp", "*.webp", "*.ico", "*.zip", "*.tar", "*.gz", "*.7z", "*.rar"
    );

    private GitReportConstants() {
    }
}
