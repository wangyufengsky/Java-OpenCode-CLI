package com.sonnet.wyf.gitreport;

import java.nio.file.Path;

final class GitTestSupport {
    private GitTestSupport() {
    }

    static String run(Path cwd, String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(cwd.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes());
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new AssertionError(String.join(" ", command) + " failed with " + exitCode + "\n" + output);
        }
        return output;
    }
}
