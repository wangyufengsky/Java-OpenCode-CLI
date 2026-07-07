# project-unit-test-generation 单元测试批次

你正在执行 Java 编排的单元测试生成任务。只处理路径载荷中的一个 `batch_input_json`。一个 task 只包含一个 Java 顶层类型，一个 agent 只写或修复这个类的单元测试；当前 task 完成前不会启动下一个 agent。

## 输入

- 路径载荷会提供 `batch_input_json:`，先读取该 JSON。
- 以 `batch_input_json` 为本批次唯一任务边界，重点使用 `source_files`、`types`、`existing_test_files`、`target_test_files`、`docs`、`coverage`、`allowed_write_globs`。
- 文档缺失时不要扩大任务范围；按源码、已有测试和当前批次信息继续。
- 下面可能包含上一轮 Java 验收失败摘要；如果存在，优先针对失败项修复。

## 任务

- 为当前 `batch_input_json` 的一个顶层类型创建或修复单元测试。
- 只允许修改 `target_test_files` 或 `allowed_write_globs` 内的测试文件。
- 不要修改生产代码、构建脚本、配置文件或当前批次以外的测试文件。
- 只需修改当前批次允许范围内的测试文件；Java 会在本轮结束后验收测试存在性、编译、运行和覆盖率。
- 最终只回复简短完成信息。


## 路径载荷

```text
repo: /Users/wangyufeng/IdeaProjects/Java-OpenCode-CLI
batch_input_json: /Users/wangyufeng/IdeaProjects/Java-OpenCode-CLI/project-unit-tests/agentbridge-live-opencode-model-mapper/test-batches/test-batch-001-opencodemodelmapper/input.json
```

## 上一轮 Java 验收失败摘要

目标测试类运行失败: com.sonnet.wyf.gitreport.opencode.OpenCodeModelMapperTest Command failed (exit code 1)

sh -c "cd '/Users/wangyufeng/IdeaProjects/Java-OpenCode-CLI' && mvn -q org.apache.maven.plugins:maven-dependency-plugin:3.8.1:get -Dartifact='org.jacoco:org.jacoco.agent:0.8.15:jar:runtime' && mvn -q -Dtest='OpenCodeModelMapperTest' '-Dsqlite.native.access.argument=--enable-native-access=ALL-UNNAMED -javaagent:/Users/wangyufeng/.m2/repository/org/jacoco/org.jacoco.agent/0.8.15/org.jacoco.agent-0.8.15-runtime.jar=destfile=/Users/wangyufeng/IdeaProjects/Java-OpenCode-CLI/target/jacoco.exec' test 'org.jacoco:jacoco-maven-plugin:0.8.15:report'"
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.5.5:test (default-test) on project Java-OpenCode-CLI: No tests matching pattern "OpenCodeModelMapperTest" were executed! (Set -Dsurefire.failIfNoSpecifiedTests=false to ignore this error.) -> [Help 1]
[ERROR] 
[ERROR] To see the full stack trace of the errors, re-run Maven with the -e switch.
[ERROR] Re-run Maven using the -X switch to enable full debug logging.
[ERROR] 
[ERROR] For more information about the errors and possible solutions, please read the following articles:
[ERROR] [Help 1] http://cwiki.apache.org/confluence/display/MAVEN/MojoFailureException
