package com.sonnet.wyf.gitreport.opencode;

import com.sonnet.wyf.gitreport.GitReportProperties;
import com.sonnet.wyf.gitreport.core.ScheduledProbeWaiter;
import com.sonnet.wyf.gitreport.runner.OpenCodeSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class OpenCodeServerManager {
    private static final Logger log = LoggerFactory.getLogger(OpenCodeServerManager.class);

    private final OpenCodeServerClient client;
    private final ScheduledProbeWaiter scheduledProbeWaiter;
    private Process ownedProcess;
    private URI ownedServerUrl;

    public OpenCodeServerManager(OpenCodeServerClient client, ScheduledProbeWaiter scheduledProbeWaiter) {
        this.client = client;
        this.scheduledProbeWaiter = scheduledProbeWaiter;
    }

    public synchronized OpenCodeServerHandle ensureReady(GitReportProperties properties, Path out) throws Exception {
        OpenCodeSettings settings = new OpenCodeSettings();
        settings.setServerUrl(properties.getOpencode().getServerUrl());
        settings.setManageServer(properties.getOpencode().isManageServer());
        settings.setServerStartTimeoutSeconds(properties.getOpencode().getServerStartTimeoutSeconds());
        settings.setOpencodeBin(properties.getPaths().getOpencodeBin());
        return ensureReady(settings, out);
    }

    public synchronized OpenCodeServerHandle ensureReady(OpenCodeSettings settings, Path out) throws Exception {
        URI serverUrl = URI.create(settings.getServerUrl());
        if (client.isHealthy(serverUrl)) {
            log.info("Reusing healthy OpenCode Server: {}", serverUrl);
            return new OpenCodeServerHandle(serverUrl, false);
        }
        if (!settings.isManageServer()) {
            throw new IllegalStateException("OpenCode Server is not healthy at " + serverUrl + " and opencode manage-server=false. Start `opencode serve` or enable server management.");
        }
        if (ownedProcess != null && ownedProcess.isAlive() && serverUrl.equals(ownedServerUrl)) {
            waitForHealth(serverUrl, settings.getServerStartTimeoutSeconds());
            return new OpenCodeServerHandle(serverUrl, true);
        }
        startServer(settings, out, serverUrl);
        waitForHealth(serverUrl, settings.getServerStartTimeoutSeconds());
        return new OpenCodeServerHandle(serverUrl, true);
    }

    public synchronized void shutdown() throws InterruptedException {
        if (ownedProcess == null) {
            return;
        }
        if (ownedProcess.isAlive()) {
            log.info("Stopping managed OpenCode Server: {}", ownedServerUrl);
            ownedProcess.destroy();
            if (!ownedProcess.waitFor(5, TimeUnit.SECONDS)) {
                ownedProcess.destroyForcibly();
                ownedProcess.waitFor();
            }
        }
        ownedProcess = null;
        ownedServerUrl = null;
    }

    private void startServer(OpenCodeSettings settings, Path out, URI serverUrl) throws IOException {
        String opencodeBin = settings.getOpencodeBin();
        if (opencodeBin == null || opencodeBin.isBlank()) {
            throw new IllegalArgumentException("opencode-bin is required when manage-server=true");
        }
        int port = serverUrl.getPort();
        if (port <= 0) {
            throw new IllegalArgumentException("git-report.opencode.server-url must include an explicit port when Java manages OpenCode Server: " + serverUrl);
        }
        Path logDir = out.resolve("runs").resolve("opencode-server").toAbsolutePath().normalize();
        Files.createDirectories(logDir);
        List<String> command = List.of(opencodeBin, "serve", "--port", String.valueOf(port));
        log.info("Starting managed OpenCode Server: command={}, logs={}", String.join(" ", command), logDir);
        ownedProcess = new ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logDir.resolve("stdout.log").toFile()))
                .redirectError(ProcessBuilder.Redirect.appendTo(logDir.resolve("stderr.log").toFile()))
                .start();
        ownedServerUrl = serverUrl;
    }

    private void waitForHealth(URI serverUrl, int timeoutSeconds) throws Exception {
        boolean healthy = scheduledProbeWaiter.waitFor(
                () -> {
                    if (client.isHealthy(serverUrl)) {
                        return true;
                    }
                    if (ownedProcess != null && !ownedProcess.isAlive()) {
                        throw new IllegalStateException("OpenCode Server process exited before becoming healthy: " + serverUrl + ", exitCode=" + ownedProcess.exitValue());
                    }
                    return false;
                },
                Boolean::booleanValue,
                () -> false,
                Duration.ofSeconds(Math.max(1, timeoutSeconds)),
                Duration.ofMillis(250)
        );
        if (healthy) {
            log.info("OpenCode Server is healthy: {}", serverUrl);
            return;
        }
        Process process = ownedProcess;
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
            process.waitFor();
            ownedProcess = null;
            ownedServerUrl = null;
        }
        throw new IllegalStateException("OpenCode Server startup timed out after " + timeoutSeconds + " seconds: " + serverUrl);
    }
}
