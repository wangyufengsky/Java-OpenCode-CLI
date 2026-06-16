---
name: beyond-compare-tar-report
description: Use on Windows when one agent must compare one pair of tar archives with Beyond Compare, generate raw/factual reports, produce diff-index.json, and write an agent-authored analysis.md for that single tar package.
compatibility: opencode
metadata:
  environment: windows
  tool: beyond-compare
---

# Beyond Compare Tar Report

Use this skill for exactly one tar pair. In the production package workflow, this skill is executed by a sub-agent that owns one package directory such as `reports\nl\`.

## Role Boundary

The script is a fact collector. The agent is the analyst.

The script must generate:

- `index.md`
- `diff-index.json`
- `files\*.md`
- `raw\folder.xml`
- `raw\folder.html`

The agent must then read those outputs and write:

- `analysis.md`

Do not stop after running the script. A completed use of this skill includes `analysis.md`.

## Command

Run from the project workspace or from the assigned package output context:

```powershell
python <path-to-skill>\scripts\bc_tar_report.py --left <left.tar> --right <right.tar> --out <reports\package> --title "<package> tar diff"
```

Useful options:

- `--bc-path "C:\Program Files\Beyond Compare 5\BCompare.exe"`: explicit Beyond Compare executable.
- `--file-report-mode text|all|none`: default `text`; use `none` for very large binary-heavy packages.
- `--class-detail-mode javap|none`: default `javap`; for changed `.class` files, extract both sides, run `javap -verbose -p -c`, and generate a bytecode diff.
- `--javap-path "C:\Program Files\Java\jdk-xx\bin\javap.exe"`: explicit `javap.exe`. If omitted, the script searches `PATH`.
- `--template-dir <path>`: custom Markdown templates.

`BCOMPARE_PATH` can also point to `BCompare.exe`.

## Analysis Requirements

After the script completes, read `diff-index.json` first. Use `index.md`, `files\*.md`, and raw XML/HTML only when needed for detail.

Write `analysis.md` in the same output directory using `templates\analysis.md` as the required structure.

Rules for filling the template:

- Preserve every heading from the template.
- Use `无` or `未发现` for sections with no findings; do not delete sections.
- Use Markdown tables for rows represented by placeholders.
- Link evidence to `index.md`, `files\*.md`, `raw\folder.html`, or `diff-index.json` where useful.
- Include text diff excerpts only when available and relevant; summarize large diffs instead of pasting long content.
- For every important text/config/script change, include concrete content evidence from `raw_patch` or `diff_excerpt`; do not only say "file changed".
- For `.class` changes, use the generated `raw\*.javap.diff` and `binary_detail` fields from `diff-index.json` as concrete evidence. Do not report class changes as only "binary changed" unless `javap` is unavailable or failed; in that case, cite SHA256/size evidence and the javap log.

The template must cover:

- Overall conclusion for this tar package.
- Counts for added/deleted/modified or equivalent statuses.
- High-risk file list.
- Configuration changes.
- Script changes.
- Dependency/binary package changes such as `.jar`, `.war`, `.class`, `.zip`, `.gz`.
- Class bytecode changes from `javap` detail where available.
- Important text diffs.
- Concrete content changes with before/after or unified diff evidence.
- Manual review recommendations.

Risk defaults:

- High: deleted/missing/orphan files, config files, startup/deploy scripts, `.class` changes.
- Medium: `.jar`, `.war`, `.ear`, `.zip`, `.gz`, plugin/dependency package changes.
- Low: docs and low-impact text/metadata changes.

## Output Discipline

The sub-agent may write only inside its assigned package output directory.

Do not modify sibling package directories. Do not modify the production package top-level `production-analysis.md`; that belongs to the main agent.

## Templates

- `templates\index.md`: script-generated parent report.
- `templates\file.md`: script-generated file detail report.
- `templates\analysis.md`: agent-authored analysis report structure. The agent must follow this template when writing `analysis.md`.

## Troubleshooting

- If `BCompare.exe` is not found, set `BCOMPARE_PATH` or pass `--bc-path`.
- If `.class` detail is missing, install a JDK and ensure `javap.exe` is on `PATH`, or pass `--javap-path`.
- If Beyond Compare opens UI or stalls on many binaries, rerun with `--file-report-mode none`; `diff-index.json` and raw folder reports are still useful.
- If `diff-index.json` has zero differences but raw HTML shows differences, inspect `raw\folder.xml` and mention XML parsing uncertainty in `analysis.md`.
