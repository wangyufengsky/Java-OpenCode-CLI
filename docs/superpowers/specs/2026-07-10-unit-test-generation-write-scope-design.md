# Unit-test generation write scope

## Goal

Permit a unit-test-generation batch to repair its module build descriptor and existing tests when those changes are required for the generated tests to compile or run.

## Write contract

For each batch, the allowed write set is limited to the module that owns the batch target:

- that module's `pom.xml`;
- that module's `src/test/**` tree, including existing tests;
- existing workflow output and local runtime exclusions (`out`, `.git`, `.agentbridge`, `.idea`, and build artifacts).

For a root-module batch, the module `pom.xml` is `pom.xml`; for a Maven submodule batch, it is `<module>/pom.xml`.

Production sources, configuration files, POM files in other modules, and test files in other modules remain protected.

## Enforcement and user-facing guidance

Batch preparation will publish the module POM alongside the existing test-write glob in `allowed_write_globs`. The worker prompt will state that this scoped POM and the current module's tests may be changed only when necessary to make the target tests work. `ProtectedSnapshot` will enforce the same paths, so prompt text and Java-side protection cannot diverge.

## Verification

Tests will prove that a worker can alter the current module POM and an existing test source without rejection, and that a POM outside the current module is still rejected. Existing production-file and cross-module-test rejection tests remain in place.
