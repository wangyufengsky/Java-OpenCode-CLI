package com.sonnet.wyf.gitreport;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FileScopeAndCommentCounterTest {
    @Test
    void countsDevelopmentFilesAndExcludesDocuments() {
        FileScopeFilter filter = FileScopeFilter.withUserPatterns(
                java.util.List.of(),
                java.util.List.of("target/**")
        );

        assertThat(filter.isCounted("src/main/java/Demo.java")).isTrue();
        assertThat(filter.isCounted("src/main/resources/schema.sql")).isTrue();
        assertThat(filter.isCounted("README.md")).isFalse();
        assertThat(filter.isCounted("docs/spec.docx")).isFalse();
        assertThat(filter.isCounted("target/generated/Demo.java")).isFalse();
    }

    @Test
    void filtersObviousCommentOnlyLines() {
        CommentLineCounter counter = new CommentLineCounter();

        assertThat(counter.isCountableCodeLine("src/Demo.java", " // comment", new CommentLineCounter.CommentState())).isFalse();
        assertThat(counter.isCountableCodeLine("src/Demo.java", "int total = 1; // comment", new CommentLineCounter.CommentState())).isTrue();
        assertThat(counter.isCountableCodeLine("script.py", "# comment", new CommentLineCounter.CommentState())).isFalse();
        assertThat(counter.isCountableCodeLine("config.yaml", "enabled: true # comment", new CommentLineCounter.CommentState())).isTrue();
        assertThat(counter.isCountableCodeLine("schema.sql", "-- comment", new CommentLineCounter.CommentState())).isFalse();
    }
}
