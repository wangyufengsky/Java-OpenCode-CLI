---
name: production-package-compare
description: Use when comparing production package tar files between two git branches. The main agent must prepare tasks, spawn sub-agents for each tar package using beyond-compare-tar-report, then synthesize production-analysis.md.
compatibility: opencode
metadata:
  environment: windows
  depends_on: beyond-compare-tar-report
---

# Production Package Compare

Use this skill when the user asks to compare production packages and provides two git branch names.

This is a main-agent orchestration skill. It requires sub-agents. Do not replace the sub-agent workflow with local script concurrency.

## Critical Execution Rule

Always run commands from the user's project workspace directory.

The skill installation directory is only where the scripts live. Do not `cd` into the skill installation directory to run this workflow.

Correct:

```powershell
cd /d D:\upfs-doc
python C:\Users\<user>\.config\opencode\skills\production-package-compare\scripts\production_package_compare.py --left-branch xc-20260423 --right-branch xc-20260507 --prepare-only
```

Wrong:

```powershell
cd /d C:\Users\<user>\.config\opencode\skills\production-package-compare
python scripts\production_package_compare.py --left-branch xc-20260423 --right-branch xc-20260507 --prepare-only
```

At startup, verify these printed lines:

```text
working directory: <project workspace>
config file: <project workspace>\config\production-package-compare.json
output directory: <project workspace>\workspace\...
```

## Workspace Config

The project workspace should contain:

```text
config\production-package-compare.json
```

Template:

```json
{
  "git_url": "ssh://git@example.com/group/project.git",
  "download_mode": "auto",
  "target_tars": ["ecis.tar", "longwl.tar", "nl.tar", "qrwl.tar"]
}
```

For HTTP(S) GitLab token URLs, prefer:

```json
{
  "git_url": "http://oauth2:<url-encoded-token>@gitlab.example.com/group/project.git",
  "download_mode": "clone",
  "target_tars": ["ecis.tar", "longwl.tar", "nl.tar", "qrwl.tar"]
}
```

Logs and reports redact `http://user:token@...`.

## Main Agent Workflow

1. Run the preparation script from the project workspace:

```powershell
python <path-to-this-skill>\scripts\production_package_compare.py --left-branch <old> --right-branch <new> --prepare-only
```

2. Read the generated:

```text
tasks.json
summary.json
index.md
```

3. Spawn one sub-agent per item in `tasks.json`.

Each sub-agent gets exactly one task object and must use `beyond-compare-tar-report` to process that one tar pair.

4. Wait for all sub-agents to finish.

5. Read:

```text
summary.json
reports\*\diff-index.json
reports\*\analysis.md
```

6. Write the final report:

```text
production-analysis.md
```

Use `templates\production-analysis.md` as the required structure for the final report.

## Sub-Agent Task Contract

Each item in `tasks.json` has this shape:

```json
{
  "package": "nl",
  "tar_name": "nl.tar",
  "left_branch": "xc-20260423",
  "right_branch": "xc-20260507",
  "left_tar": "...\\branches\\xc-20260423\\nl.tar",
  "right_tar": "...\\branches\\xc-20260507\\nl.tar",
  "out": "...\\reports\\nl",
  "analysis": "...\\reports\\nl\\analysis.md",
  "report": "...\\reports\\nl\\index.md",
  "diff_index": "...\\reports\\nl\\diff-index.json"
}
```

The sub-agent must:

- Run `beyond-compare-tar-report` for `left_tar` vs `right_tar`.
- Write only inside `out`.
- Produce `index.md`, `diff-index.json`, raw reports, file reports, and `analysis.md`.
- Avoid writing top-level `production-analysis.md`.

## Final Report Requirements

The main agent's `production-analysis.md` must follow `templates\production-analysis.md`.

Rules for filling the template:

- Preserve every heading from the template.
- Use `无` or `未发现` for sections with no findings; do not delete sections.
- Link each package's `index.md` and `analysis.md`.
- If any package lacks `analysis.md`, mark it in `Missing Or Failed Packages`.
- Base conclusions on `summary.json`, all available `diff-index.json`, and all available package `analysis.md` files.
- The final report must include concrete content changes from child `analysis.md` reports; do not only summarize counts or filenames.

The final report must include:

- Overall production package conclusion.
- Four-package summary table.
- Missing packages or failed sub-agent tasks.
- Cross-package high-risk changes.
- Configuration changes summary.
- Script/startup/deployment changes summary.
- Concrete content changes summary.
- Dependency and binary package changes summary.
- Manual production review checklist.
- Links to each package `index.md` and `analysis.md`.

If any `analysis.md` is missing, mark that package as failed/incomplete in `production-analysis.md`; do not silently ignore it.

## Output

The preparation script creates:

- `summary.json`: branch, output, package status, task count.
- `tasks.json`: executable sub-agent task list.
- `index.md`: basic package task index.
- `branches\<left>\` and `branches\<right>\`: exported branch contents.
- `reports\<package>\`: one directory per package, reserved for the corresponding sub-agent.

The main agent creates:

- `production-analysis.md`

## Templates

- `templates\index.md`: script-generated basic task index.
- `templates\production-analysis.md`: main agent final report structure. The main agent must follow this template when writing `production-analysis.md`.

## Path Rules

All relative paths resolve from the project workspace, not from the skill directory.

If `--out` is omitted, output goes to:

```text
<workspace>\workspace\<left>_vs_<right>_<timestamp>\
```

If `--out` exists, the script writes to `<out>_<timestamp>` unless `--overwrite` is explicitly passed.

## Troubleshooting

- If output appears under `.config\opencode\skills\...`, the command was run from the skill directory. Change to the project workspace and rerun.
- If config is missing, create `<workspace>\config\production-package-compare.json`.
- If HTTP(S) GitLab archive fails, use `"download_mode": "clone"`.
- Clone mode uses `--no-checkout` and then checks out only root-level target tar files. This avoids Windows path-length failures from unrelated long filenames in the repository.
- If Windows reports a locked file, avoid `--overwrite` and use a fresh output directory.
- If `tasks.json` is empty, check `summary.json` for missing tar package records.
