package com.sonnet.wyf.gitreport.console;

import java.nio.file.Path;

/** Prevents the visual-QA profile from opening or clearing a user database. */
public final class VisualQaDatabaseGuard {
    private static final Path EXPECTED_PATH = Path.of("target", "visual-qa.sqlite").toAbsolutePath().normalize();

    private VisualQaDatabaseGuard() {
    }

    public static void requireDisposablePath(Path configuredPath) {
        if (configuredPath == null || !configuredPath.toAbsolutePath().normalize().equals(EXPECTED_PATH)) {
            throw new IllegalStateException("visual-qa database path must be target/visual-qa.sqlite");
        }
    }
}
