# MyBatis SQL Review Path Scope Design

## Goal

The MyBatis SQL review workflow must review only Mapper XML files discovered below explicitly configured directories. It must not scan, snapshot, back up, or change permissions across the whole `project.repo`.

## Configuration contract

The workflow adds a required `source.paths` list:

```yaml
project:
  repo: "/home/user/project/upfs-nl-json"

source:
  paths:
    - "upfs-cup/src/main/resources/mapper"
    - "upfs-core/src/main/resources/mappers"
  include:
    - "**/*.xml"
  exclude: []
```

Each entry is a directory relative to `project.repo`. The list must contain at least one non-blank entry. Absolute paths, paths that normalize outside `project.repo`, missing paths, non-directory paths, and symbolic-link path segments are rejected before database preflight or task submission.

Normalized duplicate directories are removed. If one configured directory contains another, discovery scans only the containing directory while preserving both normalized values in the source contract so configuration drift remains detectable.

`source.include` and `source.exclude` remain supported. Their globs are evaluated relative to each configured source directory. The default include is `**/*.xml`. Discovery first applies the path boundary and globs, then parses XML candidates and retains only files whose root element is an unprefixed, non-namespaced MyBatis `<mapper>`.

An empty `source.paths` list never means full-project discovery. It is a configuration error.

## Inventory and identity

Inventory entries continue to store Mapper paths relative to `project.repo`, not relative to an individual source directory. Existing mapper keys, statement keys, report locations, traceability links, database evidence, and targeted rerun identifiers therefore remain stable when a directory is introduced around existing Mapper files.

Overlapping configured directories must not produce duplicate Mapper or SQL statement entries. Discovery order remains deterministic by repository-relative logical path, namespace, statement ID, and `selectKey` ordinal.

The published source contract records normalized `source.paths`, `source.include`, and `source.exclude`. Targeted `sql`, `xml`, and `index` reruns reject source-path configuration drift. Mapper additions, deletions, content changes, and path changes below the configured directories continue to invalidate stale published inventory.

## Filesystem protection boundary

Run-level filesystem protection receives the configured source directories and the exact discovered Mapper files. It no longer constructs a protected tree rooted at `project.repo` and no longer traverses repository-wide `.git`, `.gradle`, `target`, `build`, dependency, or unrelated source trees.

The guard protects:

- every discovered Mapper file and its identity/content snapshot;
- the configured source-directory boundaries needed to detect Mapper creation, deletion, replacement, or rename;
- stable published SQL-review artifacts, excluding only the current run;
- the current run, with only the active candidate directory writable during an Agent task.

The guard does not read or back up unrelated files merely because they share the repository. A repository containing large Git pack files or build artifacts outside the configured source directories must not consume the SQL-review snapshot byte budget.

Before and after Agent execution and Java rendering, the workflow rebuilds the inventory only from the configured directories and requires it to match the protected inventory. A changed Mapper set or changed Mapper content fails the run and prevents publication.

## Database and Agent behavior

Database MCP continues to receive the normalized absolute `project.repo` as its `project` argument because that is the existing Database MCP project/scope identity. Path-scoped Mapper discovery does not change datasource, catalog, schema, safety-mode, query, audit, or evidence contracts.

Each Agent task continues to receive one SQL statement and may write only its three candidate files. The prompt identifies the repository-relative Mapper file but does not expose unrelated project files as review inputs.

## Failure behavior

Configuration and discovery fail closed before task submission when:

- `source.paths` is absent, empty, blank, absolute, escaping, missing, not a directory, or contains a symbolic-link segment;
- no MyBatis Mapper XML is discovered below the configured directories;
- a discovered Mapper is unreadable, symbolic-linked, malformed, or violates the existing XML safety contract;
- distinct files still produce a duplicate logical Mapper identity after overlapping-directory deduplication;
- a rerun source contract differs from the published contract;
- the protected Mapper set changes during the run.

Failure messages name the offending configured path or Mapper. Filesystem-protection setup failures expose the deepest useful cause in the console instead of retaining only the generic outer wrapper.

## Compatibility and documentation

This is an intentional configuration migration. Existing MyBatis SQL-review configurations without `source.paths` fail validation with an actionable message instead of silently scanning the whole repository.

The default chain YAML, README example and field table, console configuration defaults, source-contract serialization, prompt-pack contract tests, and rerun validation are updated together.

## Testing

Implementation follows red-green-refactor cycles. Tests cover:

- one and multiple repository-relative source directories;
- recursive discovery and repository-relative inventory paths;
- overlapping-directory deduplication;
- exclusion of Mapper files outside configured directories;
- include/exclude globs relative to each source directory;
- rejection of empty, blank, absolute, escaping, missing, non-directory, and symbolic-link paths;
- stable Mapper and statement identities;
- source-contract serialization and rerun drift rejection;
- filesystem protection limited to configured Mapper scope;
- a repository with a file larger than 64 MiB under `.git` or `target` still establishing protection;
- Mapper add, delete, replace, rename, and content-change detection;
- unchanged Database MCP project binding and existing audit rules;
- default YAML, README, console, prompt, report, and schema contracts.

Final verification runs focused inventory, configuration, filesystem-guard, workflow, prompt-contract, and console tests, followed by the complete Maven suite and `git diff --check`.
