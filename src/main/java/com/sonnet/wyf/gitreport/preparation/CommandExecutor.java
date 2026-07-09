package com.sonnet.wyf.gitreport.preparation;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CommandExecutor {
    private static final Logger log = LoggerFactory.getLogger(CommandExecutor.class);
    private static final long SLOW_COMMAND_MILLIS = 5_000;

    public CommandExecutor() {
    }

    String run(Path cwd, String... command) throws IOException, InterruptedException {
        return run(cwd, Arrays.asList(command));
    }

    String run(Path cwd, List<String> command) throws IOException, InterruptedException {
        long startedNanos = System.nanoTime();
        Process process = new ProcessBuilder(command)
                .directory(cwd.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes());
        int exitCode = process.waitFor();
        long elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000;
        logCommand(cwd, command, elapsedMillis, exitCode);
        if (exitCode != 0) {
            throw new IllegalStateException(String.join(" ", command) + " failed with " + exitCode + "\n" + output);
        }
        return output;
    }

    private void logCommand(Path cwd, List<String> command, long elapsedMillis, int exitCode) {
        if (elapsedMillis >= SLOW_COMMAND_MILLIS || exitCode != 0) {
            log.warn("Git preparation command completed: cwd={}, command=\"{}\", exitCode={}, elapsedMillis={}",
                    cwd,
                    String.join(" ", command),
                    exitCode,
                    elapsedMillis);
        } else if (log.isDebugEnabled()) {
            log.debug("Git preparation command completed: cwd={}, command=\"{}\", exitCode={}, elapsedMillis={}",
                    cwd,
                    String.join(" ", command),
                    exitCode,
                    elapsedMillis);
        }
    }
}
