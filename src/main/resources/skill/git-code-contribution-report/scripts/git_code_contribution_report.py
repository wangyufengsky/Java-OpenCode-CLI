#!/usr/bin/env python3
"""
Collect Git code contribution facts for a date range.

The script intentionally produces data, not management conclusions. The agent
uses the JSON outputs and the Markdown template to write the final analysis.
"""

from __future__ import annotations

import argparse
import datetime as dt
import fnmatch
import json
import re
import sys
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Any


BLOCK_COMMENT_EXTS = {
    ".java",
    ".js",
    ".jsx",
    ".ts",
    ".tsx",
    ".c",
    ".cc",
    ".cpp",
    ".h",
    ".hpp",
    ".cs",
    ".go",
    ".kt",
    ".kts",
    ".scala",
    ".groovy",
    ".gradle",
    ".rs",
    ".swift",
    ".php",
    ".css",
    ".scss",
    ".sass",
    ".less",
    ".proto",
    ".sql",
}
HASH_COMMENT_EXTS = {".py", ".rb", ".sh", ".bash", ".zsh", ".ps1", ".yaml", ".yml", ".toml", ".ini", ".conf", ".r", ".pl", ".graphql", ".gql"}
XML_COMMENT_EXTS = {".xml", ".html", ".htm", ".xhtml", ".vue", ".jsp", ".jspx"}
PROPERTIES_EXTS = {".properties"}
DEFAULT_INCLUDE_PATTERNS = [
    "*.java",
    "*.kt",
    "*.kts",
    "*.scala",
    "*.groovy",
    "*.gradle",
    "*.py",
    "*.rb",
    "*.sh",
    "*.bash",
    "*.zsh",
    "*.ps1",
    "*.bat",
    "*.cmd",
    "*.js",
    "*.jsx",
    "*.ts",
    "*.tsx",
    "*.mjs",
    "*.cjs",
    "*.vue",
    "*.svelte",
    "*.html",
    "*.htm",
    "*.xhtml",
    "*.css",
    "*.scss",
    "*.sass",
    "*.less",
    "*.jsp",
    "*.jspx",
    "*.xml",
    "*.yml",
    "*.yaml",
    "*.json",
    "*.toml",
    "*.ini",
    "*.conf",
    "*.properties",
    "*.sql",
    "*.c",
    "*.cc",
    "*.cpp",
    "*.cxx",
    "*.h",
    "*.hpp",
    "*.cs",
    "*.go",
    "*.rs",
    "*.swift",
    "*.php",
    "*.lua",
    "*.r",
    "*.pl",
    "*.proto",
    "*.graphql",
    "*.gql",
    "Dockerfile",
    "Dockerfile.*",
    "Makefile",
    "makefile",
    "GNUmakefile",
    "Jenkinsfile",
    "Jenkinsfile.*",
    ".gitignore",
    ".gitattributes",
    ".dockerignore",
    ".editorconfig",
]
DEFAULT_EXCLUDE_PATTERNS = [
    "*.md",
    "*.markdown",
    "*.mdown",
    "*.mkd",
    "*.doc",
    "*.docx",
    "*.xls",
    "*.xlsx",
    "*.xlsm",
    "*.ppt",
    "*.pptx",
    "*.pdf",
    "*.rtf",
    "*.txt",
    "*.csv",
    "*.png",
    "*.jpg",
    "*.jpeg",
    "*.gif",
    "*.bmp",
    "*.webp",
    "*.ico",
    "*.zip",
    "*.tar",
    "*.gz",
    "*.7z",
    "*.rar",
]
REPORT_MARKER = "<!-- CODE_CONTRIBUTION_REPORT_CONTENT -->"
AUTHOR_REPORT_MARKER = "<!-- AUTHOR_CODE_CONTRIBUTION_REPORT_CONTENT -->"
QUALITY_SUMMARY_MARKER = '"__QUALITY_SUMMARY_JSON_CONTENT__"'
MAX_QUALITY_ADJUSTMENT_PERCENT = 30.0
QUALITY_DIMENSION_LIMITS = {
    "code_standard": 8.0,
    "maintainability": 8.0,
    "risk_control": 8.0,
    "reviewability": 6.0,
}
QUALITY_FINDING_SCORE_TABLE = {
    "negative": {"low": -2.0, "medium": -5.0, "high": -8.0},
    "positive": {"low": 1.0, "medium": 3.0, "high": 5.0},
}


@dataclass
class CommentState:
    in_block: bool = False
    block_end: str = "*/"


def run_git(repo: Path, args: list[str]) -> str:
    command = ["git", *args]
    proc = subprocess.run(command, cwd=repo, text=True, capture_output=True, check=False)
    if proc.returncode != 0:
        raise RuntimeError(f"git command failed: {' '.join(command)}\n{proc.stderr.strip()}")
    return proc.stdout


def parse_date(value: str) -> dt.date:
    try:
        return dt.datetime.strptime(value, "%Y-%m-%d").date()
    except ValueError as exc:
        raise argparse.ArgumentTypeError("date must use YYYY-MM-DD") from exc


def load_author_map(path: str | None) -> dict[str, str]:
    if not path:
        return {}
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    mapping: dict[str, str] = {}

    if isinstance(data, dict) and "aliases" in data:
        aliases = data["aliases"]
        if not isinstance(aliases, dict):
            raise ValueError("author map aliases must be an object")
        for canonical, values in aliases.items():
            mapping[canonical.lower()] = canonical
            for value in values:
                mapping[str(value).lower()] = canonical
        return mapping

    if isinstance(data, dict):
        for source, target in data.items():
            mapping[str(source).lower()] = str(target)
        return mapping

    raise ValueError("author map must be a JSON object")


def normalize_author(name: str, email: str, author_map: dict[str, str]) -> str:
    candidates = [f"{name} <{email}>".lower(), name.lower(), email.lower()]
    for candidate in candidates:
        if candidate in author_map:
            return author_map[candidate]
    return f"{name} <{email}>"


def file_matches_any(path: str, patterns: list[str]) -> bool:
    normalized = path.replace("\\", "/")
    basename = normalized.rsplit("/", 1)[-1]
    return any(fnmatch.fnmatch(normalized, pattern) or fnmatch.fnmatch(basename, pattern) for pattern in patterns)


def file_is_included(path: str, patterns: list[str]) -> bool:
    return file_matches_any(path, patterns)


def file_is_excluded(path: str, patterns: list[str]) -> bool:
    return file_matches_any(path, patterns)


def file_is_counted(path: str, includes: list[str], excludes: list[str]) -> bool:
    return file_is_included(path, includes) and not file_is_excluded(path, excludes)


def build_include_patterns(user_patterns: list[str]) -> list[str]:
    patterns = [*DEFAULT_INCLUDE_PATTERNS]
    for pattern in user_patterns:
        if pattern not in patterns:
            patterns.append(pattern)
    return patterns


def build_exclude_patterns(user_patterns: list[str]) -> list[str]:
    patterns = [*DEFAULT_EXCLUDE_PATTERNS]
    for pattern in user_patterns:
        if pattern not in patterns:
            patterns.append(pattern)
    return patterns


def strip_string_literals_for_comment_scan(line: str) -> str:
    # A small guard against URLs and comment markers inside common string forms.
    return re.sub(r"(['\"])(?:\\.|(?!\1).)*\1", '""', line)


def remove_block_comments(line: str, state: CommentState, start: str, end: str) -> str:
    result: list[str] = []
    index = 0
    while index < len(line):
        if state.in_block:
            close_at = line.find(state.block_end, index)
            if close_at == -1:
                return "".join(result)
            state.in_block = False
            index = close_at + len(state.block_end)
            continue

        open_at = line.find(start, index)
        if open_at == -1:
            result.append(line[index:])
            break
        result.append(line[index:open_at])
        close_at = line.find(end, open_at + len(start))
        if close_at == -1:
            state.in_block = True
            state.block_end = end
            break
        index = close_at + len(end)

    return "".join(result)


def remove_inline_comment(line: str, marker: str) -> str:
    scan = strip_string_literals_for_comment_scan(line)
    at = scan.find(marker)
    if at == -1:
        return line
    return line[:at]


def is_countable_code_line(path: str, line: str, state: CommentState) -> bool:
    suffix = Path(path).suffix.lower()
    text = line.rstrip("\n\r")

    if not text.strip():
        return False

    if suffix in XML_COMMENT_EXTS:
        text = remove_block_comments(text, state, "<!--", "-->")
    elif suffix in BLOCK_COMMENT_EXTS:
        text = remove_block_comments(text, state, "/*", "*/")
        text = remove_inline_comment(text, "--" if suffix == ".sql" else "//")
    elif suffix in HASH_COMMENT_EXTS:
        text = remove_inline_comment(text, "#")
    elif suffix in PROPERTIES_EXTS:
        stripped = text.lstrip()
        if stripped.startswith("#") or stripped.startswith("!"):
            return False

    return bool(text.strip())


def empty_author(author: str) -> dict[str, Any]:
    return {
        "author": author,
        "commit_count": 0,
        "file_change_count": 0,
        "unique_files": [],
        "added": 0,
        "deleted": 0,
        "net": 0,
        "non_comment_added": 0,
        "non_comment_deleted": 0,
        "non_comment_net": 0,
        "non_comment_churn": 0,
        "workload_score": 0.0,
        "commits": [],
        "files": {},
        "extensions": {},
    }


def calculate_workload_score(author: dict[str, Any]) -> float:
    score = (
        author["commit_count"] * 3.0
        + author["file_change_count"] * 1.5
        + author["non_comment_added"] * 1.2
        + author["non_comment_deleted"] * 1.0
        + abs(author["non_comment_net"]) * 0.2
    )
    return round(score, 2)


def clamp_quality_adjustment(value: float) -> float:
    return max(-MAX_QUALITY_ADJUSTMENT_PERCENT, min(MAX_QUALITY_ADJUSTMENT_PERCENT, value))


def calculate_adjusted_workload_score(base_score: float, quality_adjustment_percent: float) -> float:
    bounded_adjustment = clamp_quality_adjustment(quality_adjustment_percent)
    return round(base_score * (1 + bounded_adjustment / 100), 2)


def clamp_dimension_score(dimension: str, value: float) -> float:
    limit = QUALITY_DIMENSION_LIMITS[dimension]
    return max(-limit, min(limit, value))


def normalize_quality_dimension(value: Any) -> str | None:
    dimension = str(value or "").strip()
    if dimension in QUALITY_DIMENSION_LIMITS:
        return dimension
    return None


def normalize_quality_polarity(value: Any) -> str | None:
    polarity = str(value or "").strip().lower()
    if polarity in QUALITY_FINDING_SCORE_TABLE:
        return polarity
    return None


def normalize_quality_severity(value: Any) -> str | None:
    severity = str(value or "").strip().lower()
    if severity in QUALITY_FINDING_SCORE_TABLE["negative"]:
        return severity
    return None


def score_quality_finding(finding: dict[str, Any]) -> tuple[dict[str, Any] | None, str | None]:
    dimension = normalize_quality_dimension(finding.get("dimension"))
    if dimension is None:
        return None, f"ignored finding with invalid dimension: {finding.get('dimension')!r}"

    polarity = normalize_quality_polarity(finding.get("polarity"))
    if polarity is None:
        return None, f"ignored finding with invalid polarity: {finding.get('polarity')!r}"

    severity = normalize_quality_severity(finding.get("severity"))
    if severity is None:
        return None, f"ignored finding with invalid severity: {finding.get('severity')!r}"

    if polarity == "negative" and (
        finding.get("source") != "scanner"
        or finding.get("attribution") != "owned_hunk"
        or not str(finding.get("owned_hunk_id") or "").strip()
    ):
        return None, "ignored unattributed negative finding"

    score = QUALITY_FINDING_SCORE_TABLE[polarity][severity]
    return {
        "dimension": dimension,
        "polarity": polarity,
        "severity": severity,
        "score": score,
        "rule_id": str(finding.get("rule_id") or ""),
        "file": str(finding.get("file") or ""),
        "line_start": int(finding.get("line_start") or 0),
        "line_end": int(finding.get("line_end") or 0),
        "evidence": str(finding.get("evidence") or finding.get("reason") or ""),
    }, None


def low_quality_snippet_has_negative_finding(snippet: dict[str, Any], scored_findings: list[dict[str, Any]]) -> bool:
    snippet_dimension = normalize_quality_dimension(snippet.get("dimension"))
    snippet_file = str(snippet.get("file") or "")
    for finding in scored_findings:
        if finding["polarity"] != "negative":
            continue
        if finding["dimension"] != snippet_dimension:
            continue
        if snippet_file and finding.get("file") and finding["file"] != snippet_file:
            continue
        return True
    return False


def calculate_quality_score(quality_summary: dict[str, Any]) -> dict[str, Any]:
    components_by_dimension = {dimension: 0.0 for dimension in QUALITY_DIMENSION_LIMITS}
    scored_findings: list[dict[str, Any]] = []
    scoring_notes: list[str] = []

    findings = quality_summary.get("findings", [])
    if not isinstance(findings, list):
        findings = []
        scoring_notes.append("quality_summary.findings is not a list; ignored")

    for finding in findings:
        if not isinstance(finding, dict):
            scoring_notes.append("ignored non-object finding")
            continue
        scored, note = score_quality_finding(finding)
        if note:
            scoring_notes.append(note)
            continue
        assert scored is not None
        dimension = scored["dimension"]
        components_by_dimension[dimension] = clamp_dimension_score(dimension, components_by_dimension[dimension] + scored["score"])
        scored_findings.append(scored)

    quality_adjustment_percent = clamp_quality_adjustment(sum(components_by_dimension.values()))
    components = [
        {"dimension": dimension, "score": components_by_dimension[dimension]}
        for dimension in QUALITY_DIMENSION_LIMITS
    ]
    return {
        "quality_adjustment_percent": quality_adjustment_percent,
        "components": components,
        "components_by_dimension": components_by_dimension,
        "scored_findings": scored_findings,
        "scoring_notes": scoring_notes,
    }


def make_author_key(rank: int, author: str) -> str:
    normalized = "".join(char.lower() if char.isalnum() else "-" for char in author)
    normalized = re.sub(r"-+", "-", normalized).strip("-")
    if not normalized:
        normalized = "unknown"
    return f"author-{rank:03d}-{normalized[:60]}"


def make_markdown_link(label: str, relative_path: str) -> str:
    return f"[{label}]({relative_path})"


def parse_commits(repo: Path, revision: str, since: dt.date, until: dt.date, include_merges: bool) -> list[dict[str, str]]:
    args = [
        "log",
        revision,
        f"--since={since.isoformat()} 00:00:00",
        f"--until={until.isoformat()} 23:59:59",
        "--date=iso-strict",
        "--format=%H%x1f%an%x1f%ae%x1f%ad%x1f%s",
    ]
    if not include_merges:
        args.insert(2, "--no-merges")

    output = run_git(repo, args)
    commits = []
    for line in output.splitlines():
        parts = line.split("\x1f", 4)
        if len(parts) != 5:
            continue
        commit_hash, name, email, date, subject = parts
        commits.append({"hash": commit_hash, "name": name, "email": email, "date": date, "subject": subject})
    return commits


def parse_numstat(repo: Path, commit_hash: str, includes: list[str], excludes: list[str]) -> list[dict[str, Any]]:
    output = run_git(repo, ["show", "--format=", "--numstat", "--find-renames", commit_hash])
    rows = []
    for line in output.splitlines():
        parts = line.split("\t")
        if len(parts) < 3 or parts[0] == "-" or parts[1] == "-":
            continue
        path = parts[-1]
        if not file_is_counted(path, includes, excludes):
            continue
        rows.append({"added": int(parts[0]), "deleted": int(parts[1]), "path": path})
    return rows


def parse_non_comment_diff(repo: Path, commit_hash: str, includes: list[str], excludes: list[str]) -> dict[str, dict[str, int]]:
    output = run_git(
        repo,
        ["show", "--format=", "--unified=0", "--no-ext-diff", "--find-renames", commit_hash],
    )
    current_file: str | None = None
    old_file: str | None = None
    states: dict[tuple[str, str], CommentState] = {}
    result: dict[str, dict[str, int]] = {}

    def select_current_file(path: str | None) -> None:
        nonlocal current_file
        current_file = path
        if current_file is None or not file_is_counted(current_file, includes, excludes):
            current_file = None
        elif current_file not in result:
            result[current_file] = {"added": 0, "deleted": 0}

    for line in output.splitlines():
        if line.startswith("diff "):
            current_file = None
            old_file = None
            continue
        if line.startswith("--- a/"):
            old_file = line[6:]
            continue
        if line.startswith("--- /dev/null"):
            old_file = None
            continue
        if line.startswith("+++ b/"):
            select_current_file(line[6:])
            old_file = None
            continue
        if line.startswith("+++ /dev/null"):
            select_current_file(old_file)
            old_file = None
            continue
        if line.startswith("--- ") or line.startswith("@@"):
            continue
        if current_file is None:
            continue
        if line.startswith("+") and not line.startswith("+++"):
            state = states.setdefault((current_file, "+"), CommentState())
            if is_countable_code_line(current_file, line[1:], state):
                result[current_file]["added"] += 1
        elif line.startswith("-") and not line.startswith("---"):
            state = states.setdefault((current_file, "-"), CommentState())
            if is_countable_code_line(current_file, line[1:], state):
                result[current_file]["deleted"] += 1

    return result


def add_file_stats(target: dict[str, Any], path: str, added: int, deleted: int, nc_added: int, nc_deleted: int) -> None:
    files = target["files"]
    if path not in files:
        files[path] = {"file_change_count": 0, "added": 0, "deleted": 0, "non_comment_added": 0, "non_comment_deleted": 0}
    row = files[path]
    row["file_change_count"] += 1
    row["added"] += added
    row["deleted"] += deleted
    row["non_comment_added"] += nc_added
    row["non_comment_deleted"] += nc_deleted

    ext = Path(path).suffix.lower() or "[no-ext]"
    extensions = target["extensions"]
    if ext not in extensions:
        extensions[ext] = {"file_change_count": 0, "added": 0, "deleted": 0, "non_comment_added": 0, "non_comment_deleted": 0}
    extensions[ext]["file_change_count"] += 1
    extensions[ext]["added"] += added
    extensions[ext]["deleted"] += deleted
    extensions[ext]["non_comment_added"] += nc_added
    extensions[ext]["non_comment_deleted"] += nc_deleted


def collect_stats(args: argparse.Namespace) -> dict[str, Any]:
    repo = Path(args.repo).resolve()
    run_git(repo, ["rev-parse", "--is-inside-work-tree"])

    author_map = load_author_map(args.author_map)
    include_patterns = build_include_patterns(args.include)
    exclude_patterns = build_exclude_patterns(args.exclude)
    commits = parse_commits(repo, args.revision, args.since, args.until, args.include_merges)
    authors: dict[str, dict[str, Any]] = {}

    for commit in commits:
        numstat_rows = parse_numstat(repo, commit["hash"], include_patterns, exclude_patterns)
        if not numstat_rows:
            continue

        author = normalize_author(commit["name"], commit["email"], author_map)
        stats = authors.setdefault(author, empty_author(author))
        stats["commit_count"] += 1
        stats["commits"].append(
            {
                "hash": commit["hash"],
                "short_hash": commit["hash"][:12],
                "date": commit["date"],
                "subject": commit["subject"],
            }
        )

        non_comment = parse_non_comment_diff(repo, commit["hash"], include_patterns, exclude_patterns)
        for row in numstat_rows:
            path = row["path"]
            nc = non_comment.get(path, {"added": 0, "deleted": 0})
            added = row["added"]
            deleted = row["deleted"]
            nc_added = nc["added"]
            nc_deleted = nc["deleted"]

            stats["file_change_count"] += 1
            stats["added"] += added
            stats["deleted"] += deleted
            stats["non_comment_added"] += nc_added
            stats["non_comment_deleted"] += nc_deleted
            add_file_stats(stats, path, added, deleted, nc_added, nc_deleted)

    for stats in authors.values():
        stats["unique_files"] = sorted(stats["files"].keys())
        stats["unique_file_count"] = len(stats["unique_files"])
        stats["net"] = stats["added"] - stats["deleted"]
        stats["non_comment_net"] = stats["non_comment_added"] - stats["non_comment_deleted"]
        stats["non_comment_churn"] = stats["non_comment_added"] + stats["non_comment_deleted"]
        stats["workload_score"] = calculate_workload_score(stats)
        stats["base_workload_score"] = stats["workload_score"]
        stats["quality_adjustment_percent"] = 0
        stats["extensions"] = dict(sorted(stats["extensions"].items(), key=lambda item: item[1]["non_comment_added"] + item[1]["non_comment_deleted"], reverse=True))

    ranked = sorted(authors.values(), key=lambda item: (item["workload_score"], item["non_comment_churn"], item["commit_count"]), reverse=True)
    for index, stats in enumerate(ranked, start=1):
        stats["rank"] = index

    totals = {
        "commit_count": sum(author["commit_count"] for author in ranked),
        "file_change_count": sum(author["file_change_count"] for author in ranked),
        "unique_file_count": len({path for author in ranked for path in author["unique_files"]}),
        "added": sum(author["added"] for author in ranked),
        "deleted": sum(author["deleted"] for author in ranked),
        "non_comment_added": sum(author["non_comment_added"] for author in ranked),
        "non_comment_deleted": sum(author["non_comment_deleted"] for author in ranked),
    }
    totals["net"] = totals["added"] - totals["deleted"]
    totals["non_comment_net"] = totals["non_comment_added"] - totals["non_comment_deleted"]
    totals["non_comment_churn"] = totals["non_comment_added"] + totals["non_comment_deleted"]

    return {
        "metadata": {
            "repo": str(repo),
            "revision": args.revision,
            "since": args.since.isoformat(),
            "until": args.until.isoformat(),
            "include_merges": args.include_merges,
            "default_include": DEFAULT_INCLUDE_PATTERNS,
            "user_include": args.include,
            "include": include_patterns,
            "default_exclude": DEFAULT_EXCLUDE_PATTERNS,
            "user_exclude": args.exclude,
            "exclude": exclude_patterns,
            "author_map": str(Path(args.author_map).resolve()) if args.author_map else None,
            "generated_at": dt.datetime.now().astimezone().isoformat(timespec="seconds"),
            "final_report": str((Path(args.out).resolve() / "code-contribution-report.md")),
            "index_inputs": str((Path(args.out).resolve() / "index_inputs.json")),
            "details_dir": str((Path(args.out).resolve() / "details")),
            "reports_dir": str((Path(args.out).resolve() / "reports")),
            "report_marker": REPORT_MARKER,
            "author_report_marker": AUTHOR_REPORT_MARKER,
            "notes": [
                "Only development-related files matching include patterns and not matching exclude patterns are counted.",
                "non_comment_* metrics filter blank lines and obvious comment-only changed lines without modifying source files.",
                "Markdown, Office, plain document, media, archive, and similar non-development files are excluded from all contribution metrics and scores by default.",
                "base_workload_score is a code-change volume score before quality adjustment.",
                "Final workload_score is calculated by the main agent after reading quality-summary.json.",
            ],
        },
        "totals": totals,
        "authors": ranked,
    }


def write_json(path: Path, data: Any) -> None:
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def build_execution_worklist(detail_json: Path, report_md: Path, quality_summary_json: Path) -> list[dict[str, Any]]:
    return [
        {
            "step": 1,
            "action": "read_detail_json",
            "required": True,
            "target_path": str(detail_json),
            "status": "pending",
        },
        {
            "step": 2,
            "action": "read_person_report_template",
            "required": True,
            "target_path": "templates/person-code-contribution-report.md",
            "status": "pending",
        },
        {
            "step": 3,
            "action": "draft_person_report",
            "required": True,
            "target_path": str(report_md),
            "status": "pending",
        },
        {
            "step": 4,
            "action": "write_person_report",
            "required": True,
            "target_path": str(report_md),
            "marker": AUTHOR_REPORT_MARKER,
            "status": "pending",
        },
        {
            "step": 5,
            "action": "draft_quality_summary",
            "required": True,
            "target_path": str(quality_summary_json),
            "status": "pending",
        },
        {
            "step": 6,
            "action": "write_quality_summary",
            "required": True,
            "target_path": str(quality_summary_json),
            "marker": QUALITY_SUMMARY_MARKER,
            "status": "pending",
        },
        {
            "step": 7,
            "action": "verify_outputs",
            "required": True,
            "required_paths": [str(report_md), str(quality_summary_json)],
            "status": "pending",
        },
        {
            "step": 8,
            "action": "final_response",
            "required": True,
            "allowed": ["DONE person_report_md=<path> quality_summary_json=<path>", "BLOCKED step=<step> action=<action> path=<path> reason=<reason>"],
            "status": "pending",
        },
    ]


def attach_output_paths(data: dict[str, Any], out: Path) -> dict[str, Any]:
    tasks = []
    for author in data["authors"]:
        author_key = make_author_key(author["rank"], author["author"])
        detail_json = out / "details" / f"{author_key}.json"
        report_md = out / "reports" / author_key / "person-report.md"
        quality_summary_json = out / "reports" / author_key / "quality-summary.json"
        report_relative_path = f"reports/{author_key}/person-report.md"
        report_markdown_link = make_markdown_link("person-report.md", report_relative_path)
        author["author_key"] = author_key
        author["detail_json"] = str(detail_json)
        author["person_report_md"] = str(report_md)
        author["quality_summary_json"] = str(quality_summary_json)
        author["person_report_relative_path"] = report_relative_path
        author["person_report_markdown_link"] = report_markdown_link
        author["person_report_marker"] = AUTHOR_REPORT_MARKER
        execution_worklist = build_execution_worklist(detail_json.resolve(), report_md.resolve(), quality_summary_json.resolve())
        author["execution_worklist"] = execution_worklist
        tasks.append(
            {
                "rank": author["rank"],
                "author": author["author"],
                "author_key": author_key,
                "detail_json": str(detail_json),
                "report_md": str(report_md),
                "quality_summary_json": str(quality_summary_json),
                "report_relative_path": report_relative_path,
                "report_markdown_link": report_markdown_link,
                "report_marker": AUTHOR_REPORT_MARKER,
                "quality_summary_marker": QUALITY_SUMMARY_MARKER,
                "execution_worklist": execution_worklist,
            }
        )
    data["tasks"] = tasks
    return data


def write_author_outputs(out: Path, data: dict[str, Any]) -> None:
    details_dir = out / "details"
    reports_dir = out / "reports"
    details_dir.mkdir(parents=True, exist_ok=True)
    reports_dir.mkdir(parents=True, exist_ok=True)

    for author in data["authors"]:
        detail_path = Path(author["detail_json"])
        report_path = Path(author["person_report_md"])
        quality_path = Path(author["quality_summary_json"])
        report_path.parent.mkdir(parents=True, exist_ok=True)
        detail = {
            "metadata": data["metadata"],
            "author_key": author["author_key"],
            "rank": author["rank"],
            "author": author["author"],
            "summary": {
                "commit_count": author["commit_count"],
                "file_change_count": author["file_change_count"],
                "unique_file_count": author["unique_file_count"],
                "added": author["added"],
                "deleted": author["deleted"],
                "net": author["net"],
                "non_comment_added": author["non_comment_added"],
                "non_comment_deleted": author["non_comment_deleted"],
                "non_comment_net": author["non_comment_net"],
                "non_comment_churn": author["non_comment_churn"],
                "base_workload_score": author["base_workload_score"],
                "quality_adjustment_percent": author["quality_adjustment_percent"],
                "workload_score": author["workload_score"],
            },
            "extensions": author["extensions"],
            "commits": author["commits"],
            "execution_worklist": author["execution_worklist"],
            "output": {
                "person_report_md": author["person_report_md"],
                "quality_summary_json": author["quality_summary_json"],
                "person_report_relative_path": author["person_report_relative_path"],
                "person_report_markdown_link": author["person_report_markdown_link"],
                "report_marker": AUTHOR_REPORT_MARKER,
                "quality_summary_marker": QUALITY_SUMMARY_MARKER,
            },
        }
        write_json(detail_path, detail)
        report_path.write_text(AUTHOR_REPORT_MARKER + "\n", encoding="utf-8")
        quality_path.write_text(QUALITY_SUMMARY_MARKER + "\n", encoding="utf-8")


def build_index_inputs(data: dict[str, Any]) -> dict[str, Any]:
    return {
        "metadata": data["metadata"],
        "totals": data["totals"],
        "final_report": data["metadata"]["final_report"],
        "final_report_marker": REPORT_MARKER,
        "author_report_marker": AUTHOR_REPORT_MARKER,
        "quality_summary_marker": QUALITY_SUMMARY_MARKER,
        "tasks": data["tasks"],
    }


def write_index(path: Path, data: dict[str, Any]) -> None:
    rows = []
    for author in data["authors"]:
        rows.append(
            "| {rank} | {author} | {commit_count} | {file_change_count} | {unique_file_count} | {non_comment_added} | {non_comment_deleted} | {non_comment_net} | {workload_score} |".format(
                **author
            )
        )
    if not rows:
        rows.append("| - | 无 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |")

    metadata = data["metadata"]
    totals = data["totals"]
    content = f"""# 代码提交量统计数据预览

## 统计范围

- 仓库：`{metadata["repo"]}`
- 修订范围：`{metadata["revision"]}`
- 开始日期：`{metadata["since"]}`
- 结束日期：`{metadata["until"]}`
- 是否包含 merge commit：`{metadata["include_merges"]}`
- 默认统计白名单：`{", ".join(metadata["default_include"])}`
- 用户追加统计白名单：`{", ".join(metadata["user_include"]) or "无"}`
- 默认排除规则：`{", ".join(metadata["default_exclude"])}`
- 用户追加排除规则：`{", ".join(metadata["user_exclude"]) or "无"}`

## 总体数据

| 提交数 | 文件修改次数 | 去注释新增行 | 去注释删除行 | 去注释净变更行 |
| ---: | ---: | ---: | ---: | ---: |
| {totals["commit_count"]} | {totals["file_change_count"]} | {totals["non_comment_added"]} | {totals["non_comment_deleted"]} | {totals["non_comment_net"]} |

## 人员排名数据

| 初始排名 | 开发人员 | 提交数 | 文件修改次数 | 去重文件数 | 去注释新增行 | 去注释删除行 | 去注释净变更行 | 基础工作量分 |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
{chr(10).join(rows)}

## 后续处理

请让主 agent 读取 `summary.json` 和 `index_inputs.json`，按 `index_inputs.json.tasks[].detail_json` 为每个人派发子 agent。

每个子 agent 只读取自己的 `detail_json`，写入自己的 `report_md`，并通过替换 `quality_summary_marker` 写入自己的 `quality_summary_json`。主 agent 等待所有个人报告和质量摘要完成后，调用 `scripts/git_code_contribution_report.py score-quality <quality-summary.json>` 统一计算质量分，再按质量调整公式计算最终 `workload_score`，并按 `templates/code-contribution-report.md` 生成最终 `code-contribution-report.md`。
"""
    path.write_text(content, encoding="utf-8")


def build_summary(data: dict[str, Any]) -> dict[str, Any]:
    return {
        "metadata": data["metadata"],
        "totals": data["totals"],
        "ranking": [
            {
                "rank": author["rank"],
                "author": author["author"],
                "commit_count": author["commit_count"],
                "file_change_count": author["file_change_count"],
                "unique_file_count": author["unique_file_count"],
                "non_comment_added": author["non_comment_added"],
                "non_comment_deleted": author["non_comment_deleted"],
                "non_comment_net": author["non_comment_net"],
                "non_comment_churn": author["non_comment_churn"],
                "base_workload_score": author["base_workload_score"],
                "quality_adjustment_percent": author["quality_adjustment_percent"],
                "workload_score": author["workload_score"],
                "author_key": author.get("author_key"),
                "detail_json": author.get("detail_json"),
                "person_report_md": author.get("person_report_md"),
                "quality_summary_json": author.get("quality_summary_json"),
                "person_report_relative_path": author.get("person_report_relative_path"),
                "person_report_markdown_link": author.get("person_report_markdown_link"),
                "person_report_marker": author.get("person_report_marker"),
            }
            for author in data["authors"]
        ],
        "tasks": data.get("tasks", []),
    }


def parse_args() -> argparse.Namespace:
    if len(sys.argv) > 1 and sys.argv[1] == "score-quality":
        parser = argparse.ArgumentParser(description="Score one quality-summary.json with deterministic rules.")
        parser.add_argument("quality_summary_json", help="Path to a quality-summary.json file.")
        parser.add_argument("--out", help="Optional output path for the scored JSON result. Defaults to stdout.")
        args = parser.parse_args(sys.argv[2:])
        args.command = "score-quality"
        return args

    parser = argparse.ArgumentParser(description="Collect Git code contribution statistics by developer.")
    parser.add_argument("--since", required=True, type=parse_date, help="Start date, inclusive, YYYY-MM-DD.")
    parser.add_argument("--until", required=True, type=parse_date, help="End date, inclusive, YYYY-MM-DD.")
    parser.add_argument("--repo", default=".", help="Git repository path. Default: current directory.")
    parser.add_argument("--out", required=True, help="Output directory.")
    parser.add_argument("--revision", default="HEAD", help="Git revision range root, such as HEAD, main, or --all. Default: HEAD.")
    parser.add_argument("--all", dest="revision", action="store_const", const="--all", help="Collect commits from all refs.")
    parser.add_argument("--include-merges", action="store_true", help="Include merge commits. Default: excluded.")
    parser.add_argument("--author-map", help="JSON file for author alias normalization.")
    parser.add_argument(
        "--include",
        action="append",
        default=[],
        help="Additional include file glob for development-related files. Can be repeated. Files still must not match default or user exclude patterns.",
    )
    parser.add_argument(
        "--exclude",
        action="append",
        default=[],
        help="Additional exclude file glob. Markdown, Office, plain document, media, and archive files are excluded by default. Can be repeated, for example --exclude 'target/**' --exclude '*.lock'.",
    )
    args = parser.parse_args()
    args.command = "collect"
    if args.since > args.until:
        parser.error("--since must be earlier than or equal to --until")
    return args


def main() -> None:
    args = parse_args()
    if args.command == "score-quality":
        quality_summary_path = Path(args.quality_summary_json)
        quality_summary = json.loads(quality_summary_path.read_text(encoding="utf-8"))
        scored = calculate_quality_score(quality_summary)
        output = json.dumps(scored, ensure_ascii=False, indent=2) + "\n"
        if args.out:
            Path(args.out).write_text(output, encoding="utf-8")
        else:
            print(output, end="")
        return

    out = Path(args.out).resolve()
    out.mkdir(parents=True, exist_ok=True)

    data = collect_stats(args)
    data = attach_output_paths(data, out)
    write_author_outputs(out, data)
    write_json(out / "details.json", data)
    write_json(out / "summary.json", build_summary(data))
    write_json(out / "index_inputs.json", build_index_inputs(data))
    write_index(out / "index.md", data)
    (out / "code-contribution-report.md").write_text(REPORT_MARKER + "\n", encoding="utf-8")

    print(f"wrote: {out / 'summary.json'}")
    print(f"wrote: {out / 'details.json'}")
    print(f"wrote: {out / 'index_inputs.json'}")
    print(f"wrote: {out / 'index.md'}")
    print(f"wrote: {out / 'code-contribution-report.md'}")


if __name__ == "__main__":
    main()
