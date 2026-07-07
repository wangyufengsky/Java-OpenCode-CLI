# Java AgentBridge CLI Runner

Spring Boot workflow runner for local AgentBridge-based report and review chains.

Java owns workflow preparation, prompt submission, idle polling, output validation, correction prompts, and report rendering. AgentBridge is the only task execution surface.

## Configuration

```yaml
agentbridge-runner:
  enabled: true
  active-chain: git-code-contribution-report
  mode: full
  config-dir: classpath:chains
  agentbridge:
    web-base-url: "https://127.0.0.1:9642"
    mcp-url: "http://127.0.0.1:8642/mcp"
    concurrency: 1
    max-concurrency: 1
    timeout-minutes: 40
    poll-millis: 1000
    validation-settle-seconds: 30
    validation-max-corrections: 2
```

Chain-specific YAML lives under `src/main/resources/chains`. Chain-local `agentbridge` settings can override shared runtime settings where supported.

## Chains

- `git-code-contribution-report`
- `smartesb-rewrite-code-review`
- `smartesb-code-reader`
- `weekly-engineering-report`
- `project-unit-test-generation`

## Runtime

Each task sends a prompt to AgentBridge `/prompt`, waits for `/info.running=false`, then validates the expected files. If validation fails and correction rounds remain, Java sends a correction prompt and validates again.

Task status is written as `agent-status.json` with `taskId`, `agentbridgeWebBaseUrl`, `state`, `timedOut`, `completedByOutput`, `agentState`, `finishedAt`, and `error` fields where applicable.

## Run

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--agentbridge-runner.enabled=true"
```

Rerun example:

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--agentbridge-runner.enabled=true --agentbridge-runner.mode=rerun --agentbridge-runner.rerun.type=synthesis"
```
