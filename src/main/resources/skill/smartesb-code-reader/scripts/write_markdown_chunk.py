#!/usr/bin/env python
"""Write bounded Markdown content to a target file.

This helper prevents agents from accidentally sending a whole long report
through one shell command or an interactive write tool.

With --auto-split, the script accepts oversized input and splits at a safe
boundary (paragraph, heading, table row, or sentence end), then writes every
split chunk in one invocation.
"""

from __future__ import annotations

import argparse
import base64
import binascii
import re
import sys
from pathlib import Path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Write a bounded Markdown chunk.")
    parser.add_argument("--path", required=True, help="Target Markdown file path.")
    parser.add_argument(
        "--mode",
        choices=("init", "append"),
        default="append",
        help="init overwrites/creates the file; append appends to the file.",
    )
    parser.add_argument("--max-lines", type=int, default=40)
    parser.add_argument("--max-chars", type=int, default=2500)
    input_group = parser.add_mutually_exclusive_group()
    input_group.add_argument(
        "--chunk-file",
        help="Read the Markdown chunk from this UTF-8 file instead of stdin.",
    )
    input_group.add_argument(
        "--text",
        help="Read the Markdown chunk directly from this argument.",
    )
    input_group.add_argument(
        "--text-base64",
        help="Read a UTF-8 Markdown chunk from this base64 argument.",
    )
    parser.add_argument(
        "--allow-empty",
        action="store_true",
        help="Allow an empty chunk. Disabled by default.",
    )
    parser.add_argument(
        "--auto-split",
        action="store_true",
        help="Accept oversized input and split/write all safe portions in one invocation.",
    )
    parser.add_argument(
        "--remainder-file",
        help="When --auto-split cannot make progress, write unwritten content to this file.",
    )
    return parser.parse_args()


# ---------------------------------------------------------------------------
# auto-split helpers
# ---------------------------------------------------------------------------

def _find_fence_opener(lines: list[str], index: int) -> int | None:
    """Return the line index of the opening ``` for the fenced block that
    contains *index*, or None if *index* is not inside a fenced block."""
    depth = 0
    opener: int | None = None
    for i in range(index + 1):
        stripped = lines[i].strip()
        if stripped.startswith("```"):
            if depth % 2 == 0:
                opener = i  # entering a block
            depth += 1
    if depth % 2 == 1 and opener is not None:
        return opener
    return None


def _char_count(lines: list[str]) -> int:
    return sum(len(line) for line in lines)


def _find_safe_cut_point(lines: list[str], max_lines: int, max_chars: int) -> int:
    """Return the index (0-based, exclusive) at which to split *lines*.

    The returned index is always <= len(lines).  When the index equals
    len(lines), everything fits and no remainder is needed.
    """
    total_lines = len(lines)

    # --- fast path: everything fits ---
    if total_lines <= max_lines and _char_count(lines) <= max_chars:
        return total_lines

    # --- determine effective scan window ---
    # line-based upper bound
    line_bound = min(max_lines, total_lines)

    # char-based upper bound
    char_bound = total_lines
    acc = 0
    for i, line in enumerate(lines):
        acc += len(line)
        if acc > max_chars:
            char_bound = i  # this line (i) would exceed the limit
            break

    effective = min(line_bound, char_bound)
    if effective == 0:
        # The first line alone exceeds max_chars; do not write an oversized chunk.
        return 0

    # --- scan backwards from *effective* looking for a clean boundary ---
    # Priority levels (higher = better):
    #   4 – before a ## / ### heading
    #   3 – at an empty line (paragraph gap)
    #   2 – after a sentence-ending line (。；)
    #   1 – at a table-row boundary (| ... |)
    #   0 – hard cut

    search_start = max(effective // 2, 1)  # don't go below 50 % of effective
    search_end = effective

    best_level = -1
    best_cut = effective  # fallback

    for i in range(search_end, search_start - 1, -1):
        # Skip cuts that would split a fenced block (treat block as atomic).
        # Check if the last line we keep (i-1) is inside a fenced block.
        if i > 0:
            opener = _find_fence_opener(lines, i - 1)
            if opener is not None:
                # Walk the search position up to just before the opener.
                if opener - 1 >= search_start:
                    i = opener  # cut right before the opening ```
                else:
                    continue  # opener is above search window; skip

        level = -1
        current_line = lines[i - 1] if i > 0 else ""
        next_line = lines[i] if i < total_lines else ""

        # 4 – heading boundary
        if next_line.strip().startswith("##"):
            level = 4
        # 3 – paragraph boundary (empty line)
        elif i < total_lines and next_line.strip() == "":
            level = 3
        elif i > 0 and current_line.strip() == "":
            level = 3
        # 2 – sentence boundary
        elif current_line.rstrip().endswith(("。", "；")):
            level = 2
        # 1 – table row boundary
        elif current_line.strip().startswith("|") and next_line.strip().startswith("|"):
            level = 1

        if level > best_level:
            best_level = level
            best_cut = i
            if level == 4:  # heading is best possible – stop searching
                break

    # Final safety net: don't split a fenced block if fallback is inside one.
    if best_cut < total_lines and best_cut > 0:
        opener = _find_fence_opener(lines, best_cut - 1)
        if opener is not None:
            best_cut = opener  # cut right before the opening ```

    return best_cut


def _lines_to_text(lines: list[str]) -> str:
    return "".join(lines)


def _write_text(target: Path, text: str, mode: str) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    if mode == "init":
        temp_path = target.with_name(f".{target.name}.tmp")
        temp_path.write_text(text, encoding="utf-8")
        temp_path.replace(target)
    else:
        with target.open("a", encoding="utf-8") as handle:
            handle.write(text)


def _sanitize_mermaid_source(source: str) -> str:
    """Remove sequenceDiagram activation markers that often break renderers.

    Generated analysis diagrams favor readability over lifeline activation
    state. Plain arrows are less expressive, but they avoid invalid
    deactivate sequences inside alt/else branches.
    """
    sanitized_lines: list[str] = []
    for line in source.splitlines(keepends=True):
        stripped = line.strip()
        if re.fullmatch(r"(?i)(activate|deactivate)\s+[A-Za-z_][A-Za-z0-9_]*", stripped):
            continue
        line = re.sub(r"(->>|-->>)([+-])\s*([A-Za-z_][A-Za-z0-9_]*)", r"\1\3", line)
        sanitized_lines.append(line)
    return "".join(sanitized_lines)


def _sanitize_mermaid_chunks(markdown: str) -> str:
    fence_pattern = re.compile(r"(?ims)(^[ \t]*```mermaid[^\n]*\n)(.*?)(^[ \t]*```)")
    markdown = fence_pattern.sub(
        lambda match: match.group(1) + _sanitize_mermaid_source(match.group(2)) + match.group(3),
        markdown,
    )
    if markdown.lstrip().startswith("sequenceDiagram"):
        return _sanitize_mermaid_source(markdown)
    return markdown


def _auto_split_write(args: argparse.Namespace, chunk: str) -> int:
    lines = chunk.splitlines(keepends=True)
    total_lines = len(lines)
    total_chars = len(chunk)
    remaining = lines
    target = Path(args.path)
    chunks: list[list[str]] = []

    while remaining:
        cut = _find_safe_cut_point(remaining, args.max_lines, args.max_chars)
        if cut <= 0:
            remainder_text = _lines_to_text(remaining)
            if args.remainder_file:
                remainder_path = Path(args.remainder_file)
                remainder_path.parent.mkdir(parents=True, exist_ok=True)
                remainder_path.write_text(remainder_text, encoding="utf-8")
            print(
                "Refusing oversized Markdown chunk: no safe split point fits within "
                f"{args.max_lines} lines and {args.max_chars} chars. "
                f"Unwritten content saved to {args.remainder_file or '(no --remainder-file)'}.",
                file=sys.stderr,
            )
            return 6

        chunks.append(remaining[:cut])
        remaining = remaining[cut:]

    text_chunks = [_lines_to_text(current) for current in chunks]
    full_text = "".join(text_chunks)

    target.parent.mkdir(parents=True, exist_ok=True)
    if args.mode == "init":
        temp_path = target.with_name(f".{target.name}.tmp")
        temp_path.write_text(full_text, encoding="utf-8")
        temp_path.replace(target)
    else:
        with target.open("a", encoding="utf-8") as handle:
            handle.write(full_text)

    wrote_lines = sum(len(current) for current in chunks)
    wrote_chars = len(full_text)
    chunk_count = len(chunks)

    if args.remainder_file:
        remainder_path = Path(args.remainder_file)
        remainder_path.parent.mkdir(parents=True, exist_ok=True)
        remainder_path.write_text("", encoding="utf-8")

    print(
        f"Auto-split: wrote {wrote_lines}/{total_lines} lines "
        f"({wrote_chars}/{total_chars} chars) to {target} in {chunk_count} chunks."
    )
    return 0


# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------


def _read_chunk(args: argparse.Namespace) -> str:
    if args.chunk_file is not None:
        return Path(args.chunk_file).read_text(encoding="utf-8")
    if args.text_base64 is not None:
        try:
            raw = base64.b64decode(args.text_base64.encode("ascii"), validate=True)
            return raw.decode("utf-8")
        except (UnicodeEncodeError, binascii.Error, UnicodeDecodeError) as exc:
            raise ValueError(f"Invalid --text-base64 UTF-8 payload: {exc}") from exc
    if args.text is not None:
        return args.text
    return sys.stdin.read()


def main() -> int:
    args = parse_args()

    try:
        chunk = _read_chunk(args)
    except OSError as exc:
        print(f"Failed to read Markdown chunk: {exc}", file=sys.stderr)
        return 5
    except ValueError as exc:
        print(str(exc), file=sys.stderr)
        return 7

    chunk = _sanitize_mermaid_chunks(chunk)

    if not args.allow_empty and not chunk.strip():
        print("Refusing to write an empty Markdown chunk.", file=sys.stderr)
        return 2

    # --- auto-split path ---
    if args.auto_split:
        return _auto_split_write(args, chunk)

    # --- strict path (original behaviour) ---
    lines = chunk.splitlines()
    if len(lines) > args.max_lines:
        print(
            f"Refusing oversized Markdown chunk: {len(lines)} lines > "
            f"{args.max_lines} lines.",
            file=sys.stderr,
        )
        return 3

    if len(chunk) > args.max_chars:
        print(
            f"Refusing oversized Markdown chunk: {len(chunk)} chars > "
            f"{args.max_chars} chars.",
            file=sys.stderr,
        )
        return 4

    if chunk and not chunk.endswith("\n"):
        chunk += "\n"

    target = Path(args.path)
    _write_text(target, chunk, args.mode)

    print(f"Wrote {len(lines)} lines and {len(chunk)} chars to {target}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
