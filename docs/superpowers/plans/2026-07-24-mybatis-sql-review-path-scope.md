# MyBatis SQL Review Path Scope Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `source.paths` a required list of repository-relative directories and limit MyBatis Mapper discovery and run-level filesystem protection to those directories and discovered Mapper files.

**Architecture:** Add a focused `MyBatisSqlSourceScope` value object that validates and normalizes configured directories below `project.repo`. Feed that scope into inventory discovery, source-contract validation, and a narrowed filesystem guard; keep repository-relative Mapper identities and the existing Database MCP `project.repo` binding unchanged.

**Tech Stack:** Java 25, Spring Boot 4, Jackson YAML/JSON binding, Java NIO, JUnit 5, AssertJ, MockMvc, Maven.

## Global Constraints

- `source.paths` is required, non-empty, and contains only existing directories relative to `project.repo`.
- Reject absolute, escaping, missing, non-directory, blank, and symbolic-link paths before database preflight or Agent task submission.
- Evaluate `source.include` and `source.exclude` relative to each configured source directory.
- Store Mapper paths relative to `project.repo`; do not change Mapper keys, statement keys, report paths, or targeted rerun IDs.
- Do not traverse, snapshot, back up, or chmod the whole repository, `.git`, `.gradle`, `target`, `build`, dependencies, or unrelated sources.
- Keep Database MCP `project` bound to normalized absolute `project.repo`.
- Preserve atomic publication and fail closed on Mapper-set or Mapper-content drift.
- Use test-first red-green-refactor cycles and preserve unrelated working-tree state.

---

### Task 1: Validate Source Directories and Scope Inventory Discovery

**Files:**
- Create: `src/main/java/com/sonnet/wyf/gitreport/workflow/mybatissqlreview/MyBatisSqlSourceScope.java`
- Modify: `src/main/java/com/sonnet/wyf/gitreport/workflow/mybatissqlreview/MyBatisSqlInventoryBuilder.java`
- Test: `src/test/java/com/sonnet/wyf/gitreport/workflow/mybatissqlreview/MyBatisSqlInventoryBuilderTest.java`

**Interfaces:**
- Produces: `MyBatisSqlSourceScope.resolve(Path repository, List<String> configuredPaths)`.
- Produces: `Path repository()`, `List<String> configuredPaths()`, and `List<Path> discoveryRoots()`.
- Changes: `MyBatisSqlInventoryBuilder.build(Path repository, List<String> sourcePaths, List<String> includes, List<String> excludes)`.
- Consumes: repository-relative path strings from YAML-bound `source.paths`.

- [ ] **Step 1: Write failing path-scoped discovery tests**

Add tests that create `module-a/src/main/resources/mapper/A.xml`, `module-b/src/main/resources/mapper/B.xml`, and `outside/Outside.xml`, then call:

```java
MyBatisSqlInventory inventory = builder.build(
        repository,
        List.of(
                "module-a/src/main/resources/mapper",
                "module-b/src/main/resources/mapper"
        ),
        List.of("**/*.xml"),
        List.of()
);

assertThat(inventory.mappers())
        .extracting(MyBatisMapperInventory::mapperRelativePath)
        .containsExactly(
                "module-a/src/main/resources/mapper/A.xml",
                "module-b/src/main/resources/mapper/B.xml"
        );
```

Add a second test proving include/exclude globs are relative to each configured directory and a third test proving overlapping directories do not duplicate a Mapper.

- [ ] **Step 2: Run the inventory tests and verify RED**

Run:

```bash
mvn -q -Dtest=MyBatisSqlInventoryBuilderTest test
```

Expected: compilation failure because the four-argument `build` method and `MyBatisSqlSourceScope` do not exist.

- [ ] **Step 3: Write failing source-path validation tests**

Add parameterized assertions for:

```java
List.of()
List.of(" ")
List.of("/absolute/mapper")
List.of("../outside")
List.of("missing")
List.of("Mapper.xml")
```

Each must throw `IllegalArgumentException` with `source.paths` and the offending value where available. Add a POSIX-capable symbolic-link directory test using `Files.createSymbolicLink`.

- [ ] **Step 4: Implement `MyBatisSqlSourceScope`**

Implement a package-private immutable class with this API:

```java
final class MyBatisSqlSourceScope {
    static MyBatisSqlSourceScope resolve(Path repository, List<String> configuredPaths);

    Path repository();
    List<String> configuredPaths();
    List<Path> discoveryRoots();
}
```

Resolution must:

```java
Path root = repository.toAbsolutePath().normalize();
Path configured = Path.of(value);
if (configured.isAbsolute()) {
    throw new IllegalArgumentException("source.paths must be relative to project.repo: " + value);
}
Path resolved = root.resolve(configured).normalize();
if (!resolved.startsWith(root)) {
    throw new IllegalArgumentException("source.paths escapes project.repo: " + value);
}
```

Walk each path segment from `root` to `resolved` with `LinkOption.NOFOLLOW_LINKS`, reject symlinks, require the final path to be a readable directory, normalize stored strings to `/`, remove exact duplicates, and remove nested discovery roots only from `discoveryRoots()`. Keep every distinct normalized configured value in `configuredPaths()`.

- [ ] **Step 5: Implement scoped inventory discovery**

Change the builder to resolve the scope once, compile include/exclude matchers once, and walk only `scope.discoveryRoots()`. For every candidate:

```java
Path sourceRelative = discoveryRoot.relativize(path);
Path repositoryRelative = scope.repository().relativize(path);
```

Apply globs to `sourceRelative`; sort and parse using `repositoryRelative`; collect paths into a `LinkedHashSet<Path>` before parsing so overlapping directories cannot duplicate entries.

- [ ] **Step 6: Run inventory tests and verify GREEN**

Run:

```bash
mvn -q -Dtest=MyBatisSqlInventoryBuilderTest test
```

Expected: all inventory tests pass with zero failures and errors.

- [ ] **Step 7: Commit Task 1**

```bash
git add \
  src/main/java/com/sonnet/wyf/gitreport/workflow/mybatissqlreview/MyBatisSqlSourceScope.java \
  src/main/java/com/sonnet/wyf/gitreport/workflow/mybatissqlreview/MyBatisSqlInventoryBuilder.java \
  src/test/java/com/sonnet/wyf/gitreport/workflow/mybatissqlreview/MyBatisSqlInventoryBuilderTest.java
git commit -m "feat: scope MyBatis mapper discovery by path"
```

---

### Task 2: Bind `source.paths` to Workflow Configuration and Rerun Contracts

**Files:**
- Modify: `src/main/java/com/sonnet/wyf/gitreport/workflow/mybatissqlreview/MyBatisSqlReviewWorkflowChain.java`
- Modify: `src/test/java/com/sonnet/wyf/gitreport/workflow/mybatissqlreview/MyBatisSqlReviewWorkflowChainTest.java`

**Interfaces:**
- Consumes: `MyBatisSqlSourceScope.resolve(...)` and the new four-argument inventory builder.
- Produces: `Source.getPaths()` / `Source.setPaths(List<String>)`.
- Changes source contract fields from `schema_version=v1` without paths to `schema_version=mybatis-sql-review-source-contract/v2` with `paths`.

- [ ] **Step 1: Write failing configuration and source-contract tests**

Add tests proving:

```yaml
source:
  paths:
    - "src/main/resources/mappers"
  include:
    - "**/*.xml"
  exclude: []
```

loads successfully, while missing or empty `source.paths` fails before the fake database preflight is called. Extend the full-run source-contract assertion to require:

```json
{
  "schema_version": "mybatis-sql-review-source-contract/v2",
  "paths": ["src/main/resources/mappers"]
}
```

Extend targeted rerun drift coverage by changing only `source.paths` and expecting `source/config changed`.

- [ ] **Step 2: Run workflow tests and verify RED**

Run:

```bash
mvn -q -Dtest=MyBatisSqlReviewWorkflowChainTest test
```

Expected: failures because `Source` does not bind `paths`, inventory still uses the old builder signature, and the contract has no `paths` field.

- [ ] **Step 3: Implement configuration binding and normalization**

Add to `Source`:

```java
private List<String> paths = List.of();

public List<String> getPaths() {
    return paths;
}

public void setPaths(List<String> paths) {
    this.paths = paths == null ? List.of() : List.copyOf(paths);
}
```

Change `Source.validate(Path repository)` to resolve `MyBatisSqlSourceScope`, replace `paths` with `scope.configuredPaths()`, validate include/exclude entries, and call it from `Configuration.validate()` after `project.validate()`.

- [ ] **Step 4: Bind scoped paths through workflow execution**

Change `buildInventory` to:

```java
return inventoryBuilder.build(
        configuration.getProject().getRepo(),
        configuration.getSource().getPaths(),
        configuration.getSource().getInclude(),
        configuration.getSource().getExclude()
);
```

Resolve source directories once before guard creation and pass them to the guard in Task 3. Keep database preflight unchanged:

```java
configuration.getProject().getRepo()
```

- [ ] **Step 5: Upgrade and validate the source contract**

Write `paths` beside `repository_real_path`, `include`, and `exclude`; require the exact v2 field set:

```java
Set.of(
        "schema_version", "repository_real_path", "paths", "include", "exclude",
        "inventory_sha256", "artifact_sha256"
)
```

Compare `configuration.getSource().getPaths()` with `textArray(contract.path("paths"))` whenever current configuration is required. Old v1 contracts must fail with the existing full-rerun instruction.

- [ ] **Step 6: Run workflow tests and verify GREEN**

Run:

```bash
mvn -q -Dtest=MyBatisSqlReviewWorkflowChainTest test
```

Expected: all workflow-chain tests pass with zero failures and errors.

- [ ] **Step 7: Commit Task 2**

```bash
git add \
  src/main/java/com/sonnet/wyf/gitreport/workflow/mybatissqlreview/MyBatisSqlReviewWorkflowChain.java \
  src/test/java/com/sonnet/wyf/gitreport/workflow/mybatissqlreview/MyBatisSqlReviewWorkflowChainTest.java
git commit -m "feat: bind MyBatis review source paths"
```

---

### Task 3: Narrow Run-Level Filesystem Protection

**Files:**
- Modify: `src/main/java/com/sonnet/wyf/gitreport/workflow/mybatissqlreview/MyBatisSqlReviewFilesystemGuard.java`
- Modify: `src/main/java/com/sonnet/wyf/gitreport/workflow/mybatissqlreview/MyBatisSqlReviewWorkflowChain.java`
- Modify: `src/main/java/com/sonnet/wyf/gitreport/workflow/mybatissqlreview/MyBatisSqlReviewTaskRunner.java`
- Test: `src/test/java/com/sonnet/wyf/gitreport/workflow/mybatissqlreview/MyBatisSqlReviewFilesystemGuardTest.java`
- Test: `src/test/java/com/sonnet/wyf/gitreport/workflow/mybatissqlreview/MyBatisSqlReviewWorkflowChainTest.java`

**Interfaces:**
- Changes: `protectRun(ObjectMapper, Path repository, List<Path> sourceDirectories, Path stableRoot, Path currentRun, List<Path> mapperFiles, List<Path> candidates)`.
- Changes the observer overload by adding the same `sourceDirectories` argument.
- Preserves: task candidate protection, Java-write sealing, stable publication protection, and current-run restoration.

- [ ] **Step 1: Write the failing large-unrelated-file boundary test**

Update the guard fixture with `sourceDirectories = List.of(mapperDirectory)`. Create a sparse unrelated file without allocating a 64 MiB byte array:

```java
Path pack = Files.createDirectories(layout.repository().resolve(".git/objects/pack"))
        .resolve("pack-large.pack");
try (var channel = Files.newByteChannel(
        pack,
        StandardOpenOption.CREATE,
        StandardOpenOption.WRITE
)) {
    channel.position(64L * 1024 * 1024);
    channel.write(java.nio.ByteBuffer.wrap(new byte[]{1}));
}
```

Assert `protectRun` succeeds and the observer never reports `.git`, `target`, `pom.xml`, Java sources, or unrelated user files, while it reports both Mapper files and stable artifacts.

- [ ] **Step 2: Write failing scoped-tamper tests**

Replace the old whole-repository tamper expectation with:

- changing a discovered Mapper fails and restores it;
- adding, deleting, replacing, or renaming a Mapper below a configured source directory fails;
- changing `pom.xml` or a Java source outside configured source directories is not read, backed up, chmodded, or restored by this guard;
- candidate sibling writes and stable artifact writes still fail and restore.

- [ ] **Step 3: Run guard tests and verify RED**

Run:

```bash
mvn -q -Dtest=MyBatisSqlReviewFilesystemGuardTest test
```

Expected: the large `.git` file triggers the 64 MiB snapshot limit and old whole-repository observer assertions conflict with the desired scope.

- [ ] **Step 4: Replace repository-wide trees with source-directory trees**

In `RunProtection.establish`, validate every configured source directory below `repository`, reject symlink segments, and build protected trees from:

```java
for (Path sourceDirectory : normalizedSourceDirectories) {
    Set<Path> sourceMappers = normalizedMapperFiles.stream()
            .filter(path -> path.startsWith(sourceDirectory))
            .collect(Collectors.toUnmodifiableSet());
    protectedTrees.add(new TreeScope(
            sourceDirectory,
            Set.of(),
            Set.of(),
            sourceMappers
    ));
}
protectedTrees.add(new TreeScope(
        normalizedStable,
        Set.of(normalizedCurrentRun),
        Set.of(),
        Set.of()
));
```

Remove the `normalizedRepository` tree and `gitCommonTrees(normalizedRepository)` from run protection. Retain exact discovered Mapper files, and verify each Mapper starts with at least one configured source directory.

- [ ] **Step 5: Detect only Mapper additions inside source trees**

Extend `TreeScope` with `Set<Path> includedRegularFiles`. An empty set means all regular files for the stable/current-run trees; a non-empty set means only exact discovered Mapper files:

```java
private boolean includesRegularFile(Path path) {
    return includedRegularFiles.isEmpty() || includedRegularFiles.contains(path);
}
```

In `collectTreePaths`, continue traversing and recording directories, but skip regular files for which `includesRegularFile(normalized)` is false. Source-tree snapshots therefore copy/hash only discovered Mapper files. Do not silently admit a newly created file into the protected snapshot. A new Mapper is detected by the post-task `buildInventory(configuration)` comparison and fails publication.

- [ ] **Step 6: Preserve useful setup causes**

Change the setup wrapper message to include the deepest non-blank cause:

```java
String detail = concise(setupFailure);
new IllegalStateException(
        "failed to establish run-level POSIX filesystem protection: " + detail,
        setupFailure
);
```

Add a test that forces an oversized in-scope Mapper and asserts the outer message contains `protected snapshot file exceeds configured limit` and the path.

- [ ] **Step 7: Update workflow and direct runner call sites**

Pass resolved source directories from `Configuration.Source` through `MyBatisSqlReviewWorkflowChain`. For the public task-runner convenience method, derive the one-file source directory as `mapper.getParent()` so existing direct-run tests retain a narrow boundary.

- [ ] **Step 8: Run focused guard and workflow tests and verify GREEN**

Run:

```bash
mvn -q \
  -Dtest=MyBatisSqlReviewFilesystemGuardTest,MyBatisSqlReviewWorkflowChainTest,MyBatisSqlReviewTaskRunnerTest \
  test
```

Expected: all selected tests pass with zero failures and errors.

- [ ] **Step 9: Commit Task 3**

```bash
git add \
  src/main/java/com/sonnet/wyf/gitreport/workflow/mybatissqlreview/MyBatisSqlReviewFilesystemGuard.java \
  src/main/java/com/sonnet/wyf/gitreport/workflow/mybatissqlreview/MyBatisSqlReviewWorkflowChain.java \
  src/main/java/com/sonnet/wyf/gitreport/workflow/mybatissqlreview/MyBatisSqlReviewTaskRunner.java \
  src/test/java/com/sonnet/wyf/gitreport/workflow/mybatissqlreview/MyBatisSqlReviewFilesystemGuardTest.java \
  src/test/java/com/sonnet/wyf/gitreport/workflow/mybatissqlreview/MyBatisSqlReviewWorkflowChainTest.java
git commit -m "fix: protect only configured MyBatis mapper scope"
```

---

### Task 4: Update Default Configuration, Console, and Documentation

**Files:**
- Modify: `src/main/resources/chains/mybatis-sql-review.yml`
- Modify: `src/main/resources/static/js/console-common.js`
- Modify: `src/main/resources/static/js/run-form.js`
- Modify: `README.md`
- Modify: `src/test/java/com/sonnet/wyf/gitreport/PromptPackContractTest.java`
- Modify: `src/test/java/com/sonnet/wyf/gitreport/console/ConsoleMvcTest.java`

**Interfaces:**
- Produces the user-facing `source.paths` YAML and console list field.
- Preserves existing `source.include` / `source.exclude` editing.

- [ ] **Step 1: Write failing YAML, README, and console contract tests**

Require the default YAML and README to contain:

```yaml
source:
  paths:
    - "src/main/resources/mapper"
  include:
    - "**/*.xml"
```

Require `/api/chains/mybatis-sql-review/defaults` to expose `source.paths[0]`, and require the new-run JavaScript field list and summary scope to include `source.paths`.

- [ ] **Step 2: Run contract tests and verify RED**

Run:

```bash
mvn -q -Dtest=PromptPackContractTest,ConsoleMvcTest test
```

Expected: assertions fail because the YAML, console field definition, summary scope, and README do not contain `source.paths`.

- [ ] **Step 3: Update default YAML and README**

Add a Chinese comment explaining that every entry is relative to `project.repo`, is recursively scanned, and must not be empty. Add the field-table definition:

```markdown
| `source.paths` | 必填；相对 `project.repo` 的 Mapper 目录列表。每个目录递归扫描，空列表不会回退为全项目扫描。 |
```

Document include/exclude as relative to each configured source directory.

- [ ] **Step 4: Update console fields and summary**

Add before `source.include`:

```javascript
field(
  'source.paths',
  'Mapper 审查目录',
  '必填；每行一个相对 project.repo 的目录，只递归审查这些目录下的 Mapper XML。',
  'list',
  'scope',
  true,
  true
)
```

Add `source.paths` before `source.package-paths` and `source.include` in `run-form.js` summary selection.

- [ ] **Step 5: Run contract tests and verify GREEN**

Run:

```bash
mvn -q -Dtest=PromptPackContractTest,ConsoleMvcTest test
```

Expected: all contract and console tests pass with zero failures and errors.

- [ ] **Step 6: Commit Task 4**

```bash
git add \
  src/main/resources/chains/mybatis-sql-review.yml \
  src/main/resources/static/js/console-common.js \
  src/main/resources/static/js/run-form.js \
  README.md \
  src/test/java/com/sonnet/wyf/gitreport/PromptPackContractTest.java \
  src/test/java/com/sonnet/wyf/gitreport/console/ConsoleMvcTest.java
git commit -m "docs: configure path-scoped MyBatis reviews"
```

---

### Task 5: Complete Regression Verification

**Files:**
- Modify only files already named if verification exposes an in-scope defect.

**Interfaces:**
- Verifies all preceding task contracts together.

- [ ] **Step 1: Run all focused MyBatis and console tests**

```bash
mvn -q \
  -Dtest='MyBatisSql*Test,PromptPackContractTest,ConsoleMvcTest' \
  test
```

Expected: zero failures and errors.

- [ ] **Step 2: Run the complete Maven suite**

```bash
mvn -q test
```

Expected: exit code 0 with zero failures and errors.

- [ ] **Step 3: Run static repository checks**

```bash
git diff --check
rg -n \
  'inventoryBuilder\.build\([^,]+,[^,]+,[^,]+\)|source-contract/v1|protectRun\(' \
  src/main src/test
```

Expected: `git diff --check` has no output. Every remaining `protectRun` call uses the scoped signature; no production code writes or validates source-contract v1; no old three-argument inventory build remains.

- [ ] **Step 4: Inspect final diff and working tree**

```bash
git status --short --branch
git diff --stat HEAD~4..HEAD
git log -5 --oneline --decorate
```

Expected: only the approved MyBatis path-scope implementation, tests, console configuration, and documentation are present; no generated runtime outputs or unrelated files are staged or untracked.
