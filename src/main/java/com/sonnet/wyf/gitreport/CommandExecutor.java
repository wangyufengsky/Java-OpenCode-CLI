package com.sonnet.wyf.gitreport;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

class CommandExecutor {
    String run(Path cwd, String... command) throws IOException, InterruptedException {
        return run(cwd, Arrays.asList(command));
    }

    String run(Path cwd, List<String> command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command)
                .directory(cwd.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes());
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException(String.join(" ", command) + " failed with " + exitCode + "\n" + output);
        }
        return output;
    }
}
