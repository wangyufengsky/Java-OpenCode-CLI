# Unit-test generation write scope Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let each unit-test-generation batch modify only its own Maven `pom.xml` and `src/test/**` tree, while retaining protection for all other source and module paths.

**Architecture:** Preparation derives a deterministic pair of allowed write entries from the batch target: its module test-tree glob and its module POM path. The prompt explains that POM edits are exceptional and scoped. Snapshot validation recognizes an explicitly allowed POM in addition to the existing test-path behavior; it does not turn arbitrary build files or other modules into allowed paths.

**Tech Stack:** Java 22, Spring Boot, JUnit 5, AssertJ, Maven.

## Global Constraints

- A root batch may write only `pom.xml` and `src/test/**`.
- A module batch may write only `<module>/pom.xml` and `<module>/src/test/**`.
- Production code, non-POM configuration, other-module POMs, and other-module tests remain protected.
- Prompt wording, generated `allowed_write_globs`, and Java snapshot enforcement must describe the same scope.
- Preserve existing user changes outside the files listed below.

---

### Task 1: Publish the module POM in each batch contract

**Files:**
- Modify: `src/main/java/com/sonnet/wyf/gitreport/workflow/unittest/ProjectUnitTestGenerationPreparation.java:419-450`
- Test: `src/test/java/com/sonnet/wyf/gitreport/ProjectUnitTestGenerationPreparationTest.java:61-62,109-113`

**Interfaces:**
- Consumes: `target_test_files`, whose paths are rooted at either `src/test/` or `<module>/src/test/`.
- Produces: `allowed_write_globs` containing `<test-root>/**` followed by the owning module POM path.

- [ ] **Step 1: Write the failing preparation assertions**

Change the root-project assertion to require both entries and change the aggregate-project assertions to require the matching module POM:

```java
assertThat(batch.path("allowed_write_globs")).extracting(JsonNode::asText)
        .containsExactly("pom.xml", "src/test/**");

assertThat(batches.path("batches").get(0).path("allowed_write_globs")).extracting(JsonNode::asText)
        .containsExactly("upfs-common/pom.xml", "upfs-common/src/test/**");
assertThat(batches.path("batches").get(1).path("allowed_write_globs")).extracting(JsonNode::asText)
        .containsExactly("upfs-cup/pom.xml", "upfs-cup/src/test/**");
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=ProjectUnitTestGenerationPreparationTest test`

Expected: FAIL because the generated list currently contains only the `src/test/**` entry.

- [ ] **Step 3: Write minimal implementation**

Replace the body of `allowedWriteGlobs` and add `modulePomFile`:

```java
private List<String> allowedWriteGlobs(List<String> targetTestFiles) {
    return targetTestFiles.stream()
            .flatMap(target -> Stream.of(testRootGlob(target), modulePomFile(target)))
            .distinct()
            .sorted()
            .toList();
}

private String modulePomFile(String targetTestFile) {
    String marker = "src/test/";
    int index = targetTestFile.indexOf(marker);
    return index <= 0 ? "pom.xml" : targetTestFile.substring(0, index) + "pom.xml";
}
```

Add `import java.util.stream.Stream;` with the collection imports.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -Dtest=ProjectUnitTestGenerationPreparationTest test`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/sonnet/wyf/gitreport/workflow/unittest/ProjectUnitTestGenerationPreparation.java src/test/java/com/sonnet/wyf/gitreport/ProjectUnitTestGenerationPreparationTest.java
git commit -m "feat: allow batch module pom edits"
```

### Task 2: Make protected-snapshot validation honor only the declared POM

**Files:**
- Modify: `src/main/java/com/sonnet/wyf/gitreport/workflow/unittest/ProjectUnitTestGenerationPaths.java:54-74`
- Modify: `src/main/java/com/sonnet/wyf/gitreport/workflow/unittest/ProjectUnitTestGenerationBatchRunner.java:517-530`
- Test: `src/test/java/com/sonnet/wyf/gitreport/ProjectUnitTestGenerationWorkflowChainTest.java:156-215,520-565`

**Interfaces:**
- Consumes: an exact module POM path placed in `allowed_write_globs` by Task 1.
- Produces: `ProjectUnitTestGenerationPaths.isAllowedBatchPomWrite(Path, Path, List<String>)`, which returns `true` only for a `pom.xml` whose repo-relative path exactly matches a declared allow entry.

- [ ] **Step 1: Write failing workflow tests**

Add a root test that uses a fake client to append a comment to `repo/pom.xml` before creating the target test and expects an accepted report. Add an aggregate test that runs an `upfs-cup` batch, mutates `upfs-common/pom.xml`, and asserts the exception includes `modified protected file: upfs-common/pom.xml`.

The helper clients should make the intended writes explicitly:

```java
Path pom = properties.getProject().getRepo().resolve("pom.xml");
Files.writeString(pom, Files.readString(pom) + "<!-- test dependency repair -->\n");

Path unrelatedPom = properties.getProject().getRepo().resolve("upfs-common/pom.xml");
Files.writeString(unrelatedPom, Files.readString(unrelatedPom) + "<!-- forbidden -->\n");
```

Ensure `writeModuleSource` creates both `upfs-common/pom.xml` and `upfs-cup/pom.xml`, and choose `properties.getSource().setPackagePaths(List.of("upfs-cup/src/main/java/com/spdb/upfs/cup"));` so the current batch belongs to `upfs-cup`.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=ProjectUnitTestGenerationWorkflowChainTest test`

Expected: the current-module POM test FAILS with `modified protected file: pom.xml`; the cross-module POM assertion cannot pass while POMs are still protected.

- [ ] **Step 3: Write minimal implementation**

Add this method to `ProjectUnitTestGenerationPaths`:

```java
static boolean isAllowedBatchPomWrite(Path repo, Path file, List<String> allowedWriteGlobs) {
    Path repoRoot = repo.toAbsolutePath().normalize();
    Path normalized = file.toAbsolutePath().normalize();
    if (!normalized.startsWith(repoRoot) || !"pom.xml".equals(normalized.getFileName().toString())) {
        return false;
    }
    String relative = normalize(repoRoot.relativize(normalized).toString());
    return allowedWriteGlobs.stream().map(ProjectUnitTestGenerationPaths::normalize).anyMatch(relative::equals);
}
```

Then extend the snapshot predicate without changing its existing test-path protection:

```java
return ProjectUnitTestGenerationPaths.isAllowedBatchTestWrite(repo, normalized, allowedWriteGlobs, targetTestFiles)
        || ProjectUnitTestGenerationPaths.isAllowedBatchPomWrite(repo, normalized, allowedWriteGlobs)
        || ProjectUnitTestGenerationPaths.isBuildArtifact(repo, normalized)
        // retain existing local-state and output exclusions
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -Dtest=ProjectUnitTestGenerationWorkflowChainTest test`

Expected: PASS; the current-module POM edit is accepted and cross-module POM edit remains rejected.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/sonnet/wyf/gitreport/workflow/unittest/ProjectUnitTestGenerationPaths.java src/main/java/com/sonnet/wyf/gitreport/workflow/unittest/ProjectUnitTestGenerationBatchRunner.java src/test/java/com/sonnet/wyf/gitreport/ProjectUnitTestGenerationWorkflowChainTest.java
git commit -m "fix: scope unit test batch pom writes"
```

### Task 3: Align worker guidance with the Java contract

**Files:**
- Modify: `src/main/resources/project-unit-test-generation-prompt-pack/prompts/run-test-batch.md:15-20`
- Test: `src/test/java/com/sonnet/wyf/gitreport/PromptPackContractTest.java:198-207`

**Interfaces:**
- Consumes: `allowed_write_globs` populated in Task 1.
- Produces: a prompt that permits only the owning module POM and test sources, and describes POM modification as necessary-for-test repair.

- [ ] **Step 1: Write a failing prompt-contract assertion**

Require wording that names the limited POM allowance and continued restrictions:

```java
assertThat(worker).contains(
        "允许修改当前模块 `pom.xml`",
        "仅在修复当前批次测试的编译或运行问题确有必要时",
        "不要修改生产代码、配置文件、其他模块的 `pom.xml` 或当前批次以外的测试文件"
);
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=PromptPackContractTest test`

Expected: FAIL because the existing text prohibits all build-file changes.

- [ ] **Step 3: Update the worker prompt**

Replace the two scope bullets with:

```markdown
- 只允许修改 `allowed_write_globs` 中当前模块的测试文件；仅在修复当前批次测试的编译或运行问题确有必要时，才可修改其中明确列出的当前模块 `pom.xml`。
- 不要修改生产代码、配置文件、其他模块的 `pom.xml` 或当前批次以外的测试文件。
```

Keep the segmented-write limits and all Java-side validation wording unchanged.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -Dtest=PromptPackContractTest test`

Expected: PASS.

- [ ] **Step 5: Run focused integration verification and commit**

Run: `mvn -q -Dtest=ProjectUnitTestGenerationPreparationTest,ProjectUnitTestGenerationWorkflowChainTest,PromptPackContractTest test && git diff --check`

Expected: all selected tests pass and no whitespace errors are reported.

```bash
git add src/main/resources/project-unit-test-generation-prompt-pack/prompts/run-test-batch.md src/test/java/com/sonnet/wyf/gitreport/PromptPackContractTest.java
git commit -m "docs: scope unit test batch pom guidance"
```

## Self-review

- Spec coverage: Task 1 publishes per-module scope, Task 2 enforces it and proves negative boundaries, and Task 3 keeps the prompt synchronized.
- Placeholder scan: no incomplete tasks or unspecified paths remain.
- Type consistency: `allowed_write_globs` remains a `List<String>`; Task 2 introduces only `isAllowedBatchPomWrite(Path, Path, List<String>)` and calls that exact signature.
