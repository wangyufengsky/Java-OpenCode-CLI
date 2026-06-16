#!/usr/bin/env python3
"""Prepare per-transaction SmartESB rewrite code-review tasks."""

from __future__ import annotations

import argparse
import json
import os
from datetime import datetime
from pathlib import Path, PureWindowsPath
from typing import Any


TOP_LEVEL_OUTPUT_MARKERS = {
    "index_md": "<!-- OPENCODE_APPEND:index -->",
    "summary_md": "<!-- OPENCODE_APPEND:summary -->",
}

TRANSACTION_OUTPUT_MARKERS = {
    "review_md": "<!-- OPENCODE_APPEND:review -->",
    "matrix_md": "<!-- OPENCODE_APPEND:mapping-matrix -->",
    "findings_md": "<!-- OPENCODE_APPEND:01-findings -->",
    "code_chains_md": "<!-- OPENCODE_APPEND:02-code-chains -->",
    "protocol_review_md": "<!-- OPENCODE_APPEND:03-protocol-review -->",
    "behavior_review_md": "<!-- OPENCODE_APPEND:04-behavior-review -->",
    "verification_md": "<!-- OPENCODE_APPEND:05-verification -->",
    "code_standard_md": "<!-- OPENCODE_APPEND:06-code-standard -->",
}


def parse_transaction(value: str) -> tuple[str, str]:
    if "=" in value:
        key, desc = value.split("=", 1)
        transaction = key.strip()
        description = desc.strip()
    else:
        transaction = value.strip()
        description = ""
    if not transaction:
        raise argparse.ArgumentTypeError("transaction name cannot be empty")
    return transaction, description


def slugify(value: str) -> str:
    safe = []
    for ch in value:
        if ch.isalnum() or ch in ("-", "_"):
            safe.append(ch)
        else:
            safe.append("-")
    slug = "".join(safe).strip("-")
    return slug or "transaction"


def path_string(path: str) -> str:
    return str(path).replace("/", "\\")


def is_windows_absolute_path(path: str) -> bool:
    value = PureWindowsPath(path)
    return bool(value.drive and value.root)


def windows_path(path: PureWindowsPath) -> str:
    return str(path)


def write_json(path: Path, data: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def write_text_if_missing(path: Path, content: str, overwrite: bool) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if overwrite or not path.exists():
        path.write_text(content, encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--out",
        required=True,
        help="Windows absolute output review workspace directory emitted into task JSON and used by IDEA MCP.",
    )
    parser.add_argument(
        "--local-out",
        default=None,
        help=(
            "Optional local mirror directory for tests or packaging when the script is executed outside Windows. "
            "Do not use for real opencode review unless it maps to --out."
        ),
    )
    parser.add_argument("--old-project", default=r"D:\upfs\qianzhi\upfs-cloud-xc")
    parser.add_argument("--new-project", default=r"D:\upfs-nl-json")
    parser.add_argument("--legacy-index", default=r"D:\upfs-nl-json\doc\index.md")
    parser.add_argument("--doc-root", default=r"D:\upfs-nl-json\doc\docment")
    parser.add_argument("--old-8583-doc", default=None)
    parser.add_argument("--json-doc", default=None)
    parser.add_argument("--mapping-doc", default=None)
    parser.add_argument("--reconstructed-design", default=None)
    parser.add_argument(
        "--transaction",
        action="append",
        default=[],
        metavar="NAME[=DESCRIPTION]",
        help="Transaction to review. Repeat for multiple transactions. Required.",
    )
    parser.add_argument("--overwrite", action="store_true")
    args = parser.parse_args()

    if not is_windows_absolute_path(args.out):
        parser.error(
            "--out must be a Windows absolute path such as D:\\review-output\\case1. "
            "This path is written into task JSON and must be writable by IDEA MCP on Windows."
        )
    if os.name != "nt" and not args.local_out:
        parser.error("--local-out is required when running this Windows-path workflow outside Windows.")

    logical_out = PureWindowsPath(args.out)
    out = Path(args.local_out) if args.local_out else Path(args.out)
    if out.exists() and any(out.iterdir()) and not args.overwrite:
        timestamp = datetime.now().strftime("%Y%m%d-%H%M%S")
        out = out.with_name(f"{out.name}-{timestamp}")
        logical_out = logical_out.with_name(f"{logical_out.name}-{timestamp}")
    out.mkdir(parents=True, exist_ok=True)
    write_text_if_missing(
        out / "index.md",
        f"# SmartESB 8583 到 JSON 重构代码审查索引\n\n{TOP_LEVEL_OUTPUT_MARKERS['index_md']}\n",
        args.overwrite,
    )
    write_text_if_missing(
        out / "summary.md",
        f"# SmartESB 重构代码审查摘要\n\n{TOP_LEVEL_OUTPUT_MARKERS['summary_md']}\n",
        args.overwrite,
    )

    skill_dir = Path(__file__).resolve().parents[1]
    summary_schema = skill_dir / "schemas" / "transaction-summary.schema.json"
    doc_root = Path(args.doc_root)
    old_8583_doc = args.old_8583_doc or str(doc_root / "8583.md")
    json_doc = args.json_doc or str(doc_root / "json.md")
    mapping_doc = args.mapping_doc or str(doc_root / "8583 to json.md")
    reconstructed_design = args.reconstructed_design or str(doc_root / "重构项目详细设计文档.md")

    if not args.transaction:
        parser.error("at least one --transaction NAME[=DESCRIPTION] is required")
    transactions = [parse_transaction(item) for item in args.transaction]
    tasks: list[dict[str, Any]] = []
    for order, (transaction, description) in enumerate(transactions, start=1):
        slug = slugify(transaction)
        logical_report_dir = logical_out / "reports" / slug
        logical_sections_dir = logical_report_dir / "sections"
        logical_task_path = logical_out / "tasks" / f"transaction-{slug}.json"
        report_dir = out / "reports" / slug
        sections_dir = report_dir / "sections"
        report_dir.mkdir(parents=True, exist_ok=True)
        sections_dir.mkdir(parents=True, exist_ok=True)
        report_files = {
            "review_md": report_dir / "review.md",
            "summary_json": report_dir / "summary.json",
            "matrix_md": report_dir / "mapping-matrix.md",
            "findings_md": sections_dir / "01-findings.md",
            "code_chains_md": sections_dir / "02-code-chains.md",
            "protocol_review_md": sections_dir / "03-protocol-review.md",
            "behavior_review_md": sections_dir / "04-behavior-review.md",
            "verification_md": sections_dir / "05-verification.md",
            "code_standard_md": sections_dir / "06-code-standard.md",
        }
        write_text_if_missing(
            report_files["review_md"],
            f"# 审查报告\n\n{TRANSACTION_OUTPUT_MARKERS['review_md']}\n",
            args.overwrite,
        )
        write_text_if_missing(
            report_files["matrix_md"],
            f"# 字段映射矩阵\n\n{TRANSACTION_OUTPUT_MARKERS['matrix_md']}\n",
            args.overwrite,
        )
        for section_key, section_path in report_files.items():
            if not section_key.endswith("_md") or section_key in {"review_md", "matrix_md"}:
                continue
            write_text_if_missing(
                section_path,
                f"# {section_path.stem}\n\n{TRANSACTION_OUTPUT_MARKERS[section_key]}\n",
                args.overwrite,
            )
        write_text_if_missing(report_files["summary_json"], "{}\n", args.overwrite)
        task = {
            "order": order,
            "transaction": transaction,
            "description": description,
            "old_project": path_string(args.old_project),
            "new_project": path_string(args.new_project),
            "documents": {
                "legacy_index": path_string(args.legacy_index),
                "old_8583": path_string(old_8583_doc),
                "json": path_string(json_doc),
                "mapping_8583_to_json": path_string(mapping_doc),
                "reconstructed_design": path_string(reconstructed_design),
            },
            "skill": {
                "dir": str(skill_dir),
                "prompt": str(skill_dir / "prompts" / "run-transaction-review.md"),
                "transaction_template": str(skill_dir / "templates" / "transaction-review.md"),
                "summary_schema": path_string(summary_schema),
                "preferred_writer": "idea_mcp",
                "idea_mcp_write_tools": [
                    "intellij-idea_replace_text_undoable",
                    "intellij-idea_replace_text_in_file",
                ],
                "index_mcp_code_tools": [
                    "intellij-index_ide_find_class",
                    "intellij-index_ide_find_file",
                    "intellij-index_ide_find_key_file",
                    "intellij-index_ide_read_file",
                ],
                "index_mcp_sync_tools": [
                    "intellij-index_ide_sync_files",
                ],
                "db_mcp_tool_prefix": "intellij-db_*",
            },
            "output": {
                "dir": windows_path(logical_report_dir),
                "review_md": windows_path(logical_report_dir / "review.md"),
                "summary_json": windows_path(logical_report_dir / "summary.json"),
                "matrix_md": windows_path(logical_report_dir / "mapping-matrix.md"),
                "sections_dir": windows_path(logical_sections_dir),
                "findings_md": windows_path(logical_sections_dir / "01-findings.md"),
                "code_chains_md": windows_path(logical_sections_dir / "02-code-chains.md"),
                "protocol_review_md": windows_path(logical_sections_dir / "03-protocol-review.md"),
                "behavior_review_md": windows_path(logical_sections_dir / "04-behavior-review.md"),
                "verification_md": windows_path(logical_sections_dir / "05-verification.md"),
                "code_standard_md": windows_path(logical_sections_dir / "06-code-standard.md"),
            },
            "output_markers": TRANSACTION_OUTPUT_MARKERS,
            "rules": {
                "code_lookup": "必须使用 intellij-index MCP 定位和读取代码；MCP 不可用时标记未验证或停止审查，禁止使用 shell。",
                "index_sync": "搜索不到刚生成或刚修改的文件时，先尝试 intellij-index_ide_sync_files，再重试一次。",
                "db_lookup": "需要数据库/SQL 证据时，优先使用当前客户端暴露的 intellij-db_* 工具；未暴露时记录未验证，不用 shell 强行连库。",
                "scope": "只审查当前交易。",
                "protocol_focus": "重点审查 8583 到 JSON 的字段映射和处理等价性。",
                "output_language": "所有 Markdown 报告必须全中文；代码标识符、路径、协议域号和 JSON path 可保留原文。",
                "precreated_outputs": "准备脚本已预创建 review.md、mapping-matrix.md、sections/*.md 和 summary.json；子 agent 只能替换这些已存在文件的内容，禁止创建新文件。",
                "writer_preference": "必须使用 intellij-idea_replace_text_undoable、intellij-idea_replace_text_in_file 写入已存在文件；子 agent 禁止调用 intellij-idea_create_new_file。IDEA MCP 不可用或输出路径不可写时停止并报告失败，禁止使用 shell。",
                "markdown_write": "Markdown 必须分块写入，不要一次生成很大的 MD 文件。",
                "markdown_max_chars_per_write": 6000,
                "markdown_max_lines_per_write": 120,
            },
        }
        task_path = out / "tasks" / f"transaction-{slug}.json"
        task["task_path"] = windows_path(logical_task_path)
        write_json(task_path, task)
        tasks.append(task)

    summary = {
        "generated_at": datetime.now().isoformat(timespec="seconds"),
        "out": windows_path(logical_out),
        "local_out": str(out) if args.local_out else None,
        "old_project": path_string(args.old_project),
        "new_project": path_string(args.new_project),
        "transaction_count": len(tasks),
        "transactions": [
            {
                "transaction": task["transaction"],
                "description": task["description"],
                "task_path": task["task_path"],
                "review_md": task["output"]["review_md"],
                "summary_json": task["output"]["summary_json"],
            }
            for task in tasks
        ],
    }
    index_inputs = {
        "out": windows_path(logical_out),
        "local_out": str(out) if args.local_out else None,
        "skill_dir": str(skill_dir),
        "schemas": {
            "transaction_summary": path_string(summary_schema),
        },
        "templates": {
            "index": str(skill_dir / "templates" / "index.md"),
            "transaction_review": str(skill_dir / "templates" / "transaction-review.md"),
        },
        "output": {
            "index_md": windows_path(logical_out / "index.md"),
            "summary_md": windows_path(logical_out / "summary.md"),
        },
        "output_markers": TOP_LEVEL_OUTPUT_MARKERS,
        "prompts": {
            "transaction_review": str(skill_dir / "prompts" / "run-transaction-review.md"),
            "synthesize_index": str(skill_dir / "prompts" / "synthesize-index.md"),
        },
        "tasks": [
            {
                "transaction": task["transaction"],
                "description": task["description"],
                "task_path": task["task_path"],
                "report_dir": task["output"]["dir"],
                "review_md": task["output"]["review_md"],
                "summary_json": task["output"]["summary_json"],
            }
            for task in tasks
        ],
    }
    write_json(out / "summary.json", summary)
    write_json(out / "index_inputs.json", index_inputs)

    print(f"output directory: {windows_path(logical_out)}")
    if args.local_out:
        print(f"local mirror: {out}")
    print(f"tasks: {len(tasks)}")
    print(f"summary: {out / 'summary.json'}")
    print(f"index inputs: {out / 'index_inputs.json'}")
    for task in tasks:
        print(f"task: {task['task_path']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
