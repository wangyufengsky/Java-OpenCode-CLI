#!/usr/bin/env python3
"""Compare two tar archives with Beyond Compare and generate Markdown reports."""

from __future__ import annotations

import argparse
import datetime as dt
import difflib
import hashlib
import html
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tarfile
import xml.etree.ElementTree as ET


DEFAULT_BC_PATHS = (
    r"C:\Program Files\Beyond Compare 5\BCompare.exe",
    r"C:\Program Files\Beyond Compare 4\BCompare.exe",
    r"C:\Program Files (x86)\Beyond Compare 5\BCompare.exe",
    r"C:\Program Files (x86)\Beyond Compare 4\BCompare.exe",
)

TEXT_EXTENSIONS = {
    ".bat",
    ".cmd",
    ".conf",
    ".config",
    ".css",
    ".csv",
    ".env",
    ".gitignore",
    ".htm",
    ".html",
    ".ini",
    ".java",
    ".js",
    ".json",
    ".log",
    ".md",
    ".properties",
    ".ps1",
    ".py",
    ".sh",
    ".sql",
    ".ts",
    ".txt",
    ".xml",
    ".yaml",
    ".yml",
}


class ReportError(RuntimeError):
    """Raised for actionable report generation failures."""


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Compare two tar archives with Beyond Compare and generate GitHub Markdown reports."
    )
    parser.add_argument("--left", required=True, help="Left/old tar archive path.")
    parser.add_argument("--right", required=True, help="Right/new tar archive path.")
    parser.add_argument("--out", required=True, help="Output report directory.")
    parser.add_argument("--title", default="Beyond Compare tar diff", help="Markdown report title.")
    parser.add_argument("--bc-path", help="Path to BCompare.exe. Overrides BCOMPARE_PATH.")
    parser.add_argument(
        "--keep-raw",
        action="store_true",
        help="Keep raw Beyond Compare outputs. Raw outputs are kept by default.",
    )
    parser.add_argument(
        "--file-report-mode",
        choices=("text", "all", "none"),
        default="text",
        help=(
            "Controls per-file Beyond Compare reports. "
            "'text' only runs them for likely text files, 'all' runs them for every paired change, "
            "and 'none' only writes Markdown metadata child reports. Default: text."
        ),
    )
    parser.add_argument(
        "--template-dir",
        help="Directory containing index.md and file.md report templates. Defaults to this skill's templates directory.",
    )
    parser.add_argument(
        "--class-detail-mode",
        choices=("javap", "none"),
        default="javap",
        help=(
            "Controls .class detail generation. 'javap' extracts changed class files from both tar archives, "
            "runs javap -verbose -p -c, and writes a bytecode diff. Default: javap."
        ),
    )
    parser.add_argument(
        "--javap-path",
        help="Path to javap.exe. If omitted, the script searches PATH for javap or javap.exe.",
    )
    args = parser.parse_args(argv)

    try:
        generate_report(args)
    except ReportError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    return 0


def generate_report(args: argparse.Namespace) -> None:
    left = Path(args.left).expanduser()
    right = Path(args.right).expanduser()
    out_dir = Path(args.out).expanduser()

    if not left.exists():
        raise ReportError(f"left archive does not exist: {left}")
    if not right.exists():
        raise ReportError(f"right archive does not exist: {right}")

    bc_path = resolve_bcompare(args.bc_path)
    template_dir = resolve_template_dir(args.template_dir)
    raw_dir = out_dir / "raw"
    files_dir = out_dir / "files"
    raw_dir.mkdir(parents=True, exist_ok=True)
    files_dir.mkdir(parents=True, exist_ok=True)

    folder_xml = raw_dir / "folder.xml"
    folder_html = raw_dir / "folder.html"
    folder_script = raw_dir / "folder.bcscript"
    folder_log = raw_dir / "folder.log"

    write_script(
        folder_script,
        [
            script_line("log verbose", folder_log),
            "criteria rules-based",
            script_line("load", left, right),
            "expand all",
            script_line("folder-report layout:xml output-to:", folder_xml),
            script_line(
                "folder-report layout:side-by-side options:display-mismatches output-to:",
                folder_html,
                suffix=" output-options:html-color",
            ),
        ],
    )
    run_bcompare(bc_path, folder_script, raw_dir / "folder.command.log")

    entries = parse_folder_report(folder_xml)
    changed_entries = [entry for entry in entries if entry.is_difference]
    used_names: set[str] = set()

    for entry in changed_entries:
        child_name = unique_child_name(entry.path, used_names)
        entry.child_md = f"files/{child_name}.md"

    write_index_markdown(
        out_dir / "index.md",
        title=args.title,
        left=left,
        right=right,
        out_dir=out_dir,
        bc_path=bc_path,
        folder_html=folder_html,
        folder_xml=folder_xml,
        entries=changed_entries,
        template_dir=template_dir,
    )

    for entry in changed_entries:
        child_name = Path(entry.child_md).stem
        child_path = files_dir / f"{child_name}.md"
        if should_generate_file_report(entry, args.file_report_mode):
            generate_file_raw_reports(bc_path, left, right, raw_dir, entry, child_name)
        if entry.is_paired_change and is_probably_text(entry.path) and not entry.raw_patch:
            generate_python_text_diff(left, right, raw_dir, entry, child_name)
        if entry.is_paired_change and is_class_file(entry.path):
            generate_class_detail(
                left=left,
                right=right,
                raw_dir=raw_dir,
                entry=entry,
                child_name=child_name,
                mode=args.class_detail_mode,
                explicit_javap=args.javap_path,
            )
        write_child_markdown(child_path, entry, args.title, folder_html, template_dir)

    write_index_markdown(
        out_dir / "index.md",
        title=args.title,
        left=left,
        right=right,
        out_dir=out_dir,
        bc_path=bc_path,
        folder_html=folder_html,
        folder_xml=folder_xml,
        entries=changed_entries,
        template_dir=template_dir,
    )
    write_diff_index(
        out_dir / "diff-index.json",
        title=args.title,
        left=left,
        right=right,
        folder_html=folder_html,
        folder_xml=folder_xml,
        entries=changed_entries,
    )

    print(f"wrote {out_dir / 'index.md'}")
    print(f"wrote {out_dir / 'diff-index.json'}")


def resolve_bcompare(explicit_path: str | None) -> Path:
    candidates: list[str] = []
    if explicit_path:
        candidates.append(explicit_path)
    env_path = os.environ.get("BCOMPARE_PATH")
    if env_path:
        candidates.append(env_path)
    candidates.extend(DEFAULT_BC_PATHS)

    for candidate in candidates:
        path = Path(candidate).expanduser()
        if path.exists():
            return path

    found = shutil.which("BCompare.exe") or shutil.which("bcompare.exe")
    if found:
        return Path(found)

    searched = "\n  - ".join(candidates)
    raise ReportError(
        "BCompare.exe was not found. Set BCOMPARE_PATH or pass --bc-path. Searched:\n  - "
        + searched
    )


def resolve_template_dir(explicit_dir: str | None) -> Path:
    template_dir = Path(explicit_dir).expanduser() if explicit_dir else Path(__file__).resolve().parents[1] / "templates"
    required = [template_dir / "index.md", template_dir / "file.md"]
    missing = [str(path) for path in required if not path.exists()]
    if missing:
        raise ReportError("missing report template file(s): " + ", ".join(missing))
    return template_dir


def run_bcompare(bc_path: Path, script_path: Path, command_log: Path) -> None:
    command = [str(bc_path), f"@{script_path}", "/silent"]
    completed = subprocess.run(command, capture_output=True, text=True)
    command_log.write_text(
        "command: "
        + " ".join(command)
        + "\n\nstdout:\n"
        + completed.stdout
        + "\n\nstderr:\n"
        + completed.stderr,
        encoding="utf-8",
    )
    if completed.returncode != 0:
        raise ReportError(
            f"Beyond Compare failed for script {script_path} with exit code {completed.returncode}. "
            f"See {command_log}."
        )


def write_script(path: Path, lines: list[str]) -> None:
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def script_line(prefix: str, *paths: Path, suffix: str = "") -> str:
    rendered = " ".join(quote_bc_path(path) for path in paths)
    if prefix.endswith(":"):
        return f"{prefix}{rendered}{suffix}"
    return f"{prefix} {rendered}{suffix}"


def quote_bc_path(path: Path | str) -> str:
    text = str(path)
    if '"' in text:
        raise ReportError(f'Beyond Compare script paths cannot contain double quotes: {text}')
    return f'"{text}"'


class DiffEntry:
    def __init__(self, path: str, status: str, kind: str = "file", metadata: dict[str, str] | None = None):
        self.path = normalize_archive_path(path)
        self.status = status or "different"
        self.kind = kind or "file"
        self.metadata = metadata or {}
        self.child_md = ""
        self.raw_html = ""
        self.raw_patch = ""
        self.raw_log = ""
        self.diff_excerpt = ""
        self.binary_detail: dict[str, object] = {}
        self.detail_note = ""

    @property
    def is_difference(self) -> bool:
        lowered = f"{self.status} {' '.join(self.metadata.values())}".lower()
        if self.kind.lower() in {"folder", "directory", "dir"}:
            return False
        return not any(token in lowered for token in ("same", "equal", "match", "unchanged", "identical"))

    @property
    def is_paired_change(self) -> bool:
        lowered = self.status.lower()
        orphan_words = ("orphan", "left-only", "right-only", "left only", "right only", "missing")
        return self.is_difference and not any(word in lowered for word in orphan_words)


def parse_folder_report(xml_path: Path) -> list[DiffEntry]:
    if not xml_path.exists():
        raise ReportError(f"Beyond Compare XML report was not created: {xml_path}")

    try:
        root = ET.parse(xml_path).getroot()
    except ET.ParseError as exc:
        raise ReportError(f"could not parse Beyond Compare XML report {xml_path}: {exc}") from exc

    entries: list[DiffEntry] = []
    seen: set[tuple[str, str]] = set()
    walk_xml(root, [], entries, seen)
    return entries


def walk_xml(node: ET.Element, parents: list[str], entries: list[DiffEntry], seen: set[tuple[str, str]]) -> None:
    attrs = {strip_ns(key).lower(): value for key, value in node.attrib.items()}
    tag = strip_ns(node.tag).lower()

    side = side_values(node)
    name = (
        first_value(attrs, "name", "filename", "file", "path", "relativepath", "relative")
        or side.get("name", "")
    )
    kind = first_value(attrs, "type", "kind", "itemtype") or tag
    status = first_value(
        attrs,
        "status",
        "state",
        "comparison",
        "result",
        "diff",
        "difference",
        "side",
    )

    current_parents = parents
    if name and tag == "foldercomp":
        current_parents = parents + [name]

    if name and (tag == "filecomp" or looks_like_file(tag, kind, attrs)):
        path = name if has_path_separator(name) else "/".join(parents + [name])
        metadata = {key: value for key, value in attrs.items() if key not in {"name", "filename", "file"}}
        metadata.update({key: value for key, value in side.items() if key != "name"})
        entry = DiffEntry(path=path, status=status or infer_status(attrs), kind=kind, metadata=metadata)
        key = (entry.path, entry.status)
        if key not in seen:
            seen.add(key)
            entries.append(entry)

    for child in node:
        walk_xml(child, current_parents, entries, seen)


def strip_ns(name: str) -> str:
    return name.rsplit("}", 1)[-1]


def first_value(values: dict[str, str], *keys: str) -> str:
    for key in keys:
        value = values.get(key)
        if value:
            return value
    return ""


def side_values(node: ET.Element) -> dict[str, str]:
    values: dict[str, str] = {}
    for side_name in ("lt", "rt"):
        side = child_by_tag(node, side_name)
        if side is None:
            continue
        prefix = "left" if side_name == "lt" else "right"
        for child in side:
            key = strip_ns(child.tag).lower()
            text = (child.text or "").strip()
            if not text:
                continue
            if key == "name" and "name" not in values:
                values["name"] = text
            values[f"{prefix}_{key}"] = text
    return values


def child_by_tag(node: ET.Element, wanted: str) -> ET.Element | None:
    for child in node:
        if strip_ns(child.tag).lower() == wanted:
            return child
    return None


def looks_like_folder(tag: str, kind: str, attrs: dict[str, str]) -> bool:
    text = f"{tag} {kind} {attrs.get('folder', '')} {attrs.get('directory', '')}".lower()
    return any(word in text for word in ("folder", "directory", "dir"))


def looks_like_file(tag: str, kind: str, attrs: dict[str, str]) -> bool:
    text = f"{tag} {kind}".lower()
    if any(word in text for word in ("folder", "directory", "dir")):
        return False
    if any(word in text for word in ("file", "item")):
        return True
    return any(key in attrs for key in ("status", "state", "comparison", "result", "diff", "difference"))


def has_path_separator(path: str) -> bool:
    return "/" in path or "\\" in path


def infer_status(attrs: dict[str, str]) -> str:
    for key, value in attrs.items():
        lowered = f"{key}={value}".lower()
        if any(word in lowered for word in ("orphan", "diff", "newer", "older", "missing", "mismatch")):
            return value
    return "different"


def normalize_archive_path(path: str) -> str:
    normalized = path.replace("\\", "/").strip("/")
    while normalized.startswith("./"):
        normalized = normalized[2:]
    return normalized


def generate_file_raw_reports(
    bc_path: Path,
    left: Path,
    right: Path,
    raw_dir: Path,
    entry: DiffEntry,
    child_name: str,
) -> None:
    html_path = raw_dir / f"{child_name}.html"
    patch_path = raw_dir / f"{child_name}.diff"
    script_path = raw_dir / f"{child_name}.bcscript"
    log_path = raw_dir / f"{child_name}.log"
    command_log = raw_dir / f"{child_name}.command.log"

    lines = [
        script_line("log verbose", log_path),
        "criteria rules-based",
        script_line("load", left, right),
        "expand all",
        f"select {quote_bc_path(entry.path)}",
        script_line(
            "file-report layout:side-by-side options:display-mismatches output-to:",
            html_path,
            suffix=" output-options:html-color",
        ),
    ]
    if is_probably_text(entry.path):
        lines.append(script_line("text-report layout:patch options:patch-unified output-to:", patch_path))

    write_script(script_path, lines)
    try:
        run_bcompare(bc_path, script_path, command_log)
    except ReportError as exc:
        command_log.write_text(command_log.read_text(encoding="utf-8") + f"\n\nnon-fatal: {exc}\n", encoding="utf-8")

    entry.raw_html = rel_from_report(html_path)
    entry.raw_patch = rel_from_report(patch_path) if patch_path.exists() else ""
    entry.raw_log = rel_from_report(command_log)


def generate_python_text_diff(
    left: Path,
    right: Path,
    raw_dir: Path,
    entry: DiffEntry,
    child_name: str,
) -> None:
    diff_path = raw_dir / f"{child_name}.python.diff"
    try:
        left_text = read_tar_text_member(left, entry.path)
        right_text = read_tar_text_member(right, entry.path)
    except ReportError as exc:
        log_path = raw_dir / f"{child_name}.python-diff.log"
        log_path.write_text(str(exc) + "\n", encoding="utf-8")
        entry.raw_log = rel_from_report(log_path)
        return

    diff_lines = list(
        difflib.unified_diff(
            left_text.splitlines(),
            right_text.splitlines(),
            fromfile=f"left/{entry.path}",
            tofile=f"right/{entry.path}",
            lineterm="",
        )
    )
    if not diff_lines:
        return
    diff_path.write_text("\n".join(diff_lines) + "\n", encoding="utf-8")
    entry.raw_patch = rel_from_report(diff_path)
    entry.diff_excerpt = "\n".join(diff_lines[:120])


def generate_class_detail(
    left: Path,
    right: Path,
    raw_dir: Path,
    entry: DiffEntry,
    child_name: str,
    mode: str,
    explicit_javap: str | None,
) -> None:
    detail_dir = raw_dir / "classes" / child_name
    log_path = raw_dir / f"{child_name}.javap.log"
    try:
        left_bytes = read_tar_member_bytes(left, entry.path)
        right_bytes = read_tar_member_bytes(right, entry.path)
    except ReportError as exc:
        log_path.write_text(str(exc) + "\n", encoding="utf-8")
        entry.raw_log = rel_from_report(log_path)
        entry.detail_note = f"Class detail could not be generated: {exc}"
        return

    entry.binary_detail = {
        "left_size": len(left_bytes),
        "right_size": len(right_bytes),
        "left_sha256": sha256_hex(left_bytes),
        "right_sha256": sha256_hex(right_bytes),
        "binary_equal": left_bytes == right_bytes,
    }

    if mode == "none":
        entry.detail_note = "Class binary detail was generated, but javap bytecode diff is disabled."
        return

    javap = resolve_javap(explicit_javap)
    if javap is None:
        log_path.write_text(
            "javap was not found. Install a JDK and make javap available on PATH, "
            "or pass --javap-path C:\\path\\to\\javap.exe.\n",
            encoding="utf-8",
        )
        entry.raw_log = rel_from_report(log_path)
        entry.detail_note = (
            "Class bytes differ, but javap was not found. Binary hash/size evidence is shown above. "
            "Install a JDK or pass --javap-path to generate bytecode-level detail."
        )
        return

    left_class = detail_dir / "left" / Path(entry.path).name
    right_class = detail_dir / "right" / Path(entry.path).name
    left_class.parent.mkdir(parents=True, exist_ok=True)
    right_class.parent.mkdir(parents=True, exist_ok=True)
    left_class.write_bytes(left_bytes)
    right_class.write_bytes(right_bytes)

    left_javap = raw_dir / f"{child_name}.left.javap.txt"
    right_javap = raw_dir / f"{child_name}.right.javap.txt"
    diff_path = raw_dir / f"{child_name}.javap.diff"

    left_result = run_javap(javap, left_class)
    right_result = run_javap(javap, right_class)
    left_javap.write_text(left_result.stdout, encoding="utf-8", errors="replace")
    right_javap.write_text(right_result.stdout, encoding="utf-8", errors="replace")

    if left_result.returncode != 0 or right_result.returncode != 0:
        log_path.write_text(
            "left javap command: "
            + " ".join(left_result.command)
            + f"\nleft exit code: {left_result.returncode}\nleft stderr:\n{left_result.stderr}\n\n"
            + "right javap command: "
            + " ".join(right_result.command)
            + f"\nright exit code: {right_result.returncode}\nright stderr:\n{right_result.stderr}\n",
            encoding="utf-8",
        )
        entry.raw_log = rel_from_report(log_path)
        entry.detail_note = (
            "Class bytes differ, but javap failed for at least one side. "
            "Check the javap log and the binary hash/size evidence above."
        )
        return

    diff_lines = list(
        difflib.unified_diff(
            left_result.stdout.splitlines(),
            right_result.stdout.splitlines(),
            fromfile=f"left/{entry.path}.javap",
            tofile=f"right/{entry.path}.javap",
            lineterm="",
        )
    )
    if diff_lines:
        diff_path.write_text("\n".join(diff_lines) + "\n", encoding="utf-8")
        entry.raw_patch = rel_from_report(diff_path)
        entry.diff_excerpt = "\n".join(diff_lines[:160])
        entry.detail_note = "Class bytecode detail was generated with javap -verbose -p -c."
    else:
        entry.detail_note = (
            "Class binary bytes differ, but javap output is identical. "
            "The change may be in attributes not emitted differently by javap or in non-semantic binary data."
        )


class JavapResult:
    def __init__(self, command: list[str], returncode: int, stdout: str, stderr: str):
        self.command = command
        self.returncode = returncode
        self.stdout = stdout
        self.stderr = stderr


def resolve_javap(explicit_path: str | None) -> Path | None:
    if explicit_path:
        path = Path(explicit_path).expanduser()
        return path if path.exists() else None
    found = shutil.which("javap") or shutil.which("javap.exe")
    return Path(found) if found else None


def run_javap(javap: Path, class_file: Path) -> JavapResult:
    command = [str(javap), "-verbose", "-p", "-c", str(class_file)]
    completed = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", errors="replace")
    return JavapResult(command, completed.returncode, completed.stdout, completed.stderr)


def read_tar_member_bytes(tar_path: Path, member_path: str) -> bytes:
    normalized_target = normalize_archive_path(member_path)
    try:
        with tarfile.open(tar_path, mode="r:*") as archive:
            members = {normalize_archive_path(member.name): member for member in archive.getmembers() if member.isfile()}
            member = members.get(normalized_target)
            if member is None:
                raise ReportError(f"tar member not found: {member_path} in {tar_path}")
            extracted = archive.extractfile(member)
            if extracted is None:
                raise ReportError(f"tar member could not be read: {member_path} in {tar_path}")
            return extracted.read()
    except (tarfile.TarError, OSError) as exc:
        raise ReportError(f"could not read tar member {member_path} from {tar_path}: {exc}") from exc


def read_tar_text_member(tar_path: Path, member_path: str) -> str:
    return decode_text(read_tar_member_bytes(tar_path, member_path))


def decode_text(data: bytes) -> str:
    for encoding in ("utf-8", "gb18030", "utf-16"):
        try:
            return data.decode(encoding)
        except UnicodeDecodeError:
            continue
    return data.decode("utf-8", errors="replace")


def should_generate_file_report(entry: DiffEntry, mode: str) -> bool:
    if not entry.is_paired_change:
        return False
    if mode == "none":
        return False
    if mode == "all":
        return True
    return is_probably_text(entry.path)


def is_probably_text(path: str) -> bool:
    suffix = Path(path).suffix.lower()
    return suffix in TEXT_EXTENSIONS or suffix == ""


def is_class_file(path: str) -> bool:
    return Path(path).suffix.lower() == ".class"


def sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def write_child_markdown(path: Path, entry: DiffEntry, title: str, folder_html: Path, template_dir: Path) -> None:
    raw_links: list[str] = []
    if entry.raw_html:
        raw_links.append(f"- Raw file HTML report: [{Path(entry.raw_html).name}](../{entry.raw_html})")
    if entry.raw_patch:
        raw_links.append(f"- Raw unified patch: [{Path(entry.raw_patch).name}](../{entry.raw_patch})")
    if entry.raw_log:
        raw_links.append(f"- Command log: [{Path(entry.raw_log).name}](../{entry.raw_log})")

    metadata_rows: list[str] = []
    if entry.metadata:
        for key, value in sorted(entry.metadata.items()):
            metadata_rows.append(f"| `{escape_md(key)}` | {escape_md(value)} |")
    if entry.binary_detail:
        for key, value in sorted(entry.binary_detail.items()):
            metadata_rows.append(f"| `binary_{escape_md(key)}` | {escape_md(value)} |")
    if not metadata_rows:
        metadata_rows.append("| `_none` |  |")

    patch_text = read_patch(entry.raw_patch, path.parent.parent) if entry.raw_patch else ""
    if patch_text:
        detail = "```diff\n" + patch_text.rstrip() + "\n```"
    elif entry.detail_note:
        detail = escape_md(entry.detail_note)
    else:
        detail = "No text patch was generated. Use the linked raw Beyond Compare report for the detailed view."

    content = render_template(
        template_dir / "file.md",
        {
            "title": title,
            "archive_path": entry.path,
            "status": entry.status,
            "folder_html_name": folder_html.name,
            "folder_html_link": rel_from_report(folder_html),
            "raw_links": "\n".join(raw_links),
            "metadata_rows": "\n".join(metadata_rows),
            "detail": detail,
        },
    )
    path.write_text(content, encoding="utf-8")


def read_patch(raw_patch: str, report_root: Path) -> str:
    patch_path = report_root / raw_patch
    if not patch_path.exists():
        return ""
    text = patch_path.read_text(encoding="utf-8", errors="replace").strip()
    return text


def write_index_markdown(
    path: Path,
    title: str,
    left: Path,
    right: Path,
    out_dir: Path,
    bc_path: Path,
    folder_html: Path,
    folder_xml: Path,
    entries: list[DiffEntry],
    template_dir: Path,
) -> None:
    generated_at = dt.datetime.now(dt.timezone.utc).astimezone().isoformat(timespec="seconds")
    counts: dict[str, int] = {}
    for entry in entries:
        counts[entry.status] = counts.get(entry.status, 0) + 1

    count_rows: list[str] = []
    for status, count in sorted(counts.items()):
        count_rows.append(f"| `{escape_md(status)}` | {count} |")
    if not counts:
        count_rows.append("| `no differences parsed` | 0 |")

    difference_rows: list[str] = []
    for entry in sorted(entries, key=lambda item: item.path.lower()):
        detail = f"[report]({entry.child_md})" if entry.child_md else ""
        difference_rows.append(f"| `{escape_md(entry.path)}` | `{escape_md(entry.status)}` | {detail} |")
    if not difference_rows:
        difference_rows.append("| `_none` |  |  |")

    content = render_template(
        template_dir / "index.md",
        {
            "title": title,
            "generated_at": generated_at,
            "left": str(left),
            "right": str(right),
            "bc_path": str(bc_path),
            "difference_count": str(len(entries)),
            "folder_html_name": folder_html.name,
            "folder_html_link": rel_from_report(folder_html),
            "folder_xml_name": folder_xml.name,
            "folder_xml_link": rel_from_report(folder_xml),
            "count_rows": "\n".join(count_rows),
            "difference_rows": "\n".join(difference_rows),
        },
    )
    path.write_text(content, encoding="utf-8")


def write_diff_index(
    path: Path,
    title: str,
    left: Path,
    right: Path,
    folder_html: Path,
    folder_xml: Path,
    entries: list[DiffEntry],
) -> None:
    counts: dict[str, int] = {}
    for entry in entries:
        counts[entry.status] = counts.get(entry.status, 0) + 1
    payload = {
        "title": title,
        "generated_at": dt.datetime.now(dt.timezone.utc).astimezone().isoformat(timespec="seconds"),
        "left_tar": str(left),
        "right_tar": str(right),
        "difference_count": len(entries),
        "counts": counts,
        "raw": {
            "folder_html": rel_from_report(folder_html),
            "folder_xml": rel_from_report(folder_xml),
        },
        "entries": [entry_to_dict(entry) for entry in sorted(entries, key=lambda item: item.path.lower())],
    }
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def entry_to_dict(entry: DiffEntry) -> dict[str, object]:
    return {
        "path": entry.path,
        "status": entry.status,
        "kind": entry.kind,
        "metadata": entry.metadata,
        "child_report": entry.child_md,
        "raw_html": entry.raw_html,
        "raw_patch": entry.raw_patch,
        "raw_log": entry.raw_log,
        "diff_excerpt": entry.diff_excerpt,
        "binary_detail": entry.binary_detail,
        "detail_note": entry.detail_note,
        "is_paired_change": entry.is_paired_change,
        "is_probably_text": is_probably_text(entry.path),
        "risk_category": classify_risk(entry.path, entry.status),
        "change_category": classify_change(entry.path),
    }


def classify_change(path: str) -> str:
    suffix = Path(path).suffix.lower()
    lowered = path.lower()
    if suffix in {".properties", ".xml", ".yaml", ".yml", ".json", ".ini", ".conf", ".config", ".env"}:
        return "config"
    if suffix in {".sh", ".bat", ".cmd", ".ps1"}:
        return "script"
    if suffix in {".jar", ".war", ".ear", ".zip", ".gz", ".tgz"}:
        return "binary-package"
    if suffix in {".class"}:
        return "compiled-class"
    if "license" in lowered or "notice" in lowered or suffix in {".md", ".txt"}:
        return "document-text"
    return "other"


def classify_risk(path: str, status: str) -> str:
    category = classify_change(path)
    lowered = f"{path} {status}".lower()
    if any(token in lowered for token in ("orphan", "missing", "left-only", "right-only", "left only", "right only")):
        return "high"
    if category in {"config", "script", "compiled-class"}:
        return "high"
    if category == "binary-package":
        return "medium"
    return "low"


def render_template(path: Path, values: dict[str, str]) -> str:
    return path.read_text(encoding="utf-8").format(**values).rstrip() + "\n"


def unique_child_name(path: str, used: set[str]) -> str:
    base = re.sub(r"[^A-Za-z0-9._-]+", "_", path).strip("._-") or "file"
    base = base[:90]
    candidate = base
    index = 2
    while candidate.lower() in used:
        candidate = f"{base[:80]}_{index}"
        index += 1
    used.add(candidate.lower())
    return candidate


def rel_from_report(path: Path) -> str:
    parts = path.parts
    if "raw" in parts:
        return "/".join(parts[parts.index("raw") :])
    if "files" in parts:
        return "/".join(parts[parts.index("files") :])
    return path.name


def escape_md(value: str) -> str:
    return html.escape(str(value)).replace("|", "\\|")


if __name__ == "__main__":
    raise SystemExit(main())
