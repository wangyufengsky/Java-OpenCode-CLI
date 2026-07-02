package com.sonnet.wyf.gitreport.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LogicalPathsTest {
    @Test
    void appendsLogicalPathsWithoutChangingSeparatorStyle() {
        assertThat(LogicalPaths.append("/tmp/out", "reports", "/Alpha"))
                .isEqualTo("/tmp/out/reports/Alpha");
        assertThat(LogicalPaths.append("D:\\out", "reports", "Alpha/Beta"))
                .isEqualTo("D:\\out\\reports\\Alpha\\Beta");
    }

    @Test
    void detectsAbsoluteLogicalPathsAcrossPlatforms() {
        assertThat(LogicalPaths.isAbsolute("/tmp/out")).isTrue();
        assertThat(LogicalPaths.isAbsolute("D:/out")).isTrue();
        assertThat(LogicalPaths.isAbsolute("D:\\out")).isTrue();
        assertThat(LogicalPaths.isAbsolute("relative/out")).isFalse();
    }

    @Test
    void slugifiesWithCallerProvidedFallback() {
        assertThat(LogicalPaths.slug("Alpha Beta/01", "item")).isEqualTo("Alpha-Beta-01");
        assertThat(LogicalPaths.slug("!@#", "transaction")).isEqualTo("transaction");
    }
}
